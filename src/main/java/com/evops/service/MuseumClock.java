package com.evops.service;

import com.evops.config.MuseumEnvProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRules;
import java.util.List;

/**
 * 全馆统一时钟：显式区分<b>服务器时间</b>与<b>设备（展厅）时间</b>，
 * 不再隐式使用 {@code LocalDateTime.now()} 的 JVM 默认时区。
 *
 * <ul>
 *   <li>服务器时间：任务节拍、处置/复核等操作留痕的统一口径
 *       （{@code museum.env.server-timezone}，留空取 JVM 默认时区）；</li>
 *   <li>设备时间：采样挂钟时间按展厅 {@code t_hall.timezone} 解释，
 *       缺省用 {@code museum.env.default-device-timezone}；</li>
 *   <li>真实瞬时：任何日期归属都以 {@link Instant}（UTC）在设备时区下的
 *       {@link LocalDate} 为准，JVM/服务器时区变化不影响事实归属。</li>
 * </ul>
 */
@Component
public class MuseumClock {

    private static final Logger log = LoggerFactory.getLogger(MuseumClock.class);

    private final MuseumEnvProperties properties;
    private final ZoneId serverZone;
    private final ZoneId defaultDeviceZone;

    public MuseumClock(MuseumEnvProperties properties) {
        this.properties = properties;
        this.serverZone = properties.getServerTimezone() == null
                || properties.getServerTimezone().trim().isEmpty()
                ? ZoneId.systemDefault()
                : resolveZone(properties.getServerTimezone(), "服务器时区");
        this.defaultDeviceZone = resolveZone(properties.getDefaultDeviceTimezone(), "默认设备时区");
    }

    @PostConstruct
    public void announce() {
        ZoneId jvm = ZoneId.systemDefault();
        log.info("时钟口径：服务器时区 {}（JVM 默认 {}），默认设备时区 {}",
                serverZone.getId(), jvm.getId(), defaultDeviceZone.getId());
        // 规则相同即视为一致（如配置 UTC 与 JVM Etc/UTC 只是别名，不告警）
        if (!serverZone.getRules().equals(jvm.getRules())) {
            // 不一致不致命——调度与留痕已显式按 serverZone 走，但提示运维核对服务器时间配置
            log.warn("JVM 默认时区 {} 与配置的服务器时区 {} 规则不一致，任务节拍/操作留痕以配置时区为准",
                    jvm.getId(), serverZone.getId());
        }
    }

    /** 当前绝对瞬时（UTC） */
    public Instant nowInstant() {
        return Instant.now();
    }

    /** 服务器时区下的当前挂钟时间（任务节拍、操作留痕口径） */
    public LocalDateTime nowServerWall() {
        return LocalDateTime.ofInstant(Instant.now(), serverZone);
    }

    public ZoneId serverZone() {
        return serverZone;
    }

    public ZoneId defaultDeviceZone() {
        return defaultDeviceZone;
    }

    /** 解析展厅设备时区：空串/空值回落默认设备时区；非法 IANA ID 直接拒绝 */
    public ZoneId deviceZone(String hallTimezone) {
        if (hallTimezone == null || hallTimezone.trim().isEmpty()) {
            return defaultDeviceZone;
        }
        return resolveZone(hallTimezone.trim(), "展厅设备时区");
    }

    /** 设备时区当前挂钟时间（提交读数缺省采样时间口径） */
    public LocalDateTime nowDeviceWall(ZoneId deviceZone) {
        return LocalDateTime.ofInstant(Instant.now(), deviceZone);
    }

    /**
     * 当前时刻的采样事实（提交读数缺省 readAt 时使用）：直接由当前真实瞬时构造，
     * 偏移与归属日期从瞬时换算，即使此刻恰在 DST 重叠窗口内也无歧义、不会误拒。
     */
    public SampledAt sampleNow(ZoneId deviceZone) {
        Instant now = Instant.now();
        ZoneOffset offset = deviceZone.getRules().getOffset(now);
        LocalDateTime wall = LocalDateTime.ofInstant(now, deviceZone);
        return new SampledAt(wall, now, deviceZone, offset, wall.toLocalDate());
    }

    /**
     * 把设备本地挂钟时间按时区解释为一次真实采样（严格口径，用于新读数提交）：
     * 原始挂钟值原样保留（不做任何改写），只换算绝对瞬时、当时偏移（夏令时证据）与归属日期。
     *
     * <p>夏令时边界不猜测：
     * <ul>
     *   <li>春季跳变间隙（本地时间不存在，如 Berlin 02:30）：拒绝，要求设备携带 UTC 偏移重报；</li>
     *   <li>秋季回退重叠（同一挂钟对应两个瞬时，如 02:30 出现两次）：无偏移拒绝，
     *       必须由设备上报偏移消歧；</li>
     *   <li>设备显式携带偏移时，校验该偏移在采样时刻对该时区确实有效，无效即拒绝。</li>
     * </ul>
     *
     * @param offsetMin 设备上报的相对 UTC 偏移分钟（可空）
     */
    public SampledAt sample(LocalDateTime deviceWall, ZoneId deviceZone, Integer offsetMin) {
        ZoneRules rules = deviceZone.getRules();
        if (offsetMin != null) {
            ZoneOffset offset;
            try {
                offset = ZoneOffset.ofTotalSeconds(offsetMin * 60);
            } catch (DateTimeException e) {
                throw new IllegalArgumentException("设备 UTC 偏移分钟非法: " + offsetMin);
            }
            Instant instant = Instant.ofEpochSecond(deviceWall.toEpochSecond(offset));
            if (!rules.isValidOffset(deviceWall, offset)) {
                throw new IllegalArgumentException("采样时间 " + deviceWall + " 在设备时区 "
                        + deviceZone.getId() + " 下 UTC 偏移 " + offset + " 无效"
                        + "（夏令时切换边界请核对设备上报偏移）");
            }
            return new SampledAt(deviceWall, instant, deviceZone, offset,
                    deviceWall.toLocalDate());
        }
        List<ZoneOffset> validOffsets = rules.getValidOffsets(deviceWall);
        if (validOffsets.isEmpty()) {
            throw new IllegalArgumentException("采样时间 " + deviceWall + " 在设备时区 "
                    + deviceZone.getId() + " 不存在（夏令时春季跳变间隙），"
                    + "请设备携带当时 UTC 偏移重新提交，系统不猜测瞬时");
        }
        if (validOffsets.size() > 1) {
            throw new IllegalArgumentException("采样时间 " + deviceWall + " 在设备时区 "
                    + deviceZone.getId() + " 对应两个瞬时（夏令时秋季回退重叠），"
                    + "请设备携带当时 UTC 偏移重新提交以区分，系统不擅自归属");
        }
        ZonedDateTime zdt = ZonedDateTime.of(deviceWall, deviceZone);
        return new SampledAt(deviceWall, zdt.toInstant(), deviceZone,
                zdt.getOffset(), zdt.toLocalDate());
    }

    /**
     * 历史数据回填用宽口径：无设备偏移可依，夏令时间隙按 JVM 规则调整、
     * 重叠取夏令时偏移（best-effort），只用于 museum-17 升级时旧行新列回填，
     * 绝不用于新读数提交。
     */
    public SampledAt sampleLenient(LocalDateTime deviceWall, ZoneId deviceZone) {
        ZonedDateTime zdt = ZonedDateTime.of(deviceWall, deviceZone);
        return new SampledAt(deviceWall, zdt.toInstant(), deviceZone,
                zdt.getOffset(), zdt.toLocalDate());
    }

    private static ZoneId resolveZone(String zoneIdText, String label) {
        try {
            return ZoneId.of(zoneIdText.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(label + "非法（需 IANA 时区标识，如 Asia/Shanghai）: "
                    + zoneIdText);
        }
    }

    /**
     * 一次采样的完整时间事实：挂钟（原样）+ 绝对瞬时 + 时区 + 当时 UTC 偏移 + 归属日期。
     * 偏移随事实固化，夏令时/标准时各有不同值，回放历史不依赖"今天"的时区规则。
     */
    public static final class SampledAt {
        private final LocalDateTime wall;
        private final Instant instant;
        private final ZoneId zone;
        private final ZoneOffset offset;
        private final LocalDate localDate;

        SampledAt(LocalDateTime wall, Instant instant, ZoneId zone,
                  ZoneOffset offset, LocalDate localDate) {
            this.wall = wall;
            this.instant = instant;
            this.zone = zone;
            this.offset = offset;
            this.localDate = localDate;
        }

        /** 原始采样挂钟时间（展示层事实，不得改写） */
        public LocalDateTime getWall() {
            return wall;
        }

        /** 真实采样瞬时（UTC），落库口径 */
        public Instant getInstant() {
            return instant;
        }

        public ZoneId getZone() {
            return zone;
        }

        /** 采样当时相对 UTC 的偏移（夏令时证据） */
        public ZoneOffset getOffset() {
            return offset;
        }

        /** 报表唯一日期归属：真实瞬时在设备时区下的本地日期 */
        public LocalDate getLocalDate() {
            return localDate;
        }
    }
}
