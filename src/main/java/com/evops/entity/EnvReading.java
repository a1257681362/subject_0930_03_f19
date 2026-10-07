package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 环境读数：一个点位一条（温度/湿度/照度同采）。
 * 规则版本与判定结果在提交时固化，历史读数不随后续规则版本变化；
 * 点位多材质时按材质分别判定，各材质阈值明细见 {@link EnvReadingDetail}。
 * abnormalMetrics 为各材质越限指标码并集逗号拼接（TEMP/HUMIDITY/LUX），正常为 null。
 */
@Data
@TableName("t_env_reading")
public class EnvReading {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Long hallId;

    private String pointCode;

    /** 提交时任务固化的规则版本（快照） */
    private Long ruleVersionId;
    private String ruleVersionNo;

    private BigDecimal temperature;
    private BigDecimal humidity;
    private BigDecimal illuminance;

    /** 参与判定的材质（点位在展且规则有条目的材质，| 分隔） */
    private String matchedMaterials;

    /** 点位在展但生效规则无条目、未参与判定的材质（| 分隔），空为 null */
    private String uncoveredMaterials;

    /** 0 正常 / 1 存在越限指标（任一材质） */
    private Integer abnormal;

    /** 越限指标码并集逗号拼接 */
    private String abnormalMetrics;

    private LocalDateTime readAt;

    /**
     * 归属日：提交时按展厅时区（hall.time_zone，空回落全馆默认时区）对 {@link #readAt}
     * 归日得到，是日报分组的唯一键。原始采样时间 {@code readAt} 原样落库、永不改写。
     */
    private LocalDate readingDate;

    /** 归日所用展厅时区（IANA），随读数固化——展厅日后改时区不改历史读数口径 */
    private String readAtZone;

    /** readAt 在 readAtZone 下的 UTC 绝对时刻（DST 重叠取较早偏移、间隙前推） */
    private OffsetDateTime readAtInstant;

    private String inspector;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
