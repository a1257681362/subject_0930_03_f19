package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 照度日报生成/更正请求。
 * 归属日期口径：真实采样瞬时在展厅设备时区下的本地日期。
 */
@Data
public class LuxDailyReportRequest {

    /** 归属日期（设备时区本地日期，报表分组键） */
    @NotNull(message = "归属日期不能为空")
    private LocalDate localDate;

    @NotBlank(message = "生成人不能为空")
    private String generatedBy;

    /**
     * 更正原因：该归属日已存在 CURRENT 版本时必填——
     * 再生成即更正版本，旧快照置 SUPERSEDED 保留不覆盖。
     */
    private String reason;
}
