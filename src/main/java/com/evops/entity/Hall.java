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
     * 设备/展厅所在时区（IANA，如 Asia/Shanghai、Europe/Berlin）。
     * 采样挂钟时间按此时区解释；报表日期归属以真实瞬时在此时区下的本地日期为准。
     * museum-17：设备时区登记，旧库默认 Asia/Shanghai。
     */
    private String timezone;

    /** ACTIVE / CLOSED */
    private String status;
}
