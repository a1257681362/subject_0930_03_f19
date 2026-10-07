package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.entity.EnvReading;
import com.evops.entity.EnvRuleVersion;
import com.evops.entity.InspectionTask;
import com.evops.mapper.EnvReadingMapper;
import com.evops.service.EnvReadingTimeBackfill;
import com.evops.service.EnvRuleService;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * museum-17 升级回填回归：升级前只有挂钟 read_at 的历史读数，
 * 由 {@link EnvReadingTimeBackfill} 按展厅设备时区幂等补齐真实瞬时/偏移/归属日期；
 * 原始 read_at 不改写；跨零点与夏令时重叠的历史挂钟用 JVM ZoneRules 宽口径处理不报错。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-tz-backfill;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EnvReadingTimeBackfillTest {

    @TempDir
    static Path imageDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("museum.image.storage-root", () -> imageDir.toString());
        registry.add("museum.env.server-timezone", () -> "UTC");
        registry.add("museum.env.default-device-timezone", () -> "Asia/Shanghai");
        registry.add("museum.env.generate-on-startup", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EnvReadingMapper readingMapper;
    @Autowired
    private EnvReadingTimeBackfill backfill;
    @Autowired
    private EnvRuleService ruleService;

    @Test
    void legacy_wall_only_readings_backfilled_by_device_zone_idempotently() throws Exception {
        // 建档：Berlin 厅 + 纸本文物 + 规则 + 一轮任务（不提交读数）
        long relicId = dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"BF-P-1\",\"name\":\"回填纸本\",\"category\":\"文书\","
                + "\"material\":\"纸\",\"era\":\"现代\",\"source\":\"调拨\","
                + "\"preservationGrade\":\"TWO\"}")).get("id").asLong();
        long hallId = dataOf(postJson("/api/halls", "{"
                + "\"hallNo\":\"HBF\",\"name\":\"柏林回填厅\",\"custodian\":\"王保管\","
                + "\"timezone\":\"Europe/Berlin\"}")).get("id").asLong();
        ok(postJson("/api/halls/" + hallId + "/relics",
                "{\"relicId\":" + relicId + ",\"pointCode\":\"P-1\"}"));
        ok(postJson("/api/env-rules/publish", "{"
                + "\"versionNo\":\"BF-R1\",\"title\":\"回填规则\",\"publishedBy\":\"管理员\","
                + "\"items\":[{\"material\":\"纸\",\"tempMin\":18,\"tempMax\":22,"
                + "\"humidityMin\":50,\"humidityMax\":60,\"luxMin\":0,\"luxMax\":50,"
                + "\"sampleIntervalMin\":60}]}"));
        ok(postEmpty("/api/inspection/tasks/generate?force=true"));
        JsonNode taskNode = dataOf(getRaw(
                "/api/inspection/tasks?hallId=" + hallId + "&status=PENDING")).get(0);
        long taskId = taskNode.get("id").asLong();
        EnvRuleVersion version = ruleService.activeVersionEntity();

        // 直接插入 museum-17 升级前格式的历史行：只有挂钟，新列全空
        EnvReading legacy = new EnvReading();
        legacy.setTaskId(taskId);
        legacy.setHallId(hallId);
        legacy.setPointCode("P-1");
        legacy.setRuleVersionId(version.getId());
        legacy.setRuleVersionNo(version.getVersionNo());
        legacy.setTemperature(new BigDecimal("20"));
        legacy.setHumidity(new BigDecimal("55"));
        legacy.setIlluminance(new BigDecimal("30"));
        legacy.setMatchedMaterials("纸");
        legacy.setAbnormal(0);
        legacy.setInspector("老巡检员");
        // 跨零点挂钟：Berlin 1/16 00:30（UTC 1/15 23:30），旧实现会错误并入 15 日
        legacy.setReadAt(LocalDateTime.parse("2024-01-16T00:30:00"));
        assertNull(legacy.getReadAtEpochMilli());
        readingMapper.insert(legacy);
        long legacyId = legacy.getId();

        // 再插一行秋季回退重叠挂钟（02:30 两个瞬时）：回填宽口径不报错
        EnvReading fallLegacy = new EnvReading();
        fallLegacy.setTaskId(taskId);
        fallLegacy.setHallId(hallId);
        fallLegacy.setPointCode("P-2");
        fallLegacy.setRuleVersionId(version.getId());
        fallLegacy.setRuleVersionNo(version.getVersionNo());
        fallLegacy.setTemperature(new BigDecimal("20"));
        fallLegacy.setHumidity(new BigDecimal("55"));
        fallLegacy.setIlluminance(new BigDecimal("30"));
        fallLegacy.setMatchedMaterials("纸");
        fallLegacy.setAbnormal(0);
        fallLegacy.setInspector("老巡检员");
        fallLegacy.setReadAt(LocalDateTime.parse("2024-10-27T02:30:00"));
        readingMapper.insert(fallLegacy);

        // 执行回填（幂等，可重复执行）
        backfill.backfill();
        backfill.backfill();

        EnvReading filled = readingMapper.selectById(legacyId);
        // 原始挂钟事实未被改写
        assertEquals(LocalDateTime.parse("2024-01-16T00:30:00"), filled.getReadAt());
        // 按 Berlin UTC+1 换算：2024-01-15 23:30Z，归属设备日 1/16（不是 UTC 日 1/15）
        assertEquals(Instant.parse("2024-01-15T23:30:00Z").toEpochMilli(),
                filled.getReadAtEpochMilli());
        assertEquals("Europe/Berlin", filled.getReadZone());
        assertEquals(60, filled.getReadOffsetMin());
        assertEquals(java.time.LocalDate.parse("2024-01-16"), filled.getLocalDate());

        EnvReading fallFilled = readingMapper.selectById(fallLegacy.getId());
        assertEquals(java.time.LocalDate.parse("2024-10-27"), fallFilled.getLocalDate());
        assertTrue(fallFilled.getReadOffsetMin() == 60 || fallFilled.getReadOffsetMin() == 120,
                "重叠时刻宽口径回填取其一有效偏移");
        // 挂钟保持原值
        assertEquals(LocalDateTime.parse("2024-10-27T02:30:00"), fallFilled.getReadAt());
    }

    private void ok(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(root.get("success").asBoolean(),
                "期望成功，实际: " + root.get("message").asText());
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult postEmpty(String uri) throws Exception {
        return mockMvc.perform(post(uri)).andExpect(status().isOk()).andReturn();
    }

    private MvcResult getRaw(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }
}
