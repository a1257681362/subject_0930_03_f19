package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 日报更正请求：基于读数事实重新计算出更正版本。
 * 旧快照不覆盖；必须填写更正原因（留痕，随更正版本永久保存）。
 */
@Data
public class DailyReportCorrectRequest {

    @NotNull(message = "展厅ID不能为空")
    private Long hallId;

    @NotNull(message = "归属日不能为空")
    private LocalDate date;

    @NotBlank(message = "更正原因不能为空")
    private String reason;

    @NotBlank(message = "更正操作人不能为空")
    private String correctedBy;
}
