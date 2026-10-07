package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 照度日报点位明细行：随日报版本冻结，是快照的一部分，永不改写。
 * 更正出新版本时重新计算写新行；旧版本行原样保留。
 */
@Data
@TableName("t_env_lux_daily_report_line")
public class EnvLuxDailyReportLine {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reportId;

    private String pointCode;

    private Integer readingCount;

    private BigDecimal minLux;
    private BigDecimal maxLux;
    private BigDecimal avgLux;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
