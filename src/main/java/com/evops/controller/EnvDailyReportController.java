package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.DailyReportCorrectRequest;
import com.evops.entity.EnvDailyReport;
import com.evops.service.EnvDailyReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 展厅照度日报：按读数【展厅时区归属日】分组。
 * 首版冻结后不可变；修复历史归属/统计只能出更正版本（版本链可查），旧快照不覆盖；
 * 另提供内容哈希校验与历史读数归属补录入口。
 */
@RestController
public class EnvDailyReportController {

    private final EnvDailyReportService reportService;

    public EnvDailyReportController(EnvDailyReportService reportService) {
        this.reportService = reportService;
    }

    /** 冻结某展厅某归属日首版照度日报（已冻结须走更正） */
    @PostMapping("/api/env-daily/freeze")
    public ApiResponse<EnvDailyReport> freeze(@RequestParam Long hallId,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                              @RequestParam String frozenBy) {
        return ApiResponse.ok(reportService.freeze(hallId, date, frozenBy));
    }

    /** 更正版本：基于读数事实重算，旧快照原样保留（事实无变化拒绝） */
    @PostMapping("/api/env-daily/correct")
    public ApiResponse<EnvDailyReport> correct(@Valid @RequestBody DailyReportCorrectRequest request) {
        return ApiResponse.ok(reportService.correct(request));
    }

    /** 当前生效版本（最新版本号） */
    @GetMapping("/api/env-daily/latest")
    public ApiResponse<EnvDailyReport> latest(@RequestParam Long hallId,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(reportService.latestVersion(hallId, date));
    }

    /** 指定历史版本（旧快照原样查看，展示层不改事实） */
    @GetMapping("/api/env-daily/versions/{version}")
    public ApiResponse<EnvDailyReport> version(@RequestParam Long hallId,
                                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                               @PathVariable int version) {
        return ApiResponse.ok(reportService.getVersion(hallId, date, version));
    }

    /** 更正版本链：首版到最新版本全部保留 */
    @GetMapping("/api/env-daily/versions")
    public ApiResponse<List<EnvDailyReport>> chain(@RequestParam Long hallId,
                                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(reportService.versionChain(hallId, date));
    }

    /** 展厅各归属日最新版列表 */
    @GetMapping("/api/env-daily")
    public ApiResponse<List<EnvDailyReport>> list(@RequestParam Long hallId) {
        return ApiResponse.ok(reportService.listLatest(hallId));
    }

    /** 快照完整性：按冻结的读数事实与统计重算 SHA-256，返回是否一致 */
    @GetMapping("/api/env-daily/{id}/verify")
    public ApiResponse<Map<String, Object>> verify(@PathVariable Long id) {
        boolean ok = reportService.verifyHash(id);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("reportId", id);
        r.put("hashValid", ok);
        return ApiResponse.ok(r);
    }

    /** 历史读数归属补录（升级前缺 reading_date 的行，只补归属列不改 read_at）；默认幂等无操作 */
    @PostMapping("/api/env-daily/backfill-reading-dates")
    public ApiResponse<Map<String, Object>> backfill() {
        int n = reportService.backfillLegacyReadingDates();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("backfilled", n);
        return ApiResponse.ok(r);
    }
}
