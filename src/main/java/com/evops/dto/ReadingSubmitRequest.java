package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 巡检任务读数提交：一个任务一次提交各点位的温湿度/照度。
 * 按任务固化的规则版本快照判定，阈值/版本在读数行固化，规则新版本不改写已提交读数。
 */
@Data
public class ReadingSubmitRequest {

    @NotBlank(message = "巡检人不能为空")
    private String inspector;

    /**
     * 采样时间（展厅设备本地挂钟时间），缺省取展厅设备时区当前时间。
     * 原始挂钟值原样落库，永不改写。
     */
    private LocalDateTime readAt;

    /**
     * 设备上报的采样当时相对 UTC 偏移分钟（可空）。
     * 夏令时春季跳变/秋季回退等挂钟歧义时刻必须携带，用于确定唯一真实瞬时；
     * 系统校验该偏移在采样时刻对展厅时区有效，无效即拒绝。
     */
    private Integer readOffsetMin;

    @NotEmpty(message = "读数不能为空")
    @Valid
    private List<ReadingPoint> readings;

    @Data
    public static class ReadingPoint {

        @NotBlank(message = "点位编码不能为空")
        private String pointCode;

        @NotNull(message = "温度不能为空")
        private BigDecimal temperature;

        @NotNull(message = "湿度不能为空")
        private BigDecimal humidity;

        @NotNull(message = "照度不能为空")
        private BigDecimal illuminance;
    }
}
