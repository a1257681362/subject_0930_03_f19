package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 展厅设备时区更正。
 * 只影响此后新读数的解释与归属；历史读数已固化时区/瞬时/归属日期，不回溯改写。
 */
@Data
public class HallTimezoneRequest {

    /** 设备/展厅时区（IANA，如 Asia/Shanghai、Europe/Berlin） */
    @NotBlank(message = "设备时区不能为空")
    private String timezone;
}
