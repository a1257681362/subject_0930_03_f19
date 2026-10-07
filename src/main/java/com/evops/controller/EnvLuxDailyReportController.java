package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.LuxDailyReportRequest;
import com.evops.entity.EnvLuxDailyReport;
import com.evops.service.EnvLuxDailyReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 照度日报（museum-17）：
 * 分组键为真实采样瞬时在<b>展厅设备时区</b>下的归属日期，与服务器/JVM 时区无关；
 * 快照只追加版本化，修复历史报表只出更正版本，旧快照不覆盖。
 */
@RestController
public class EnvLuxDailyReportController {

    private final EnvLuxDailyReportService reportService;

    public EnvLuxDailyReportController(EnvLuxDailyReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * 生成/更正日报：首次生成出 v1 CURRENT；该归属日已有 CURRENT 版本时
     * 必须带 reason，旧版本置 SUPERSEDED、新版本号递增，旧快照保留。
     */
    @PostMapping("/api/halls/{hallId}/lux-daily-reports")
    public ApiResponse<EnvLuxDailyReport> generate(@PathVariable Long hallId,
                                                   @Valid @RequestBody LuxDailyReportRequest request) {
        return ApiResponse.ok(reportService.generate(hallId, request.getLocalDate(),
                request.getGeneratedBy(), request.getReason()));
    }

    /** 某归属日全部版本（新到旧），历史更正链可追溯 */
    @GetMapping("/api/halls/{hallId}/lux-daily-reports")
    public ApiResponse<List<EnvLuxDailyReport>> listVersions(
            @PathVariable Long hallId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate localDate) {
        return ApiResponse.ok(reportService.listVersions(hallId, localDate));
    }

    /** 单个版本详情（含冻结的点位明细行） */
    @GetMapping("/api/lux-daily-reports/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(reportService.reportDetail(id));
    }
}
