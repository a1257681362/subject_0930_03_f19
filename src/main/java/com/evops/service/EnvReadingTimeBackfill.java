package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.entity.EnvReading;
import com.evops.entity.Hall;
import com.evops.mapper.EnvReadingMapper;
import com.evops.mapper.HallMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * museum-17 历史读数时间事实回填（幂等）。
 *
 * <p>升级前的 t_env_reading 只有挂钟 read_at，没有真实瞬时/时区/归属日期。
 * 启动就绪后扫描 read_at_epoch_milli IS NULL 的历史行，按<b>该行展厅登记的设备时区</b>
 * （展厅无时区则取默认设备时区）用 JVM {@link java.time.zone.ZoneRules} 换算：
 * read_at 原样不动，只补 read_at_epoch_milli / read_zone / read_offset_min / local_date。
 * 时区规则（含历史夏令时）由 JVM tzdata 负责，SQL 层不做任何时区猜测。</p>
 *
 * <p>回填只写新列、逐行 updateById（不改 read_at）；重复执行自动跳过已回填行。</p>
 */
@Component
public class EnvReadingTimeBackfill {

    private static final Logger log = LoggerFactory.getLogger(EnvReadingTimeBackfill.class);

    private final EnvReadingMapper readingMapper;
    private final HallMapper hallMapper;
    private final MuseumClock clock;

    public EnvReadingTimeBackfill(EnvReadingMapper readingMapper,
                                  HallMapper hallMapper,
                                  MuseumClock clock) {
        this.readingMapper = readingMapper;
        this.hallMapper = hallMapper;
        this.clock = clock;
    }

    /** 早于巡检任务补生成执行：保证节拍扫描前历史时间事实已齐 */
    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        List<EnvReading> missing = readingMapper.selectList(new LambdaQueryWrapper<EnvReading>()
                .isNull(EnvReading::getReadAtEpochMilli)
                .orderByAsc(EnvReading::getId));
        if (missing.isEmpty()) {
            return;
        }
        Map<Long, ZoneId> hallZoneCache = new HashMap<>();
        int done = 0;
        for (EnvReading reading : missing) {
            ZoneId zone = hallZoneCache.computeIfAbsent(reading.getHallId(), hid -> {
                Hall hall = hallMapper.selectById(hid);
                return clock.deviceZone(hall == null ? null : hall.getTimezone());
            });
            MuseumClock.SampledAt sample = clock.sampleLenient(reading.getReadAt(), zone);
            EnvReading patch = new EnvReading();
            patch.setId(reading.getId());
            patch.setReadAtEpochMilli(sample.getInstant().toEpochMilli());
            patch.setReadZone(sample.getZone().getId());
            patch.setReadOffsetMin(sample.getOffset().getTotalSeconds() / 60);
            patch.setLocalDate(sample.getLocalDate());
            readingMapper.updateById(patch);
            done++;
        }
        log.info("museum-17 历史读数时间事实回填完成：{} 行（按展厅设备时区，原始 read_at 未改写）", done);
    }
}
