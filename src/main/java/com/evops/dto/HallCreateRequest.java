package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 展厅建档 */
@Data
public class HallCreateRequest {

    @NotBlank(message = "展厅编号不能为空")
    private String hallNo;

    @NotBlank(message = "展厅名称不能为空")
    private String name;

    private String location;

    /** 展厅保管员：事件关闭前须由其复核 */
    @NotBlank(message = "展厅保管员不能为空")
    private String custodian;

    /**
     * 设备/展厅时区（IANA，如 Asia/Shanghai、Europe/Berlin）。
     * 留空取 museum.env.default-device-timezone；采样挂钟时间按此时区解释。
     */
    private String timezone;
}
