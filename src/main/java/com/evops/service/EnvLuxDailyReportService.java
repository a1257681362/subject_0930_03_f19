package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.entity.EnvLuxDailyReport;
import com.evops.entity.EnvLuxDailyReportLine;
import com.evops.entity.EnvReading;
import com.evops.entity.Hall;
import com.evops.mapper.EnvLuxDailyReportLineMapper;
import com.evops.mapper.EnvLuxDailyReportMapper;
import com.evops.mapper.EnvReadingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static com.evops.constant.MuseumConstants.*;

/**
 * 照度日报（museum-17）：分组键是读数真实瞬时在<b>设备时区</b>下的归属日期
 * （t_env_reading.local_date），不是服务器/JVM 日期，也不是对挂钟时间 toLocalDate。
 *
 * <p>快照只追加、版本化：首版 CURRENT；此后任何修复都只能再出一个更正版本，
 * 旧版本置 SUPERSEDED 连同其明细行原样保留——历史报表只通过更正版本修复，
 * 不覆盖、不删除旧快照。原始读数事实从不由报表侧改写（展示层不得改事实）。</p>
 */
@Service
public class EnvLuxDailyReportService {

    private static final int LOCK_STRIPES = 16;

    private final EnvLuxDailyReportMapper reportMapper;
    private final EnvLuxDailyReportLineMapper lineMapper;
    private final EnvReadingMapper readingMapper;
    private final HallService hallService;
    private final EnvLedgerService ledgerService;
    private final MuseumClock clock;

    /** 同展厅同归属日的版本切换串行化：防止两次更正并发产生两个 CURRENT */
    private final ReentrantLock[] stripeLocks;

    public EnvLuxDailyReportService(EnvLuxDailyReportMapper reportMapper,
                                    EnvLuxDailyReportLineMapper lineMapper,
                                    EnvReadingMapper readingMapper,
                                    HallService hallService,
                                    EnvLedgerService ledgerService,
                                    MuseumClock clock) {
        this.reportMapper = reportMapper;
        this.lineMapper = lineMapper;
        this.readingMapper = readingMapper;
        this.hallService = hallService;
        this.ledgerService = ledgerService;
        this.clock = clock;
        this.stripeLocks = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            stripeLocks[i] = new ReentrantLock();
        }
    }

    /**
     * 生成（或更正）展厅某归属日的照度日报快照。
     *
     * @param reason 更正原因：已存在 CURRENT 版本时必填——再生成即更正，
     *               旧版本置 SUPERSEDED 保留，新版本号递增；首次生成应传 null
     */
    @Transactional
    public EnvLuxDailyReport generate(Long hallId, LocalDate localDate,
                                      String generatedBy, String reason) {
        Hall hall = hallService.requireHall(hallId);
        if (localDate == null) {
            throw new IllegalArgumentException("归属日期不能为空");
        }
        if (generatedBy == null || generatedBy.trim().isEmpty()) {
            throw new IllegalArgumentException("生成人不能为空");
        }
        ReentrantLock lock = stripeLock(hallId, localDate);
        lock.lock();
        try {
            // 归属日期严格用读数落库的 local_date（真实瞬时在设备时区下的日期），
            // 此处绝不按服务器时区重新解释 read_at
            List<EnvReading> readings = readingMapper.selectList(new LambdaQueryWrapper<EnvReading>()
                    .eq(EnvReading::getHallId, hallId)
                    .eq(EnvReading::getLocalDate, localDate)
                    .orderByAsc(EnvReading::getReadAtEpochMilli)
                    .orderByAsc(EnvReading::getId));
            if (readings.isEmpty()) {
                throw new IllegalArgumentException("展厅 " + hall.getHallNo() + " 在归属日 "
                        + localDate + " 没有照度读数，无法生成日报");
            }

            EnvLuxDailyReport current = findCurrent(hallId, localDate);
            boolean correction = current != null;
            if (correction && (reason == null || reason.trim().isEmpty())) {
                throw new IllegalArgumentException("归属日 " + localDate
                        + " 已存在当前版本报表 v" + current.getVersionNo()
                        + "，修复必须以更正版本追加并填写更正原因，不得覆盖旧快照");
            }
            int nextVersion = correction ? current.getVersionNo() + 1
                    : nextVersion(hallId, localDate);

            EnvLuxDailyReport report = new EnvLuxDailyReport();
            report.setReportNo("LD-" + String.format("%06d", reportMapper.nextReportNo()));
            report.setHallId(hallId);
            report.setLocalDate(localDate);
            report.setZoneIds(readings.stream().map(EnvReading::getReadZone)
                    .collect(Collectors.toCollection(LinkedHashSet::new))
                    .stream().collect(Collectors.joining(",")));
            report.setVersionNo(nextVersion);
            report.setStatus(LUX_REPORT_CURRENT);
            report.setCorrectedReason(correction ? reason.trim() : null);
            report.setGeneratedBy(generatedBy.trim());
            report.setGeneratedAt(clock.nowServerWall());

            Stats overall = Stats.of(readings.stream()
                    .map(EnvReading::getIlluminance).collect(Collectors.toList()));
            report.setReadingCount(readings.size());
            report.setMinLux(overall.min);
            report.setMaxLux(overall.max);
            report.setAvgLux(overall.avg);
            report.setFirstReadAt(readings.get(0).getReadAt());
            report.setLastReadAt(readings.get(readings.size() - 1).getReadAt());
            reportMapper.insert(report);

            // 点位明细随版本冻结
            Map<String, List<EnvReading>> byPoint = readings.stream()
                    .collect(Collectors.groupingBy(EnvReading::getPointCode,
                            LinkedHashMap::new, Collectors.toList()));
            for (Map.Entry<String, List<EnvReading>> e : byPoint.entrySet()) {
                Stats s = Stats.of(e.getValue().stream()
                        .map(EnvReading::getIlluminance).collect(Collectors.toList()));
                EnvLuxDailyReportLine line = new EnvLuxDailyReportLine();
                line.setReportId(report.getId());
                line.setPointCode(e.getKey());
                line.setReadingCount(e.getValue().size());
                line.setMinLux(s.min);
                line.setMaxLux(s.max);
                line.setAvgLux(s.avg);
                lineMapper.insert(line);
            }

            if (correction) {
                // 旧快照只置状态、不覆盖其任何内容
                current.setStatus(LUX_REPORT_SUPERSEDED);
                current.setSupersededAt(clock.nowServerWall());
                reportMapper.updateById(current);
            }

            ledgerService.append(hallId, null, null, null, null,
                    correction ? ACTION_LUX_DAILY_CORRECT : ACTION_LUX_DAILY_PUBLISH,
                    report.getReportNo(),
                    (correction ? "照度日报更正：归属日 " + localDate + " 由 v"
                            + current.getVersionNo() + " 更正为 v" + nextVersion
                            + "，原因：" + reason.trim()
                            : "照度日报生成：展厅 " + hall.getHallNo() + " 归属日 " + localDate
                            + "（设备时区 " + report.getZoneIds() + "）v1，读数 "
                            + readings.size() + " 条")
                            + "；分组键为真实采样瞬时在设备时区下的归属日期，旧快照保留不覆盖",
                    clock.nowServerWall());
            return report;
        } finally {
            lock.unlock();
        }
    }

    /** 某展厅某归属日的全部版本（新到旧） */
    public List<EnvLuxDailyReport> listVersions(Long hallId, LocalDate localDate) {
        hallService.requireHall(hallId);
        LambdaQueryWrapper<EnvLuxDailyReport> qw = new LambdaQueryWrapper<EnvLuxDailyReport>()
                .eq(EnvLuxDailyReport::getHallId, hallId)
                .eq(localDate != null, EnvLuxDailyReport::getLocalDate, localDate)
                .orderByDesc(EnvLuxDailyReport::getLocalDate)
                .orderByAsc(EnvLuxDailyReport::getVersionNo);
        return reportMapper.selectList(qw);
    }

    public Map<String, Object> reportDetail(Long reportId) {
        EnvLuxDailyReport report = requireReport(reportId);
        List<EnvLuxDailyReportLine> lines = lineMapper.selectList(
                new LambdaQueryWrapper<EnvLuxDailyReportLine>()
                        .eq(EnvLuxDailyReportLine::getReportId, reportId)
                        .orderByAsc(EnvLuxDailyReportLine::getPointCode));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("report", report);
        detail.put("lines", lines);
        return detail;
    }

    /** 某展厅某归属日当前生效版本（无则 null） */
    public EnvLuxDailyReport findCurrent(Long hallId, LocalDate localDate) {
        return reportMapper.selectOne(new LambdaQueryWrapper<EnvLuxDailyReport>()
                .eq(EnvLuxDailyReport::getHallId, hallId)
                .eq(EnvLuxDailyReport::getLocalDate, localDate)
                .eq(EnvLuxDailyReport::getStatus, LUX_REPORT_CURRENT)
                .last("LIMIT 1"));
    }

    public EnvLuxDailyReport requireReport(Long reportId) {
        EnvLuxDailyReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new IllegalArgumentException("照度日报不存在: id=" + reportId);
        }
        return report;
    }

    private int nextVersion(Long hallId, LocalDate localDate) {
        Integer max = reportMapper.selectObjs(new LambdaQueryWrapper<EnvLuxDailyReport>()
                .select(EnvLuxDailyReport::getVersionNo)
                .eq(EnvLuxDailyReport::getHallId, hallId)
                .eq(EnvLuxDailyReport::getLocalDate, localDate)
                .orderByDesc(EnvLuxDailyReport::getVersionNo)
                .last("LIMIT 1"))
                .stream().findFirst().map(o -> (Integer) o).orElse(0);
        return max + 1;
    }

    private ReentrantLock stripeLock(Long hallId, LocalDate localDate) {
        return stripeLocks[Math.floorMod(
                31 * hallId.hashCode() + localDate.hashCode(), LOCK_STRIPES)];
    }

    /** 照度聚合：和与个数用精确运算，均值四位小数 HALF_UP；min/max 保留两位 */
    private static final class Stats {
        final BigDecimal min;
        final BigDecimal max;
        final BigDecimal avg;

        private Stats(BigDecimal min, BigDecimal max, BigDecimal avg) {
            this.min = min;
            this.max = max;
            this.avg = avg;
        }

        static Stats of(List<BigDecimal> values) {
            BigDecimal min = values.get(0);
            BigDecimal max = values.get(0);
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal v : values) {
                min = min.min(v);
                max = max.max(v);
                sum = sum.add(v);
            }
            BigDecimal avg = sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
            return new Stats(min, max, avg);
        }
    }
}
