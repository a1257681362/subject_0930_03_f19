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
 * 照度（含温湿度）日报版本快照：只追加、不可变。
 *
 * <p>同一展厅同一归属日（{@code reading_date}，按展厅时区归日）首版冻结
 * {@code report_version=1}（FREEZE）；归属或统计口径修正后只能出<b>更正版本</b>
 * （CORRECTION，版本号递增，{@code base_version_id/hash} 指向旧版），
 * 旧快照任何字段不更新、不删除——历史报表只通过更正版本修复。
 *
 * <p>{@code contentHash} 为「事实读数 + 统计结果」规范化后的 SHA-256：
 * 任何对快照统计列或底层读数事实的篡改都可通过重算比对发现。
 */
@Data
@TableName("t_env_daily_report")
public class EnvDailyReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String reportNo;

    private Long hallId;

    /** 归属日（读数按展厅时区归日的分组键） */
    private LocalDate readingDate;

    /** 出报所用展厅时区（IANA），随快照固化 */
    private String hallZone;

    /** 同一 (hallId, readingDate) 内版本号，首版 1，更正版本递增 */
    private Integer reportVersion;

    /** FREEZE 首版冻结 / CORRECTION 更正版本 */
    private String source;

    private Integer pointCount;

    private Integer readingCount;

    private BigDecimal luxMin;
    private BigDecimal luxMax;
    private BigDecimal luxAvg;

    private Integer abnormalCount;

    /** 逐点位统计与读数明细快照 JSON（冻结后原样保留） */
    private String snapshotJson;

    /** SHA-256 规范化内容哈希（事实读数 + 统计结果） */
    private String contentHash;

    /** 更正链：本版基于的旧版本 id/哈希（首版为 null） */
    private Long baseVersionId;
    private String baseVersionHash;

    private String correctionReason;
    private String correctedBy;

    private LocalDateTime frozenAt;
    private String frozenBy;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
