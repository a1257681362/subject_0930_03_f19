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
 * 照度日报冻结快照（museum-17）：按展厅 + 设备时区归属日期（local_date）。
 *
 * <p>只追加、版本化：同一展厅同一归属日可有多个版本；首版 CURRENT，
 * 更正再出一版并把旧版置 SUPERSEDED——旧快照及其点位行永不覆盖、永不删除，
 * 历史报表只通过更正版本修复。</p>
 *
 * <p>status: CURRENT 当前生效版本 / SUPERSEDED 已被更正版本取代。</p>
 */
@Data
@TableName("t_env_lux_daily_report")
public class EnvLuxDailyReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String reportNo;

    private Long hallId;

    /** 设备时区归属日期（报表分组键，来自 t_env_reading.local_date） */
    private LocalDate localDate;

    /**
     * 纳入读数的固化设备时区集合（逗号拼接，IANA ID）。
     * 通常只有一个；展厅时区发生过更正时，同一天历史读数可能含多个时区，
     * 报表如实呈现而非抹平——展示层不得改事实。
     */
    private String zoneIds;

    /** 同展厅同归属日内版本号，从 1 递增 */
    private Integer versionNo;

    private Integer readingCount;

    private BigDecimal minLux;
    private BigDecimal maxLux;
    private BigDecimal avgLux;

    private LocalDateTime firstReadAt;
    private LocalDateTime lastReadAt;

    /** CURRENT / SUPERSEDED */
    private String status;

    /** 更正原因：首版为 null；更正版本必填 */
    private String correctedReason;

    private String generatedBy;
    private LocalDateTime generatedAt;
    private LocalDateTime supersededAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
