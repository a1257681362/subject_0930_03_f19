package com.evops.service;

import com.evops.config.MuseumTimeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 全馆统一时钟：业务取时只走这里，禁止再散落 {@code LocalDateTime.now()}。
 *
 * <ul>
 *   <li>业务"当前时间"按配置的服务器时区 {@code museum.time.server-zone} 取墙钟时间，
 *       JVM 默认时区被容器/部署环境改动不影响业务口径；启动时核对 JVM 时区，
 *       不一致默认告警（{@code fail-on-zone-mismatch=true} 可改为拒绝启动）。</li>
 *   <li>测试可替换 {@link Clock}（固定钟/偏移钟），不依赖物理等待。</li>
 *   <li>读数归属日 {@link #readingDate(LocalDateTime, ZoneId)} 按<b>展厅所在时区</b>归日：
 *       这是跨零点归属的唯一口径，原始采样墙钟时间永不改写；
 *       DST 间隙/重叠有确定性规则（见方法注释）。</li>
 * </ul>
 */
@Component
public class MuseumTimeProvider {

    private static final Logger log = LoggerFactory.getLogger(MuseumTimeProvider.class);

    private final MuseumTimeProperties properties;
    private volatile Clock clock;

    public MuseumTimeProvider(MuseumTimeProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void verifySystemZone() {
        ZoneId systemZone = ZoneId.systemDefault();
        ZoneId serverZone = properties.getServerZoneId();
        this.clock = Clock.system(serverZone);
        if (!systemZone.equals(serverZone)) {
            String msg = "JVM 默认时区 " + systemZone + " 与配置的服务器时区 " + serverZone
                    + " 不一致；业务取时按配置时区，巡检跨日归属按展厅时区，不受 JVM 时区影响";
            if (properties.isFailOnZoneMismatch()) {
                throw new IllegalStateException(msg + "（museum.time.fail-on-zone-mismatch=true，拒绝启动）");
            }
            log.warn(msg);
        } else {
            log.info("时间口径核对：JVM/服务器时区一致 = {}", serverZone);
        }
    }

    /** 业务当前墙钟时间（服务器时区）。 */
    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 业务当前时刻（绝对时间，跨时区比较/UTC 口径用）。 */
    public Instant nowInstant() {
        return Instant.now(clock);
    }

    public ZoneId serverZone() {
        return properties.getServerZoneId();
    }

    /** 展厅生效时区：展厅未登记设备/现场时区时回落全馆默认时区。 */
    public ZoneId hallZone(String hallTimeZone) {
        if (hallTimeZone == null || hallTimeZone.trim().isEmpty()) {
            return properties.getDefaultHallZoneId();
        }
        return MuseumTimeProperties.parseZone(hallTimeZone.trim(), "hall.timeZone");
    }

    /**
     * 把原始采样墙钟时间按展厅时区归日（日报分组键的唯一计算口径）。
     *
     * <p>夏令时确定性规则（{@code LocalDateTime.atZone} 的语义，此处显式固定）：
     * <ul>
     *   <li><b>间隙（spring-forward，本地时间不存在）</b>：自动按缺口长度向前调整，
     *       即设备时钟随拨快继续走，归属按调整后的当地日期，该小时"跳过"；</li>
     *   <li><b>重叠（fall-back，本地时间出现两次）</b>：取<b>较早一次</b>偏移，
     *       即同一墙钟读数当夜第一次出现的那一天——夜间巡检先经历第一次（夏令时），
     *       再到第二次（标准时）；</li>
     *   <li>无 DST 时区（如 Asia/Shanghai）即普通归日。</li>
     * </ul>
     *
     * @param sampleWallTime 设备原始采样墙钟时间（原样落库，本方法不修改它）
     * @param hallZone       展厅时区
     * @return 展厅当地归属日
     */
    public LocalDate readingDate(LocalDateTime sampleWallTime, ZoneId hallZone) {
        return resolve(sampleWallTime, hallZone).toLocalDate();
    }

    /** 采样墙钟时间对应的绝对时刻（DST 重叠取较早偏移，间隙向前调整）。 */
    public Instant toInstant(LocalDateTime sampleWallTime, ZoneId hallZone) {
        return resolve(sampleWallTime, hallZone).toInstant();
    }

    private static java.time.ZonedDateTime resolve(LocalDateTime sampleWallTime, ZoneId hallZone) {
        // atZone：gap 自动前推（取过渡后偏移）；overlap 默认取较早偏移，显式写死口径
        return sampleWallTime.atZone(hallZone).withEarlierOffsetAtOverlap();
    }

    /** 测试/运维换钟（固定钟、偏移钟）；生产不调用。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 还原为服务器系统钟。 */
    public void resetClock() {
        this.clock = Clock.system(properties.getServerZoneId());
    }
}
