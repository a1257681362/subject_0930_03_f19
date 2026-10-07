package com.evops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.ZoneId;

/**
 * 时间口径配置（museum.time.*）。
 *
 * <ul>
 *   <li>{@code server-zone}：服务器（进程/JVM 所在主机）声明时区。业务取时一律走
 *       {@link MuseumTimeProvider}，不再散落 {@link java.time.LocalDateTime#now()}，
 *       保证容器时区被改成 UTC 等情况下业务取时口径不变；</li>
 *   <li>{@code default-hall-zone}：展厅未单独登记设备/现场时区时的回落时区；</li>
 *   <li>{@code fail-on-zone-mismatch}：JVM 默认时区与 {@code server-zone} 不一致时是否直接失败。
 *       默认仅告警（兼容既有部署），巡检跨日归属不依赖 JVM 默认时区。</li>
 * </ul>
 *
 * 读数跨日归属按「展厅时区」把原始采样墙钟时间归日，不按服务器时区，
 * 也不按 JVM 默认时区——见 {@code EnvReadingDateResolver}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "museum.time")
public class MuseumTimeProperties {

    /** 服务器声明时区：业务取时口径，缺省 Asia/Shanghai（与既有 jackson.time-zone 一致） */
    private String serverZone = "Asia/Shanghai";

    /** 展厅未登记时区时的回落时区 */
    private String defaultHallZone = "Asia/Shanghai";

    /** JVM 默认时区与 server-zone 不一致时是否拒绝启动（默认告警放行） */
    private boolean failOnZoneMismatch = false;

    private ZoneId serverZoneId;
    private ZoneId defaultHallZoneId;

    @PostConstruct
    public void init() {
        this.serverZoneId = parseZone(serverZone, "museum.time.server-zone");
        this.defaultHallZoneId = parseZone(defaultHallZone, "museum.time.default-hall-zone");
    }

    public static ZoneId parseZone(String zone, String key) {
        if (zone == null || zone.trim().isEmpty()) {
            throw new IllegalStateException(key + " 不能为空");
        }
        try {
            return ZoneId.of(zone.trim());
        } catch (Exception e) {
            throw new IllegalStateException(key + " 不是合法 IANA 时区: " + zone, e);
        }
    }
}
