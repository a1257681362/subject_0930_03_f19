package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修复材料退料幂等（重复冲销治理）验收：
 * 1) 重复点击：8 并发同一退料单号同一内容，只生效一次——退料行 1 条、
 *    库存只加回一次、台账 MATERIAL_RETURN 只写一条，全部请求幂等回放同一结果；
 * 2) 异常重放：成功后再原样重放，命中同一条记录，原操作人与首次请求摘要保持不变；
 *    同单号不同内容（数量/操作者不符）明确报单号冲突，绝不静默生效第二次；
 * 3) 失败完整回滚：超额退料失败后，退料行、库存、台账都不留痕迹；
 * 4) 高风险并发：同一明细行 8 并发不同单号退料、合计超实际用量——
 *    只有不超限的先生效，其余被「累计退料不能超过」拦截，
 *    库存加回与台账流水与生效退料严格同步，对账始终一致。
 * 使用独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-return-idem;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MaterialReturnIdempotencyTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static final class Outcome {
        boolean success;
        String message;
        JsonNode data;
    }

    @Test
    void material_return_is_idempotent_under_retry_and_concurrency() throws Exception {
        // ---- 建档 + 入藏 + 立项 + 会签 + 开工 ----
        long relicId = createRelic("{"
                + "\"relicNo\":\"M-RET-001\",\"name\":\"退料幂等测试陶罐\",\"category\":\"陶器\","
                + "\"material\":\"陶\",\"era\":\"汉代\",\"source\":\"考古移交\","
                + "\"preservationGrade\":\"TWO\"}");
        postOk("/api/relics/" + relicId + "/intake", "{"
                + "\"intakeNo\":\"MI-RET-001\",\"location\":\"一号库·陶器区·A-01\","
                + "\"photos\":[{\"photoUrl\":\"photos/mret001/a.jpg\"}]}");
        long projectId = dataOf(postOk("/api/repair-projects", "{"
                + "\"projectNo\":\"MP-RET-001\",\"relicId\":" + relicId
                + ",\"client\":\"藏品部\",\"plan\":\"裂隙补配\"}")).get("id").asLong();
        countersign(projectId);
        postEmpty("/api/repair-projects/" + projectId + "/start");

        // ---- 材料 + 入库 + 领用（计划=实际=10，无差异复核噪音） ----
        long pigmentId = createMaterial("{"
                + "\"materialNo\":\"MM-RET-P1\",\"name\":\"矿物颜料朱砂\",\"category\":\"PIGMENT\",\"unit\":\"g\"}");
        inbound("MM-RET-P1", "B-RET", "100", "2027-12-31");
        long usageId = dataOf(postOk("/api/material-usages", issueJson("MU-RET-001", projectId,
                "[{\"batchNo\":\"B-RET\",\"planQty\":10,\"actualQty\":10,"
                        + "\"applyPart\":\"罐身补配\",\"operator\":\"修复师甲\"}]")))
                .get("id").asLong();
        JsonNode lines = dataOf(getJson("/api/material-usages/" + usageId)).get("lines");
        long lineId = lines.get(0).get("id").asLong();
        assertEquals(90.0, stockOf(pigmentId, "B-RET"), 1e-3);

        // ---- 场景 1：重复点击——8 并发同一退料单号同一内容，只生效一次 ----
        String dupBody = returnJson("MR-DUP-001", usageId, lineId, 4, "随色余量退回", "仓管员王五");
        List<Outcome> clicks = parallelPost(8, "/api/material-returns", i -> dupBody);
        Long effectiveId = null;
        for (Outcome o : clicks) {
            assertTrue(o.success, "同一单号同一内容的并发重试都应幂等成功，实际: " + o.message);
            if (effectiveId == null) {
                effectiveId = o.data.get("id").asLong();
            } else {
                assertEquals(effectiveId.longValue(), o.data.get("id").asLong(),
                        "重复点击必须回放同一条退料记录");
            }
        }
        assertEquals(1, returnsOf(usageId).size(), "8 次重复点击只能形成 1 条退料行");
        assertEquals(94.0, stockOf(pigmentId, "B-RET"), 1e-3, "库存只能加回一次 4g");
        assertEquals(1, ledgerCount(relicId, "MATERIAL_RETURN"), "库存流水只能写一条退料台账");

        // ---- 场景 2：异常重放——原样重放命中同一条，原操作人与请求摘要保留 ----
        JsonNode first = dataOf(postOk("/api/material-returns", dupBody));
        assertEquals(effectiveId.longValue(), first.get("id").asLong(), "事后重放不得新增退料行");
        assertEquals("仓管员王五", first.get("returnedBy").asText(), "原操作人必须保留");
        assertNotNull(first.get("requestDigest").asText(), "首次请求摘要必须落库");
        JsonNode replay = dataOf(postOk("/api/material-returns", dupBody));
        assertEquals(first.get("requestDigest").asText(), replay.get("requestDigest").asText(),
                "重放不得改写首次请求摘要");
        assertEquals(first.get("returnedAt").asText(), replay.get("returnedAt").asText(),
                "重放不得改写首次生效时间");
        assertEquals(1, returnsOf(usageId).size());
        assertEquals(94.0, stockOf(pigmentId, "B-RET"), 1e-3, "重放不得再次加回库存");
        assertEquals(1, ledgerCount(relicId, "MATERIAL_RETURN"), "重放不得再写台账");

        // 同单号不同内容：数量不符 → 单号冲突，不生效
        expectFail(postJson("/api/material-returns",
                returnJson("MR-DUP-001", usageId, lineId, 5, "随色余量退回", "仓管员王五")),
                "退料单号已存在且请求内容不一致");
        // 同单号换人提交 → 同样冲突，原操作人不被覆盖
        expectFail(postJson("/api/material-returns",
                returnJson("MR-DUP-001", usageId, lineId, 4, "随色余量退回", "仓管员李四")),
                "退料单号已存在且请求内容不一致");
        assertEquals(1, returnsOf(usageId).size());
        assertEquals(94.0, stockOf(pigmentId, "B-RET"), 1e-3);
        assertEquals("仓管员王五", returnsOf(usageId).get(0).get("returnedBy").asText(),
                "冲突重试不得覆盖原操作人");

        // ---- 场景 3：失败完整回滚——超额退料（已退 4，再退 7 超实际 10） ----
        expectFail(postJson("/api/material-returns",
                returnJson("MR-OVER-001", usageId, lineId, 7, "超额", "仓管员王五")),
                "累计退料不能超过");
        assertEquals(1, returnsOf(usageId).size(), "失败的退料不能留行");
        assertEquals(94.0, stockOf(pigmentId, "B-RET"), 1e-3, "失败必须完整回滚库存");
        assertEquals(1, ledgerCount(relicId, "MATERIAL_RETURN"), "失败不得写台账");

        // ---- 场景 4：高风险并发——8 张不同单号各退 4g，剩余可退仅 6g，只放行 1 张 ----
        List<Outcome> race = parallelPost(8, "/api/material-returns",
                i -> returnJson("MR-RACE-" + i, usageId, lineId, 4, "高风险并发退料", "仓管员王五"));
        int effective = 0;
        int rejected = 0;
        for (Outcome o : race) {
            if (o.success) {
                effective++;
            } else {
                rejected++;
                assertTrue(o.message.contains("累计退料不能超过"),
                        "落败退料应被累计上限拦截，实际: " + o.message);
            }
        }
        assertEquals(1, effective, "剩余可退 6g 时 8 张 4g 退料只能生效 1 张");
        assertEquals(7, rejected);
        assertEquals(2, returnsOf(usageId).size(), "退料行数必须与生效次数一致");
        assertEquals(98.0, stockOf(pigmentId, "B-RET"), 1e-3,
                "库存加回必须与生效退料同步，不得重复冲销");
        assertEquals(2, ledgerCount(relicId, "MATERIAL_RETURN"),
                "库存流水必须与生效退料同步，不得多写");

        // 落败单号重放：内容未被生效占用，重试仍被累计上限拦截，且不留痕迹
        expectFail(postJson("/api/material-returns",
                returnJson("MR-RACE-3", usageId, lineId, 4, "高风险并发退料", "仓管员王五")),
                "累计退料不能超过");
        assertEquals(2, returnsOf(usageId).size());
        assertEquals(98.0, stockOf(pigmentId, "B-RET"), 1e-3);

        // ---- 收尾：退齐最后 2g 整单 RETURNED；对账一致；再重放仍幂等 ----
        postOk("/api/material-returns", returnJson("MR-LAST-001", usageId, lineId, 2, "尾量退齐", "仓管员王五"));
        assertEquals("RETURNED", dataOf(getJson("/api/material-usages/" + usageId))
                .get("usage").get("status").asText());
        postOk("/api/material-returns", returnJson("MR-LAST-001", usageId, lineId, 2, "尾量退齐", "仓管员王五"));
        assertEquals(3, returnsOf(usageId).size(), "退齐后的重放不得新增退料行");
        assertEquals(3, ledgerCount(relicId, "MATERIAL_RETURN"));

        JsonNode reco = dataOf(getJson("/api/materials/batches/"
                + batchIdOf(pigmentId, "B-RET") + "/reconcile"));
        assertTrue(reco.get("consistent").asBoolean(), "全程库存与流水必须对平");
        assertEquals(100.0, reco.get("stockQty").asDouble(), 1e-3);
        assertEquals(10.0, reco.get("returnedTotal").asDouble(), 1e-3);
    }

    // ------------------------------------------------------------------

    private JsonNode returnsOf(long usageId) throws Exception {
        return dataOf(getJson("/api/material-usages/" + usageId)).get("returns");
    }

    private int ledgerCount(long relicId, String action) throws Exception {
        int count = 0;
        for (JsonNode e : dataOf(getJson("/api/relics/" + relicId + "/ledger"))) {
            if (action.equals(e.get("action").asText())) {
                count++;
            }
        }
        return count;
    }

    private String returnJson(String returnNo, long usageId, long lineId,
                              Object qty, String reason, String returnedBy) {
        return "{\"returnNo\":\"" + returnNo + "\",\"usageId\":" + usageId
                + ",\"lineId\":" + lineId + ",\"returnQty\":" + qty
                + ",\"reason\":\"" + reason + "\",\"returnedBy\":\"" + returnedBy + "\"}";
    }

    /** 通用并发 POST：所有线程同一 URI，各自生成 body，栅栏同时放行 */
    private List<Outcome> parallelPost(int threads, String uri,
                                       Function<Integer, String> bodyBuilder) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return toOutcome(mockMvc.perform(post(uri)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyBuilder.apply(idx)))
                        .andExpect(status().isOk()).andReturn());
            }));
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private Outcome toOutcome(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        Outcome o = new Outcome();
        o.success = root.get("success").asBoolean();
        o.message = root.get("message").asText();
        o.data = root.get("data");
        return o;
    }

    private long createRelic(String body) throws Exception {
        return dataOf(postJson("/api/relics", body)).get("id").asLong();
    }

    /** 二级文物会签顺序：馆内专家 -> 外部专家 -> 保管人员，全部同意方可开工 */
    private void countersign(long projectId) throws Exception {
        long versionId = dataOf(postOk("/api/repair-projects/" + projectId + "/plan-versions", "{"
                + "\"planContent\":\"裂隙补配\",\"internalExpert\":\"馆内专家甲\","
                + "\"externalExpert\":\"外部专家乙\",\"custodian\":\"保管员丙\","
                + "\"submittedBy\":\"项目负责人\"}")).get("version").get("id").asLong();
        postOk("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"INTERNAL\",\"signerName\":\"馆内专家甲\",\"decision\":\"APPROVE\"}");
        postOk("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"EXTERNAL\",\"signerName\":\"外部专家乙\",\"decision\":\"APPROVE\"}");
        postOk("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"CUSTODIAN\",\"signerName\":\"保管员丙\",\"decision\":\"APPROVE\"}");
    }

    private long createMaterial(String body) throws Exception {
        return dataOf(postJson("/api/materials", body)).get("id").asLong();
    }

    private void inbound(String materialNo, String batchNo, String qty, String expire) throws Exception {
        postOk("/api/materials/inbound", "{"
                + "\"materialNo\":\"" + materialNo + "\",\"batchNo\":\"" + batchNo + "\""
                + ",\"qty\":" + qty + ",\"expireDate\":\"" + expire + "\""
                + ",\"supplier\":\"修复材料中心\",\"inboundBy\":\"库管员\"}");
    }

    private String issueJson(String usageNo, long projectId, String linesJson) {
        return "{\"usageNo\":\"" + usageNo + "\",\"projectId\":" + projectId
                + ",\"lines\":" + linesJson + "}";
    }

    private double stockOf(long materialId, String batchNo) throws Exception {
        for (JsonNode b : dataOf(getJson("/api/materials/batches?materialId=" + materialId))) {
            if (batchNo.equals(b.get("batchNo").asText())) {
                return b.get("stockQty").asDouble();
            }
        }
        throw new IllegalStateException("批号未找到: " + batchNo);
    }

    private long batchIdOf(long materialId, String batchNo) throws Exception {
        for (JsonNode b : dataOf(getJson("/api/materials/batches?materialId=" + materialId))) {
            if (batchNo.equals(b.get("batchNo").asText())) {
                return b.get("id").asLong();
            }
        }
        throw new IllegalStateException("批号未找到: " + batchNo);
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult postOk(String uri, String body) throws Exception {
        MvcResult result = postJson(uri, body);
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(root.get("success").asBoolean(),
                "期望成功，实际: " + root.get("message").asText());
        return result;
    }

    private MvcResult postEmpty(String uri) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");
    }

    private void expectFail(MvcResult result, String messageContains) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(root.get("success").asBoolean());
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: " + root.get("message").asText());
    }
}
