package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.DailyReportCorrectRequest;
import com.evops.entity.EnvDailyReport;
import com.evops.entity.EnvReading;
import com.evops.entity.Hall;
import com.evops.mapper.EnvDailyReportMapper;
import com.evops.mapper.EnvReadingMapper;
import com.evops.config.MuseumEnvProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.evops.constant.MuseumConstants.*;

/**
 * 照度日报版本快照服务。
 *
 * <ol>
 *   <li><b>分组键</b>：日报按 {@code (hallId, reading.readingDate)} 取数，
 *       readingDate 是提交时按<b>展厅时区</b>对原始采样墙钟时间归日落库的归属日，
 *       不是服务器日期，也不是 readAt 的裸日期截断；</li>
 *   <li><b>首版冻结</b>：FREEZE V1 落一条不可变快照（统计 + 逐读数 JSON + 内容哈希）；</li>
 *   <li><b>更正出版本</b>：发现历史归属/统计错误时，基于读数事实重新计算出
 *       CORRECTION 版本（版本号递增、base_version_id/hash 串链），旧快照不更新不删除；
 *       若事实未变化（哈希相同）则拒绝——展示层改动无法产生"更正"，事实不变报表不得变；</li>
 *   <li><b>历史归属补录</b>：升级前 reading_date 为空的旧读数，按展厅时区补归属
 *       （read_at 原样不动），台账逐展厅留痕，之后即可冻结正确日报。</li>
 * </ol>
 */
@Service
public class EnvDailyReportService {

    private static final Logger log = LoggerFactory.getLogger(EnvDailyReportService.class);

    public static final String SOURCE_FREEZE = "FREEZE";
    public static final String SOURCE_CORRECTION = "CORRECTION";

    private final EnvDailyReportMapper reportMapper;
    private final EnvReadingMapper readingMapper;
    private final HallService hallService;
    private final EnvLedgerService ledgerService;
    private final MuseumTimeProvider timeProvider;
    private final MuseumEnvProperties envProperties;
    private final ObjectMapper objectMapper;

    public EnvDailyReportService(EnvDailyReportMapper reportMapper,
                                 EnvReadingMapper readingMapper,
                                 HallService hallService,
                                 EnvLedgerService ledgerService,
                                 MuseumTimeProvider timeProvider,
                                 MuseumEnvProperties envProperties,
                                 ObjectMapper objectMapper) {
        this.reportMapper = reportMapper;
        this.readingMapper = readingMapper;
        this.hallService = hallService;
        this.ledgerService = ledgerService;
        this.timeProvider = timeProvider;
        this.envProperties = envProperties;
        this.objectMapper = objectMapper;
    }

    /** 启动就绪后为升级前的历史读数补归属（幂等；只补归属列，不改 read_at）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        if (!envProperties.isBackfillOnStartup()) {
            return;
        }
        try {
            int n = backfillLegacyReadingDates();
            if (n > 0) {
                log.info("启动补录历史读数时区归属 {} 条", n);
            }
        } catch (Exception e) {
            log.warn("启动补录历史读数归属失败（可手动调补录接口重试）: {}", e.getMessage());
        }
    }

    // ================================================================
    // 冻结 / 更正
    // ================================================================

    /** 首版冻结：同一展厅同一归属日已有任一版本时拒绝（须走更正版本）。 */
    @Transactional
    public EnvDailyReport freeze(Long hallId, LocalDate date, String frozenBy) {
        Hall hall = hallService.requireHall(hallId);
        EnvDailyReport existing = latestVersion(hallId, date);
        if (existing != null) {
            throw new IllegalArgumentException("展厅 " + hall.getHallNo() + " " + date
                    + " 照度日报已冻结 V" + existing.getReportVersion()
                    + "，不能重复冻结；如需修复请出更正版本");
        }
        ZoneId zone = timeProvider.hallZone(hall.getTimeZone());
        List<EnvReading> readings = listFactReadings(hallId, date);
        if (readings.isEmpty()) {
            throw new IllegalArgumentException("展厅 " + hall.getHallNo() + " " + date
                    + " 无归属读数，不能冻结照度日报");
        }
        EnvDailyReport report = buildSnapshot(hall, date, zone, readings, 1, SOURCE_FREEZE,
                null, null, null, frozenBy);
        reportMapper.insert(report);
        ledgerService.append(hallId, null, null, null, null,
                ACTION_ENV_DAILY_FREEZE, report.getReportNo(),
                "照度日报首版冻结：" + date + "（展厅时区 " + zone.getId() + "），读数 "
                        + report.getReadingCount() + " 条、点位 " + report.getPointCount()
                        + " 个、照度区间 [" + report.getLuxMin() + ", " + report.getLuxMax()
                        + "]、越限 " + report.getAbnormalCount() + " 条；快照只追加不可变",
                report.getFrozenAt());
        return report;
    }

    /**
     * 更正版本：基于读数事实重新计算，旧快照原样保留。
     * 事实无变化（内容哈希相同）时拒绝——没有事实修正就不得出新版本。
     */
    @Transactional
    public EnvDailyReport correct(DailyReportCorrectRequest req) {
        Hall hall = hallService.requireHall(req.getHallId());
        EnvDailyReport base = latestVersion(req.getHallId(), req.getDate());
        if (base == null) {
            throw new IllegalArgumentException("展厅 " + hall.getHallNo() + " " + req.getDate()
                    + " 尚无冻结日报，不能更正；请先冻结首版");
        }
        ZoneId zone = timeProvider.hallZone(hall.getTimeZone());
        List<EnvReading> readings = listFactReadings(req.getHallId(), req.getDate());
        if (readings.isEmpty()) {
            throw new IllegalArgumentException("展厅 " + hall.getHallNo() + " " + req.getDate()
                    + " 已无归属读数，事实缺失不能出更正版本");
        }
        int nextVersion = base.getReportVersion() + 1;
        EnvDailyReport report = buildSnapshot(hall, req.getDate(), zone, readings, nextVersion,
                SOURCE_CORRECTION, base.getId(), base.getContentHash(),
                req.getReason().trim(), req.getCorrectedBy().trim());
        if (report.getContentHash().equals(base.getContentHash())) {
            throw new IllegalArgumentException("读数事实与统计结果相对 V" + base.getReportVersion()
                    + " 无变化（内容哈希一致），无需更正——展示层不得改事实");
        }
        reportMapper.insert(report);
        ledgerService.append(hall.getId(), null, null, null, null,
                ACTION_ENV_DAILY_CORRECT, report.getReportNo(),
                "照度日报更正版本 V" + nextVersion + "：" + req.getDate()
                        + "，基于 V" + base.getReportVersion() + "（" + base.getReportNo()
                        + "）只追加；旧快照保留不覆盖。原因：" + req.getReason().trim()
                        + "；重算读数 " + report.getReadingCount() + " 条、照度区间 ["
                        + report.getLuxMin() + ", " + report.getLuxMax() + "]、越限 "
                        + report.getAbnormalCount() + " 条", report.getFrozenAt());
        return report;
    }

    // ================================================================
    // 查询
    // ================================================================

    public EnvDailyReport requireReport(Long reportId) {
        EnvDailyReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new IllegalArgumentException("照度日报不存在: id=" + reportId);
        }
        return report;
    }

    /** 最新版本（当前生效口径）。 */
    public EnvDailyReport latestVersion(Long hallId, LocalDate date) {
        return reportMapper.selectOne(new LambdaQueryWrapper<EnvDailyReport>()
                .eq(EnvDailyReport::getHallId, hallId)
                .eq(EnvDailyReport::getReadingDate, date)
                .orderByDesc(EnvDailyReport::getReportVersion)
                .last("LIMIT 1"));
    }

    /** 指定版本（历史快照原样可查）。 */
    public EnvDailyReport getVersion(Long hallId, LocalDate date, int version) {
        return reportMapper.selectOne(new LambdaQueryWrapper<EnvDailyReport>()
                .eq(EnvDailyReport::getHallId, hallId)
                .eq(EnvDailyReport::getReadingDate, date)
                .eq(EnvDailyReport::getReportVersion, version)
                .last("LIMIT 1"));
    }

    /** 更正版本链：首版 -> 各更正版本，按版本号升序，旧快照全部保留可追溯。 */
    public List<EnvDailyReport> versionChain(Long hallId, LocalDate date) {
        return reportMapper.selectList(new LambdaQueryWrapper<EnvDailyReport>()
                .eq(EnvDailyReport::getHallId, hallId)
                .eq(EnvDailyReport::getReadingDate, date)
                .orderByAsc(EnvDailyReport::getReportVersion));
    }

    /** 展厅日报列表：每个归属日只取最新版本号（旧版本通过版本链查看）。 */
    public List<EnvDailyReport> listLatest(Long hallId) {
        hallService.requireHall(hallId);
        List<EnvDailyReport> all = reportMapper.selectList(new LambdaQueryWrapper<EnvDailyReport>()
                .eq(EnvDailyReport::getHallId, hallId)
                .orderByDesc(EnvDailyReport::getReadingDate)
                .orderByDesc(EnvDailyReport::getReportVersion));
        Map<LocalDate, EnvDailyReport> latestByDate = new LinkedHashMap<>();
        for (EnvDailyReport r : all) {
            latestByDate.putIfAbsent(r.getReadingDate(), r);
        }
        return new ArrayList<>(latestByDate.values());
    }

    /**
     * 校验快照内容哈希：按快照内冻结的读数事实与统计结果重算 SHA-256，
     * 并逐列比对实体统计列与快照内 stats。任何对旧快照明细 JSON 或统计列的
     * 事后篡改都会暴露（快照不可变，修复只能出更正版本）。
     */
    @SuppressWarnings("unchecked")
    public boolean verifyHash(Long reportId) {
        EnvDailyReport report = requireReport(reportId);
        try {
            // JSON 浮点强制读为 BigDecimal，保留词法标度（20.00 不被读成 Double 20.0）
            ObjectMapper verifying = objectMapper.copy()
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            Map<String, Object> snapshot = verifying.readValue(report.getSnapshotJson(), Map.class);
            if (!hashCanonicalMatches(report, canonicalFromSnapshot(snapshot))) {
                return false;
            }
            // 统计列与快照内 stats 必须一致：防止只改表列、不动 JSON 的篡改
            Map<String, Object> stats = (Map<String, Object>) snapshot.get("stats");
            return eq(report.getPointCount(), stats.get("pointCount"))
                    && eq(report.getReadingCount(), stats.get("readingCount"))
                    && isBdEq(report.getLuxMin(), stats.get("luxMin"))
                    && isBdEq(report.getLuxMax(), stats.get("luxMax"))
                    && isBdEq(report.getLuxAvg(), stats.get("luxAvg"))
                    && eq(report.getAbnormalCount(), stats.get("abnormalCount"));
        } catch (Exception e) {
            throw new IllegalStateException("日报快照内容无法解析: " + report.getReportNo(), e);
        }
    }

    private boolean hashCanonicalMatches(EnvDailyReport report, String canonical) {
        return sha256(canonical).equals(report.getContentHash());
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.toString().equals(b == null ? null : b.toString());
    }

    private static boolean isBdEq(BigDecimal a, Object b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.compareTo(new BigDecimal(b.toString())) == 0;
    }

    // ================================================================
    // 历史读数归属补录（升级前 reading_date 为空的旧读数）
    // ================================================================

    /**
     * 把升级前缺归属日的历史读数按展厅时区补归属。
     * <b>只补 reading_date/read_at_zone/read_at_instant，原始 read_at 不动</b>；
     * 按展厅幂等（无空值即跳过），每个展厅写一条台账留痕。
     *
     * @return 实际补录条数
     */
    @Transactional
    public int backfillLegacyReadingDates() {
        List<EnvReading> missing = readingMapper.selectList(new LambdaQueryWrapper<EnvReading>()
                .isNull(EnvReading::getReadingDate)
                .orderByAsc(EnvReading::getHallId)
                .orderByAsc(EnvReading::getId));
        int total = 0;
        Map<Long, Integer> perHall = new TreeMap<>();
        for (EnvReading r : missing) {
            Hall hall = hallService.requireHall(r.getHallId());
            ZoneId zone = timeProvider.hallZone(hall.getTimeZone());
            r.setReadingDate(timeProvider.readingDate(r.getReadAt(), zone));
            r.setReadAtZone(zone.getId());
            r.setReadAtInstant(timeProvider.toInstant(r.getReadAt(), zone)
                    .atOffset(java.time.ZoneOffset.UTC));
            readingMapper.updateById(r);
            total++;
            perHall.merge(hall.getId(), 1, Integer::sum);
        }
        for (Map.Entry<Long, Integer> e : perHall.entrySet()) {
            ledgerService.append(e.getKey(), null, null, null, null,
                    ACTION_ENV_READING_DATE_BACKFILL, "BACKFILL-H" + e.getKey(),
                    "历史读数按展厅时区补归属日共 " + e.getValue()
                            + " 条；原始采样时间 read_at 原样未改写，仅补日报分组键",
                    timeProvider.now());
        }
        if (total > 0) {
            log.info("历史环境读数归属补录完成：{} 条，涉及展厅 {}", total, perHall.keySet());
        }
        return total;
    }

    // ================================================================
    // 内部
    // ================================================================

    private List<EnvReading> listFactReadings(Long hallId, LocalDate date) {
        return readingMapper.selectList(new LambdaQueryWrapper<EnvReading>()
                .eq(EnvReading::getHallId, hallId)
                .eq(EnvReading::getReadingDate, date)
                .orderByAsc(EnvReading::getPointCode)
                .orderByAsc(EnvReading::getReadAt)
                .orderByAsc(EnvReading::getId));
    }

    private EnvDailyReport buildSnapshot(Hall hall, LocalDate date, ZoneId zone,
                                         List<EnvReading> readings, int version, String source,
                                         Long baseId, String baseHash,
                                         String reason, String operator) {
        // 逐点位统计
        Map<String, List<EnvReading>> byPoint = new TreeMap<>();
        for (EnvReading r : readings) {
            byPoint.computeIfAbsent(r.getPointCode(), k -> new ArrayList<>()).add(r);
        }
        List<Map<String, Object>> pointStats = new ArrayList<>();
        BigDecimal min = null;
        BigDecimal max = null;
        BigDecimal sum = BigDecimal.ZERO;
        int abnormal = 0;
        for (Map.Entry<String, List<EnvReading>> e : byPoint.entrySet()) {
            BigDecimal pMin = null, pMax = null, pSum = BigDecimal.ZERO;
            int pAbnormal = 0;
            for (EnvReading r : e.getValue()) {
                BigDecimal lux = r.getIlluminance();
                pMin = pMin == null ? lux : pMin.min(lux);
                pMax = pMax == null ? lux : pMax.max(lux);
                pSum = pSum.add(lux);
                if (r.getAbnormal() != null && r.getAbnormal() == 1) {
                    pAbnormal++;
                    abnormal++;
                }
            }
            BigDecimal pAvg = pSum.divide(BigDecimal.valueOf(e.getValue().size()), 2, RoundingMode.HALF_UP);
            min = min == null ? pMin : min.min(pMin);
            max = max == null ? pMax : max.max(pMax);
            sum = sum.add(pSum);
            Map<String, Object> ps = new LinkedHashMap<>();
            ps.put("pointCode", e.getKey());
            ps.put("readingCount", e.getValue().size());
            ps.put("luxMin", pMin);
            ps.put("luxMax", pMax);
            ps.put("luxAvg", pAvg);
            ps.put("abnormalCount", pAbnormal);
            pointStats.add(ps);
        }
        BigDecimal avg = sum.divide(BigDecimal.valueOf(readings.size()), 2, RoundingMode.HALF_UP);

        // 逐读数事实快照（只取不可变事实列；展示层呈现的就是落库事实）
        List<Map<String, Object>> readingFacts = new ArrayList<>();
        for (EnvReading r : readings) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("readingId", r.getId());
            f.put("pointCode", r.getPointCode());
            f.put("readAt", r.getReadAt());
            f.put("readingDate", r.getReadingDate());
            f.put("readAtZone", r.getReadAtZone());
            f.put("temperature", r.getTemperature());
            f.put("humidity", r.getHumidity());
            f.put("illuminance", r.getIlluminance());
            f.put("abnormal", r.getAbnormal());
            f.put("abnormalMetrics", r.getAbnormalMetrics());
            f.put("ruleVersionNo", r.getRuleVersionNo());
            readingFacts.add(f);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pointCount", byPoint.size());
        stats.put("readingCount", readings.size());
        stats.put("luxMin", min);
        stats.put("luxMax", max);
        stats.put("luxAvg", avg);
        stats.put("abnormalCount", abnormal);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("hallId", hall.getId());
        snapshot.put("readingDate", date);
        snapshot.put("hallZone", zone.getId());
        snapshot.put("stats", stats);
        snapshot.put("points", pointStats);
        snapshot.put("readings", readingFacts);

        String json;
        try {
            json = objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("照度日报快照序列化失败", e);
        }

        EnvDailyReport report = new EnvDailyReport();
        report.setReportNo("ED-" + String.format("%06d", reportMapper.nextReportNo()));
        report.setHallId(hall.getId());
        report.setReadingDate(date);
        report.setHallZone(zone.getId());
        report.setReportVersion(version);
        report.setSource(source);
        report.setPointCount(byPoint.size());
        report.setReadingCount(readings.size());
        report.setLuxMin(min);
        report.setLuxMax(max);
        report.setLuxAvg(avg);
        report.setAbnormalCount(abnormal);
        report.setSnapshotJson(json);
        report.setContentHash(sha256(canonical(hall.getId(), date, zone.getId(), readings, stats)));
        report.setBaseVersionId(baseId);
        report.setBaseVersionHash(baseHash);
        report.setCorrectionReason(reason);
        report.setCorrectedBy(SOURCE_CORRECTION.equals(source) ? operator : null);
        report.setFrozenAt(timeProvider.now());
        report.setFrozenBy(operator);
        return report;
    }

    /**
     * 规范化哈希输入：固定列序与分隔符，避免 JSON 键序/格式化差异。
     * 事实部分只含读数落库原值（readAt 原样墙钟、readingDate 归属日、照度等）。
     * 时间一律 ISO_LOCAL_DATE_TIME 全秒格式，与快照 JSON 中 Jackson 序列化口径一致。
     */
    private String canonical(Long hallId, LocalDate date, String zone,
                             List<EnvReading> readings, Map<String, Object> stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("H|").append(hallId).append('\n')
          .append("D|").append(date).append('\n')
          .append("Z|").append(zone).append('\n');
        sb.append("S|").append(stats.get("pointCount"))
          .append('|').append(stats.get("readingCount"))
          .append('|').append(bd(stats.get("luxMin")))
          .append('|').append(bd(stats.get("luxMax")))
          .append('|').append(bd(stats.get("luxAvg")))
          .append('|').append(stats.get("abnormalCount")).append('\n');
        for (EnvReading r : readings) {
            sb.append("R|").append(r.getId())
              .append('|').append(r.getPointCode())
              .append('|').append(r.getReadAt().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME))
              .append('|').append(r.getReadingDate())
              .append('|').append(r.getReadAtZone())
              .append('|').append(r.getTemperature().toPlainString())
              .append('|').append(r.getHumidity().toPlainString())
              .append('|').append(r.getIlluminance().toPlainString())
              .append('|').append(r.getAbnormal())
              .append('|').append(r.getAbnormalMetrics() == null ? "" : r.getAbnormalMetrics())
              .append('|').append(r.getRuleVersionNo())
              .append('\n');
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String canonicalFromSnapshot(Map<String, Object> snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("H|").append(snapshot.get("hallId")).append('\n')
          .append("D|").append(snapshot.get("readingDate")).append('\n')
          .append("Z|").append(snapshot.get("hallZone")).append('\n');
        Map<String, Object> stats = (Map<String, Object>) snapshot.get("stats");
        sb.append("S|").append(stats.get("pointCount"))
          .append('|').append(stats.get("readingCount"))
          .append('|').append(bd(stats.get("luxMin")))
          .append('|').append(bd(stats.get("luxMax")))
          .append('|').append(bd(stats.get("luxAvg")))
          .append('|').append(stats.get("abnormalCount")).append('\n');
        List<Map<String, Object>> readings = (List<Map<String, Object>>) snapshot.get("readings");
        for (Map<String, Object> r : readings) {
            sb.append("R|").append(r.get("readingId"))
              .append('|').append(r.get("pointCode"))
              .append('|').append(r.get("readAt"))
              .append('|').append(r.get("readingDate"))
              .append('|').append(r.get("readAtZone"))
              .append('|').append(bd(r.get("temperature")))
              .append('|').append(bd(r.get("humidity")))
              .append('|').append(bd(r.get("illuminance")))
              .append('|').append(r.get("abnormal"))
              .append('|').append(r.get("abnormalMetrics") == null ? "" : r.get("abnormalMetrics"))
              .append('|').append(r.get("ruleVersionNo"))
              .append('\n');
        }
        return sb.toString();
    }

    /** 实体统计列占位方法已移除（重算以快照内 stats 为准）。 */

    private static String bd(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).toPlainString();
        }
        return v.toString();
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
