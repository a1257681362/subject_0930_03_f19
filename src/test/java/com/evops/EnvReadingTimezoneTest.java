package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 夜间巡检跨零点日期归属修复的端到端回归。
 *
 * <p>关键设定：<b>服务器时区 UTC</b>（museum.time.server-zone=UTC），
 * 默认展厅时区 Asia/Shanghai，另有 America/New_York 展厅——
 * 验证归属只按展厅时区，与服务器/JVM 时区无关；原始 read_at 原样落库。
 *
 * <p>覆盖：跨零点、DST 间隙/重叠、闰日、照度日报冻结/更正版本链/哈希完整性、
 * 升级前历史读数归属补录（不改 read_at）、旧快照被篡改可被哈希校验发现。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:museum-env-tz;DB_CLOSE_DELAY=-1",
        "museum.time.server-zone=UTC",
        "museum.time.default-hall-zone=Asia/Shanghai",
        "museum.env.backfill-on-startup=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EnvReadingTimezoneTest {

    @TempDir
    static Path imageDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("museum.image.storage-root", () -> imageDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long shHallId;
    private long nyHallId;
    private long ruleVersionId;

    @Test
    void midnight_dst_leap_and_correction_versions() throws Exception {
        prepareHallsAndRule();

        // ========== 0. 服务器时间核对端点：服务器 UTC，与 JVM 时区分别展示 ==========
        JsonNode timeInfo = dataOf(getJson("/api/health/time"));
        assertEquals("UTC", timeInfo.get("serverZone").asText());
        assertNotNull(timeInfo.get("serverNow").asText());
        Instant.parse(timeInfo.get("utcInstant").asText()); // 可解析为绝对时刻

        // ========== 1. 三轮任务：上海/纽约各 3 个点位读数 ==========
        forceGenerate(); // R1
        long sh1 = taskOf(shHallId, 1);
        long ny1 = taskOf(nyHallId, 1);

        // —— 跨零点（上海厅，无独立时区 → 回落默认 Asia/Shanghai）——
        // 23:30 上海 = 15:30Z 同日；归属 03-10
        submit(sh1, "2024-03-10T23:30:00");
        JsonNode r1 = onlyReading(sh1);
        assertEquals("2024-03-10T23:30:00", r1.get("readAt").asText(), "原始采样时间原样落库");
        assertEquals("2024-03-10", r1.get("readingDate").asText());
        assertEquals("Asia/Shanghai", r1.get("readAtZone").asText());
        assertEquals(Instant.parse("2024-03-10T15:30:00Z"),
                instantOf(r1.get("readAtInstant").asText()));

        // —— 纽约 DST spring-forward 间隙：02:30 不存在 → 前推 03:30 EDT = 07:30Z ——
        submit(ny1, "2024-03-10T02:30:00");
        JsonNode n1 = onlyReading(ny1);
        assertEquals("2024-03-10T02:30:00", n1.get("readAt").asText(), "间隙读数原始墙钟不改写");
        assertEquals("2024-03-10", n1.get("readingDate").asText());
        assertEquals("America/New_York", n1.get("readAtZone").asText());
        assertEquals(Instant.parse("2024-03-10T07:30:00Z"),
                instantOf(n1.get("readAtInstant").asText()), "间隙按过渡后偏移前推");

        forceGenerate(); // R2
        long sh2 = taskOf(shHallId, 2);
        long ny2 = taskOf(nyHallId, 2);

        // —— 事故场景重现：上海 00:30 = UTC 前一天 16:30 ——
        // 裸服务器日期会归到 03-10（错误）；展厅时区归属必须是 03-11
        submit(sh2, "2024-03-11T00:30:00");
        JsonNode r2 = onlyReading(sh2);
        assertEquals("2024-03-11", r2.get("readingDate").asText(),
                "跨零点读数按展厅时区归次日，不得按 UTC 服务器日期归到前一天");
        assertEquals(Instant.parse("2024-03-10T16:30:00Z"),
                instantOf(r2.get("readAtInstant").asText()));

        // —— 纽约 DST fall-back 重叠：01:30 两次 → 取较早 EDT(-4)=05:30Z ——
        submit(ny2, "2024-11-03T01:30:00");
        JsonNode n2 = onlyReading(ny2);
        assertEquals("2024-11-03", n2.get("readingDate").asText());
        assertEquals(Instant.parse("2024-11-03T05:30:00Z"),
                instantOf(n2.get("readAtInstant").asText()), "重叠取较早偏移，不是 06:30Z");

        forceGenerate(); // R3
        long sh3 = taskOf(shHallId, 3);
        long ny3 = taskOf(nyHallId, 3);

        // —— 闰日 2024-02-29 ——
        submit(sh3, "2024-02-29T23:30:00");
        JsonNode r3 = onlyReading(sh3);
        assertEquals("2024-02-29", r3.get("readingDate").asText(), "闰日 23:30 仍归 2/29");
        assertEquals(Instant.parse("2024-02-29T15:30:00Z"),
                instantOf(r3.get("readAtInstant").asText()));

        // —— 纽约跨零点：本地 11-02 23:30 EDT = UTC 11-03 03:30，仍归当地 11-02 ——
        submit(ny3, "2024-11-02T23:30:00");
        JsonNode n3 = onlyReading(ny3);
        assertEquals("2024-11-02", n3.get("readingDate").asText(),
                "UTC 已到次日，展厅当地未跨零点不得归次日");

        // ========== 2. 照度日报：冻结 V1 ==========
        JsonNode v1 = dataOf(postEmpty("/api/env-daily/freeze?hallId=" + shHallId
                + "&date=2024-03-10&frozenBy=" + url("报表员")));
        long v1Id = v1.get("id").asLong();
        assertEquals(1, v1.get("reportVersion").asInt());
        assertEquals("FREEZE", v1.get("source").asText());
        assertEquals(1, v1.get("readingCount").asInt());
        assertEquals(1, v1.get("pointCount").asInt());
        assertEquals(30.0, v1.get("luxMin").asDouble(), 1e-9);
        assertEquals(30.0, v1.get("luxMax").asDouble(), 1e-9);
        assertEquals("Asia/Shanghai", v1.get("hallZone").asText());
        assertTrue(verifyHash(v1Id), "V1 内容哈希自洽");

        // 重复冻结拒绝；事实无变化的"更正"拒绝（展示层不得改事实）
        expectFail(postEmpty("/api/env-daily/freeze?hallId=" + shHallId
                + "&date=2024-03-10&frozenBy=x"), "不能重复冻结");
        expectFail(postJson("/api/env-daily/correct", "{"
                + "\"hallId\":" + shHallId + ",\"date\":\"2024-03-10\","
                + "\"reason\":\"想改个格式\",\"correctedBy\":\"展示层\"}"), "无变化");

        // ========== 3. 升级前历史读数（reading_date 为空）补归属，再出更正版本 ==========
        // 模拟升级库：reading_date/read_at_zone 由 ALTER 补列而来（可空）
        jdbcTemplate.execute("ALTER TABLE t_env_reading ALTER COLUMN reading_date DROP NOT NULL");
        jdbcTemplate.execute("ALTER TABLE t_env_reading ALTER COLUMN read_at_zone DROP NOT NULL");
        jdbcTemplate.update("INSERT INTO t_env_reading (task_id, hall_id, point_code, "
                + "rule_version_id, rule_version_no, temperature, humidity, illuminance, "
                + "matched_materials, abnormal, read_at, inspector) "
                + "VALUES (900001, ?, 'P-OLD', ?, 'TZ-R1', 20, 55, 42, '纸', 0, "
                + "TIMESTAMP '2024-03-10 12:00:00', '老巡检员')", shHallId, ruleVersionId);

        JsonNode backfill = dataOf(postEmpty("/api/env-daily/backfill-reading-dates"));
        assertEquals(1, backfill.get("backfilled").asInt());
        // 再次补录幂等
        assertEquals(0, dataOf(postEmpty("/api/env-daily/backfill-reading-dates"))
                .get("backfilled").asInt());
        // 原始采样时间未被改写，归属列按展厅时区补齐
        MapRow row = queryLegacy();
        assertEquals("2024-03-10 12:00:00", row.readAt, "原始采样时间不得改写");
        assertEquals("2024-03-10", row.readingDate, "补录按展厅时区归日");
        assertEquals("Asia/Shanghai", row.zone);

        // 更正版本 V2：读数 2 条；V1 原样保留
        JsonNode v2 = dataOf(postJson("/api/env-daily/correct", "{"
                + "\"hallId\":" + shHallId + ",\"date\":\"2024-03-10\","
                + "\"reason\":\"补录升级前漏归属的午间照度读数后重算\",\"correctedBy\":\"报表主管\"}"));
        long v2Id = v2.get("id").asLong();
        assertNotEquals(v1Id, v2Id);
        assertEquals(2, v2.get("reportVersion").asInt());
        assertEquals("CORRECTION", v2.get("source").asText());
        assertEquals(2, v2.get("readingCount").asInt());
        assertEquals(2, v2.get("pointCount").asInt());
        assertEquals(42.0, v2.get("luxMax").asDouble(), 1e-9);
        assertEquals(v1Id, v2.get("baseVersionId").asLong(), "更正链指向旧版本");
        assertEquals(v1.get("contentHash").asText(), v2.get("baseVersionHash").asText());
        assertEquals("补录升级前漏归属的午间照度读数后重算", v2.get("correctionReason").asText());

        // 旧快照 V1 一个字段都没变
        JsonNode v1Again = dataOf(getJson("/api/env-daily/versions/1?hallId="
                + shHallId + "&date=2024-03-10"));
        assertEquals(1, v1Again.get("readingCount").asInt(), "历史快照不被更正覆盖");
        assertEquals("FREEZE", v1Again.get("source").asText());
        assertTrue(verifyHash(v1Id));
        assertTrue(verifyHash(v2Id));

        // 版本链与"最新即生效"
        JsonNode chain = dataOf(getJson("/api/env-daily/versions?hallId=" + shHallId
                + "&date=2024-03-10"));
        assertEquals(2, chain.size());
        assertEquals(1, chain.get(0).get("reportVersion").asInt());
        assertEquals(2, chain.get(1).get("reportVersion").asInt());
        JsonNode latest = dataOf(getJson("/api/env-daily/latest?hallId=" + shHallId
                + "&date=2024-03-10"));
        assertEquals(v2Id, latest.get("id").asLong());

        // 原事故回归：00:30 那条单独归 03-11，不再与 03-10 23:30 合并成同一天
        JsonNode day11 = dataOf(postEmpty("/api/env-daily/freeze?hallId=" + shHallId
                + "&date=2024-03-11&frozenBy=" + url("报表员")));
        assertEquals(1, day11.get("readingCount").asInt(), "00:30 读数归 03-11，不并入 03-10");
        assertTrue(day11.get("snapshotJson").asText().contains("2024-03-11T00:30:00"));
        assertFalse(day11.get("snapshotJson").asText().contains("2024-03-10T23:30:00"));
        assertTrue(verifyHash(day11.get("id").asLong()));

        JsonNode list = dataOf(getJson("/api/env-daily?hallId=" + shHallId));
        assertTrue(list.size() >= 1, "展厅日报列表可查");

        // 台账留痕：冻结、补录、更正三动作
        JsonNode ledger = dataOf(getJson("/api/env-ledgers?hallId=" + shHallId));
        assertTrue(hasAction(ledger, "DAILY_FREEZE"));
        assertTrue(hasAction(ledger, "DAILY_CORRECT"));
        assertTrue(hasAction(ledger, "READING_DATE_BACKFILL"));

        // ========== 4. 旧快照不可变：直接改库篡改统计列，哈希校验必须发现 ==========
        jdbcTemplate.update("UPDATE t_env_daily_report SET lux_max=999 WHERE id=?", v1Id);
        assertFalse(verifyHash(v1Id), "统计列被篡改，哈希完整性校验必须失败");
        // V2 不受影响
        assertTrue(verifyHash(v2Id));
        // 快照明细 JSON 被改同样暴露
        jdbcTemplate.update("UPDATE t_env_daily_report SET snapshot_json="
                + "REPLACE(snapshot_json,'\"illuminance\":42','\"illuminance\":43') WHERE id=?", v2Id);
        assertFalse(verifyHash(v2Id), "读数事实被篡改，哈希校验必须失败");

        // ========== 5. 非法时区建档拒绝 ==========
        expectFail(postJson("/api/halls", "{"
                + "\"hallNo\":\"HBAD\",\"name\":\"错时区厅\",\"custodian\":\"王保管\","
                + "\"timeZone\":\"Mars/Olympus\"}"), "时区");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void prepareHallsAndRule() throws Exception {
        long paperRelicId = createRelic("TZ-P-1", "时区测试纸本", "纸");
        long paperRelicId2 = createRelic("TZ-P-2", "时区测试纸本二", "纸");

        shHallId = dataOf(postJson("/api/halls", "{"
                + "\"hallNo\":\"HTZSH\",\"name\":\"上海厅\",\"custodian\":\"王保管\"}"))
                .get("id").asLong();
        nyHallId = dataOf(postJson("/api/halls", "{"
                + "\"hallNo\":\"HTZNY\",\"name\":\"纽约厅\",\"custodian\":\"John\","
                + "\"timeZone\":\"America/New_York\"}")).get("id").asLong();

        expectOk(postJson("/api/halls/" + shHallId + "/relics",
                "{\"relicId\":" + paperRelicId + ",\"pointCode\":\"P-A\"}"));
        expectOk(postJson("/api/halls/" + nyHallId + "/relics",
                "{\"relicId\":" + paperRelicId2 + ",\"pointCode\":\"Q-A\"}"));

        expectOk(postJson("/api/env-rules/publish", "{"
                + "\"versionNo\":\"TZ-R1\",\"title\":\"时区规则\",\"publishedBy\":\"环境管理员\","
                + "\"items\":[{\"material\":\"纸\",\"tempMin\":18,\"tempMax\":22,"
                + "\"humidityMin\":50,\"humidityMax\":60,\"luxMin\":0,\"luxMax\":50,"
                + "\"sampleIntervalMin\":60}]}"));
        ruleVersionId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_env_rule_version WHERE version_no='TZ-R1'", Long.class);
    }

    private void forceGenerate() throws Exception {
        JsonNode arr = dataOf(postEmpty("/api/inspection/tasks/generate?force=true"));
        assertTrue(arr.size() >= 2, "两个展厅各应生成一轮");
    }

    private long taskOf(long hallId, int roundNo) throws Exception {
        JsonNode arr = dataOf(getJson("/api/inspection/tasks?hallId=" + hallId));
        for (JsonNode t : arr) {
            if (t.get("roundNo").asInt() == roundNo) {
                return t.get("id").asLong();
            }
        }
        throw new AssertionError("未找到展厅 " + hallId + " 第 " + roundNo + " 轮任务");
    }

    private void submit(long taskId, String readAt) throws Exception {
        long hallId = dataOf(getJson("/api/inspection/tasks/" + taskId + "/report"))
                .get("task").get("hallId").asLong();
        String point = hallId == nyHallId ? "Q-A" : "P-A";
        expectOk(postJson("/api/inspection/tasks/" + taskId + "/readings", "{"
                + "\"inspector\":\"巡检员赵\",\"readAt\":\"" + readAt + "\","
                + "\"readings\":[{\"pointCode\":\"" + point
                + "\",\"temperature\":20,\"humidity\":55,\"illuminance\":30}]}"));
    }

    private JsonNode onlyReading(long taskId) throws Exception {
        JsonNode readings = dataOf(getJson("/api/inspection/tasks/" + taskId + "/report"))
                .get("readings");
        assertEquals(1, readings.size());
        return readings.get(0);
    }

    private boolean verifyHash(long reportId) throws Exception {
        return dataOf(getJson("/api/env-daily/" + reportId + "/verify"))
                .get("hashValid").asBoolean();
    }

    private boolean hasAction(JsonNode ledger, String action) {
        for (JsonNode e : ledger) {
            if (action.equals(e.get("action").asText())) {
                return true;
            }
        }
        return false;
    }

    private MapRow queryLegacy() {
        return jdbcTemplate.queryForObject("SELECT FORMATDATETIME(read_at,'yyyy-MM-dd HH:mm:ss') AS read_at, "
                + "CAST(reading_date AS VARCHAR) AS reading_date, read_at_zone AS zone "
                + "FROM t_env_reading WHERE task_id=900001",
                (rs, i) -> new MapRow(rs.getString("read_at"),
                        rs.getString("reading_date"), rs.getString("zone")));
    }

    private static class MapRow {
        final String readAt;
        final String readingDate;
        final String zone;

        MapRow(String readAt, String readingDate, String zone) {
            this.readAt = readAt;
            this.readingDate = readingDate;
            this.zone = zone;
        }
    }

    private long createRelic(String no, String name, String material) throws Exception {
        return dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"" + no + "\",\"name\":\"" + name + "\",\"category\":\"书画\","
                + "\"material\":\"" + material + "\",\"era\":\"宋代\",\"source\":\"考古移交\","
                + "\"preservationGrade\":\"TWO\"}")).get("id").asLong();
    }

    private static Instant instantOf(String iso) {
        return OffsetDateTime.parse(iso).toInstant();
    }

    private static String url(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertFalse(root.get("success").asBoolean());
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: " + root.get("message").asText());
    }
}
