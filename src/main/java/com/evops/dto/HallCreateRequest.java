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
     * 设备/现场时区（IANA，如 America/New_York），可空：
     * 空则回落 museum.time.default-hall-zone；读数跨零点归属按此时区归日。
     */
    private String timeZone;
}
