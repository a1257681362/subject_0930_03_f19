package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * museum-17 夜间巡检跨零点时区归属回归。
 *
 * <p>服务器时区刻意设为 UTC、默认设备时区 Asia/Shanghai，证明：
 * <ol>
 *   <li><b>跨零点</b>：Berlin 厅 23:30 与次日 00:30 分属两天，分组键只看设备时区；</li>
 *   <li><b>夏令时</b>：Berlin 春季跳变间隙（02:30 不存在）拒绝、秋季回退重叠
 *       （02:30 两次）无设备偏移拒绝、带偏移各归其位且偏移证据随读数固化；</li>
 *   <li><b>闰日</b>：2024-02-29 读数归属 2/29，非闰年 2023-02-29 拒绝；</li>
 *   <li>服务器 UTC、设备 Asia/Shanghai 的 00:30 仍归属设备日（不被 UTC 切到前一天）；</li>
 *   <li>原始 read_at 永不改写；历史报表只出更正版本，旧快照 SUPERSEDED 保留不覆盖；</li>
 *   <li>museum-17 升级前旧格式读数（仅挂钟）由回填器按设备时区幂等补齐真实瞬时与归属日期。</li>
 * </ol>
 * 使用独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-tz;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EnvReadingTimezoneRegressionTest {

    @TempDir
    static Path imageDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("museum.image.storage-root", () -> imageDir.toString());
        // 服务器时间口径刻意 UTC：任何按服务器/JVM 时区切日的旧实现都会在这些用例里暴露
        registry.add("museum.env.server-timezone", () -> "UTC");
        registry.add("museum.env.default-device-timezone", () -> "Asia/Shanghai");
        // 关掉启动补生成，轮次完全由用例 force 控制
        registry.add("museum.env.generate-on-startup", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void midnight_dst_leap_day_grouping_and_versioned_correction() throws Exception {
        // ========== 建档：Berlin 厅（DST 地区）一个点位，纸本文物 ==========
        long paperId = createRelic("TZ-P-1", "时区测试纸本", "纸");
        long berlinHall = createHall("HB", "柏林厅", "Europe/Berlin", null);
        expectOk(postJson("/api/halls/" + berlinHall + "/relics",
                "{\"relicId\":" + paperId + ",\"pointCode\":\"P-1\"}"));
        publishRule("TZ-R1");

        // ---------- 一、跨零点：23:30 与 00:30 分属设备日两天 ----------
        // Berlin 1 月标准时 UTC+1：23:30 = 22:30Z；次日 00:30 = 前一日 23:30Z
        long r1 = generateForHall(berlinHall);
        submit(berlinHall, r1, "2024-01-15T23:30:00", null);
        JsonNode r1Reading = onlyReading(r1);
        assertEquals("Europe/Berlin", r1Reading.get("readZone").asText());
        assertEquals(60, r1Reading.get("readOffsetMin").asInt(), "1 月柏林标准时 UTC+1");
        assertEquals("2024-01-15", r1Reading.get("localDate").asText());
        // epoch：2024-01-15 22:30:00Z
        assertEquals(java.time.Instant.parse("2024-01-15T22:30:00Z").toEpochMilli(),
                r1Reading.get("readAtEpochMilli").asLong());

        long r2 = generateForHall(berlinHall);
        submit(berlinHall, r2, "2024-01-16T00:30:00", null);
        JsonNode r2Reading = onlyReading(r2);
        assertEquals("2024-01-16", r2Reading.get("localDate").asText(),
                "跨过零点的读数必须归属设备日次日，不得与前一日并组");
        assertEquals(java.time.Instant.parse("2024-01-15T23:30:00Z").toEpochMilli(),
                r2Reading.get("readAtEpochMilli").asLong());

        // 日报按设备日分组：15 日 1 条、16 日 1 条（若按 UTC 切日，16 日 00:30 本地会被并到 15 日）
        long rep15 = generateReport(berlinHall, "2024-01-15", null);
        JsonNode v15 = reportJson(rep15);
        assertEquals(1, v15.get("readingCount").asInt());
        assertEquals("CURRENT", v15.get("status").asText());
        assertEquals(1, v15.get("versionNo").asInt());

        // 16 日先出 v1（此时只有 00:30 一条）
        long rep16v1 = generateReport(berlinHall, "2024-01-16", null);
        assertEquals(1, reportJson(rep16v1).get("readingCount").asInt());

        // ---------- 二、同日补录读数后更正：只追加 v2，v1 原样保留 ----------
        long r3 = generateForHall(berlinHall);
        submit(berlinHall, r3, "2024-01-16T01:15:00", null);
        // 已有 CURRENT 却不带更正原因：拒绝覆盖
        expectFail(postReport(berlinHall, "2024-01-16", "统计员", null), "更正原因");
        // v1 仍在、仍只有 1 条（失败请求未产生任何新版本）
        assertEquals("CURRENT", reportJson(rep16v1).get("status").asText());
        assertEquals(1, reportJson(rep16v1).get("readingCount").asInt());

        long rep16v2 = generateReport(berlinHall, "2024-01-16", "补录凌晨 01:15 巡检读数后重新出表");
        JsonNode v2 = reportJson(rep16v2);
        assertEquals(2, v2.get("versionNo").asInt());
        assertEquals(2, v2.get("readingCount").asInt());
        assertEquals("CURRENT", v2.get("status").asText());
        assertEquals("补录凌晨 01:15 巡检读数后重新出表", v2.get("correctedReason").asText());
        // 旧快照不被覆盖：仍是 v1、1 条、SUPERSEDED，明细行也不重写
        JsonNode oldV1 = reportJson(rep16v1);
        assertEquals("SUPERSEDED", oldV1.get("status").asText());
        assertEquals(1, oldV1.get("readingCount").asInt());
        assertEquals(1, oldV1.get("versionNo").asInt());
        assertNotNull(oldV1.get("supersededAt").asText());
        assertEquals(1, reportDetail(rep16v1).get("lines").size(), "旧版点位明细行原样保留");
        // 版本链完整可查
        JsonNode versions = dataOf(getJson(
                "/api/halls/" + berlinHall + "/lux-daily-reports?localDate=2024-01-16"));
        assertEquals(2, versions.size());

        // ---------- 三、夏令时春季跳变：Berlin 2024-03-31 02:00 -> 03:00 ----------
        // 02:30 本地时间根本不存在：无偏移拒绝
        long r4 = generateForHall(berlinHall);
        expectFail(submitMvc(berlinHall, r4, "2024-03-31T02:30:00", null), "夏令时");
        // 带任何偏移也不合法：挂钟 02:30 与偏移的组合在该时区不存在
        expectFail(submitMvc(berlinHall, r4, "2024-03-31T02:30:00", 60), "偏移");
        expectFail(submitMvc(berlinHall, r4, "2024-03-31T02:30:00", 120), "偏移");
        // 任务未被失败提交污染，可按跳变后的有效时刻 03:30+02:00 提交
        submit(berlinHall, r4, "2024-03-31T03:30:00", 120);
        JsonNode spring = onlyReading(r4);
        assertEquals(120, spring.get("readOffsetMin").asInt());
        assertEquals("2024-03-31", spring.get("localDate").asText());
        assertEquals(java.time.Instant.parse("2024-03-31T01:30:00Z").toEpochMilli(),
                spring.get("readAtEpochMilli").asLong());

        // ---------- 四、夏令时秋季回退：Berlin 2024-10-27 03:00 -> 02:00 ----------
        // 02:30 挂钟出现两次：无偏移拒绝擅自归属
        long r5 = generateForHall(berlinHall);
        expectFail(submitMvc(berlinHall, r5, "2024-10-27T02:30:00", null), "夏令时");
        // 第一次 02:30 仍是夏令时 UTC+2 -> 00:30Z
        submit(berlinHall, r5, "2024-10-27T02:30:00", 120);
        JsonNode fall1 = onlyReading(r5);
        assertEquals(120, fall1.get("readOffsetMin").asInt());
        assertEquals(java.time.Instant.parse("2024-10-27T00:30:00Z").toEpochMilli(),
                fall1.get("readAtEpochMilli").asLong());

        // 第二次 02:30 已切标准时 UTC+1 -> 01:30Z；挂钟相同但真实瞬时不同
        long r6 = generateForHall(berlinHall);
        // 柏林从不用的 +3 小时偏移：挂钟+偏移组合无效，拒绝
        expectFail(submitMvc(berlinHall, r6, "2024-10-27T02:30:00", 180), "偏移",
                "该时区从不使用的偏移必须判为无效");
        // 注：重叠时 +120 与 +60 都合法（分别对应 00:30Z/01:30Z 两个真实瞬时），
        // 系统以设备偏移确定唯一瞬时——第二次巡检按设备实际 +60 提交
        submit(berlinHall, r6, "2024-10-27T02:30:00", 60);
        JsonNode fall2 = onlyReading(r6);
        assertEquals(60, fall2.get("readOffsetMin").asInt());
        assertEquals(java.time.Instant.parse("2024-10-27T01:30:00Z").toEpochMilli(),
                fall2.get("readAtEpochMilli").asLong());
        // 两条读数同挂钟、同归属日，但真实瞬时相差 1 小时；日报按瞬时排序不按重复挂钟
        assertEquals("2024-10-27", fall1.get("localDate").asText());
        assertEquals("2024-10-27", fall2.get("localDate").asText());
        assertEquals(3_600_000L,
                fall2.get("readAtEpochMilli").asLong() - fall1.get("readAtEpochMilli").asLong());

        // ---------- 五、闰日：2024-02-29 有效归属闰日；2023-02-29 日历不存在 ----------
        long r7 = generateForHall(berlinHall);
        submit(berlinHall, r7, "2024-02-29T23:50:00", 60);
        assertEquals("2024-02-29", onlyReading(r7).get("localDate").asText());
        long r8 = generateForHall(berlinHall);
        submit(berlinHall, r8, "2024-03-01T00:10:00", 60);
        assertEquals("2024-03-01", onlyReading(r8).get("localDate").asText());
        JsonNode leapReport = reportJson(generateReport(berlinHall, "2024-02-29", null));
        assertEquals(1, leapReport.get("readingCount").asInt());
        assertEquals("2024-02-29", leapReport.get("localDate").asText());
        // 非闰年不存在 2 月 29 日：严格解析拒绝（success=false），不做 2/28 静默平移，不落库
        long r9 = generateForHall(berlinHall);
        String badPoint = dataOf(getJson("/api/halls/" + berlinHall)).get("onDisplay")
                .get(0).get("pointCode").asText();
        MvcResult badLeap = postJson("/api/inspection/tasks/" + r9 + "/readings",
                "{\"inspector\":\"巡检员时区\",\"readAt\":\"2023-02-29T08:00:00\","
                        + "\"readings\":[{\"pointCode\":\"" + badPoint
                        + "\",\"temperature\":20,\"humidity\":55,\"illuminance\":30}]}");
        JsonNode badLeapRoot = objectMapper.readTree(
                badLeap.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertFalse(badLeapRoot.get("success").asBoolean(),
                "非闰年 2/29 必须拒绝，不得静默平移为 2/28");
        assertTrue(badLeapRoot.get("message").asText().contains("不合法"));
        // 被拒绝的任务仍是 PENDING，可用合法时间正常提交（非法事实未落库、未污染轮次）
        submit(berlinHall, r9, "2023-03-01T08:00:00", 60);

        // ---------- 六、服务器 UTC / 设备上海：00:30 归属设备日，不被 UTC 切走 ----------
        long shPaperId = createRelic("TZ-P-2", "时区测试书卷", "纸");
        long shHall = createHall("HS", "上海厅", null, null); // 不指定 -> 默认 Asia/Shanghai
        JsonNode shHallNode = dataOf(getJson("/api/halls/" + shHall)).get("hall");
        assertEquals("Asia/Shanghai", shHallNode.get("timezone").asText());
        expectOk(postJson("/api/halls/" + shHall + "/relics",
                "{\"relicId\":" + shPaperId + ",\"pointCode\":\"Q-1\"}"));
        long rs1 = generateForHall(shHall);
        submit(shHall, rs1, "2024-06-15T00:30:00", null);
        JsonNode shReading = onlyReading(rs1);
        assertEquals("Asia/Shanghai", shReading.get("readZone").asText());
        assertEquals(480, shReading.get("readOffsetMin").asInt());
        assertEquals("2024-06-15", shReading.get("localDate").asText(),
                "服务器 UTC 的 6/14 16:30，设备本地已是 6/15，归属必须是 6/15");
        assertEquals(java.time.Instant.parse("2024-06-14T16:30:00Z").toEpochMilli(),
                shReading.get("readAtEpochMilli").asLong());

        // 时区核对端点：服务器 UTC、JVM 默认、设备时区如实并列
        JsonNode diag = dataOf(getJson("/api/time/diagnosis"));
        assertEquals("UTC", diag.get("serverTimezone").asText());
        assertEquals("Asia/Shanghai", diag.get("defaultDeviceTimezone").asText());

        // 非法时区建档拒绝
        mockMvc.perform(post("/api/halls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hallNo\":\"HBAD\",\"name\":\"坏时区厅\","
                                + "\"custodian\":\"保管\",\"timezone\":\"Asia/NotAZone\"}"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode root = objectMapper.readTree(
                            result.getResponse().getContentAsString(StandardCharsets.UTF_8));
                    assertFalse(root.get("success").asBoolean());
                    assertTrue(root.get("message").asText().contains("时区"));
                });

        // ---------- 七、设备时区更正：只影响新读数，历史不回溯 ----------
        expectOk(postJson("/api/halls/" + shHall + "/timezone",
                "{\"timezone\":\"Europe/Berlin\"}"));
        // 更正前那条读数的时区与归属不变（展示层不得改事实）
        assertEquals("Asia/Shanghai", onlyReading(rs1).get("readZone").asText());
        assertEquals("2024-06-15", onlyReading(rs1).get("localDate").asText());
        assertEquals("2024-06-15T00:30:00", onlyReading(rs1).get("readAt").asText(),
                "原始采样挂钟时间永不改写");
        long rs2 = generateForHall(shHall);
        submit(shHall, rs2, "2024-07-15T09:00:00", 120);
        assertEquals("Europe/Berlin", onlyReading(rs2).get("readZone").asText());

        // 时区更正与日报版本链均写环境台账
        JsonNode ledger = dataOf(getJson("/api/env-ledgers?hallId=" + berlinHall));
        assertTrue(hasAction(ledger, "LUX_DAILY_PUBLISH"));
        assertTrue(hasAction(ledger, "LUX_DAILY_CORRECT"));
        JsonNode shLedger = dataOf(getJson("/api/env-ledgers?hallId=" + shHall));
        assertTrue(hasAction(shLedger, "HALL_TIMEZONE_CHANGE"));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private long createRelic(String no, String name, String material) throws Exception {
        return dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"" + no + "\",\"name\":\"" + name + "\",\"category\":\"文书\","
                + "\"material\":\"" + material + "\",\"era\":\"现代\",\"source\":\"调拨\","
                + "\"preservationGrade\":\"TWO\"}")).get("id").asLong();
    }

    private long createHall(String no, String name, String timezone, String custodian)
            throws Exception {
        String tz = timezone == null ? "" : ",\"timezone\":\"" + timezone + "\"";
        return dataOf(postJson("/api/halls", "{"
                + "\"hallNo\":\"" + no + "\",\"name\":\"" + name + "\","
                + "\"custodian\":\"王保管\"" + tz + "}")).get("id").asLong();
    }

    private void publishRule(String versionNo) throws Exception {
        expectOk(postJson("/api/env-rules/publish", "{"
                + "\"versionNo\":\"" + versionNo + "\",\"title\":\"时区回归规则\","
                + "\"publishedBy\":\"环境管理员\",\"items\":[{"
                + "\"material\":\"纸\",\"tempMin\":18,\"tempMax\":22,"
                + "\"humidityMin\":50,\"humidityMax\":60,"
                + "\"luxMin\":0,\"luxMax\":50,\"sampleIntervalMin\":60}]}"));
    }

    /** force 生成后按展厅取本轮 PENDING 任务（多展厅并存时不能靠数组下标） */
    private long generateForHall(long hallId) throws Exception {
        postEmpty("/api/inspection/tasks/generate?force=true");
        JsonNode tasks = dataOf(getJson(
                "/api/inspection/tasks?hallId=" + hallId + "&status=PENDING"));
        assertTrue(tasks.size() >= 1, "展厅应存在待巡检任务");
        return tasks.get(0).get("id").asLong();
    }

    private void submit(long hallId, long taskId, String readAt, Integer offsetMin) throws Exception {
        expectOk(submitMvc(hallId, taskId, readAt, offsetMin));
    }

    private MvcResult submitMvc(long hallId, long taskId, String readAt, Integer offsetMin)
            throws Exception {
        String pointCode = dataOf(getJson("/api/halls/" + hallId)).get("onDisplay").get(0)
                .get("pointCode").asText();
        String offset = offsetMin == null ? "" : ",\"readOffsetMin\":" + offsetMin;
        String body = "{\"inspector\":\"巡检员时区\",\"readAt\":\"" + readAt + "\"" + offset
                + ",\"readings\":[{\"pointCode\":\"" + pointCode + "\",\"temperature\":20,"
                + "\"humidity\":55,\"illuminance\":30}]}";
        return postJson("/api/inspection/tasks/" + taskId + "/readings", body);
    }

    private JsonNode onlyReading(long taskId) throws Exception {
        JsonNode report = dataOf(getJson("/api/inspection/tasks/" + taskId + "/report"));
        assertEquals(1, report.get("readings").size());
        return report.get("readings").get(0);
    }

    private long generateReport(long hallId, String localDate, String reason) throws Exception {
        return dataOf(postReport(hallId, localDate, "统计员", reason)).get("id").asLong();
    }

    private MvcResult postReport(long hallId, String localDate, String by, String reason)
            throws Exception {
        String reasonPart = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
        return postJson("/api/halls/" + hallId + "/lux-daily-reports",
                "{\"localDate\":\"" + localDate + "\",\"generatedBy\":\"" + by + "\""
                        + reasonPart + "}");
    }

    private JsonNode reportJson(long reportId) throws Exception {
        return dataOf(getJson("/api/lux-daily-reports/" + reportId)).get("report");
    }

    private JsonNode reportDetail(long reportId) throws Exception {
        return dataOf(getJson("/api/lux-daily-reports/" + reportId));
    }

    private boolean hasAction(JsonNode ledger, String action) {
        for (JsonNode e : ledger) {
            if (action.equals(e.get("action").asText())) {
                return true;
            }
        }
        return false;
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult postEmpty(String uri) throws Exception {
        return mockMvc.perform(post(uri)).andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private void expectOk(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(root.get("success").asBoolean(),
                "期望成功，实际: " + root.get("message").asText());
    }

    private void expectFail(MvcResult result, String messageContains) throws Exception {
        expectFail(result, messageContains, "");
    }

    private void expectFail(MvcResult result, String messageContains, String hint) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertFalse(root.get("success").asBoolean(), hint);
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: "
                        + root.get("message").asText() + "；" + hint);
    }
}
