package com.evops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 展厅温湿度与照度巡检配置（museum.env.*）。
 *
 * <ul>
 *   <li>{@code default-interval-min}：展厅在展文物材质在生效规则中没有任何匹配条目时
 *       的兜底采样频率（分钟），正常应由规则条目决定；</li>
 *   <li>{@code generate-on-startup}：应用就绪后是否补生成一轮到期巡检任务（重启场景）；</li>
 *   <li>{@code generate-interval-ms}：定时扫描生成周期（毫秒），只生成到点未生成的轮次；</li>
 *   <li>{@code server-timezone}：服务器时间口径（IANA 时区，如 Asia/Shanghai）。
 *       任务节拍与处置/复核等操作留痕统一按此时区；留空取 JVM 默认时区。
 *       它不参与读数的日期归属——读数归属只看展厅设备时区；</li>
 *   <li>{@code default-device-timezone}：展厅未单独登记时区时的默认设备时区。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "museum.env")
public class MuseumEnvProperties {

    /** 无材质匹配条目时的兜底采样频率（分钟） */
    private int defaultIntervalMin = 240;

    /** 启动就绪后是否补扫到期任务 */
    private boolean generateOnStartup = true;

    /** 任务生成定时扫描间隔（毫秒） */
    private long generateIntervalMs = 60000;

    /**
     * 服务器时间口径（IANA ID）。任务调度、节拍判断、操作留痕使用；
     * 留空表示沿用 JVM 默认时区。绝不用于读数日期归属。
     */
    private String serverTimezone = "";

    /** 展厅未登记时区时的默认设备时区（IANA ID） */
    private String defaultDeviceTimezone = "Asia/Shanghai";
}
