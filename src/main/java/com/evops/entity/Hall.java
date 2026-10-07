package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 展厅：巡检任务按展厅生成。
 * status: ACTIVE 开放 / CLOSED 关闭（关闭后不再生成新任务，历史任务与读数保留）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_hall")
public class Hall extends BaseEntity {

    private String hallNo;

    private String name;

    private String location;

    /** 展厅保管员：事件关闭前须由其复核 */
    private String custodian;

    /**
     * 设备/现场时区（IANA，如 America/New_York）。
     * 读数跨零点归属按此时区把原始采样墙钟时间归日；为空回落全馆默认时区。
     */
    private String timeZone;

    /** ACTIVE / CLOSED */
    private String status;
}
