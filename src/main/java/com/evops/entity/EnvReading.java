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

    /**
     * 原始采样时间：设备/巡检员提交的展厅本地挂钟时间，永不改写（展示层不得改事实）。
     */
    private LocalDateTime readAt;

    /**
     * 真实采样瞬时（UTC epoch 毫秒）：由 readAt 按展厅设备时区换算落库，
     * 与 JVM/服务器时区无关；日期归属、跨天统计只认此瞬时。
     */
    private Long readAtEpochMilli;

    /** 归属设备时区（IANA ID，采样当时展厅配置），随事实固化 */
    private String readZone;

    /** 采样当时相对 UTC 的偏移分钟（夏令时证据，标准时/夏令时各自不同） */
    private Integer readOffsetMin;

    /**
     * 报表分组键：真实瞬时在 {@link #readZone} 下的本地日期。
     * 夜间巡检跨零点据此分属两天；服务器/JVM 时区变化不影响。
     */
    private LocalDate localDate;

    private String inspector;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
