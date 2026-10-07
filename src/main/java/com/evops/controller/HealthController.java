package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.service.MuseumTimeProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存活与时间口径核对：
 * /api/health 仅存活；/api/health/time 给出 JVM 默认时区、配置的服务器时区、
 * 业务当前时间与 UTC 时刻，供巡检跨日归属问题排查（设备时区在各展厅档案中查看）。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final MuseumTimeProvider timeProvider;

    public HealthController(MuseumTimeProvider timeProvider) {
        this.timeProvider = timeProvider;
    }

    @GetMapping
    public ApiResponse<Object> health() {
        return ApiResponse.ok(java.util.Collections.singletonMap("service", "evops"));
    }

    /** 时间口径核对：JVM 时区、服务器时区、业务当前时间（服务器时区墙钟）、UTC 绝对时刻 */
    @GetMapping("/time")
    public ApiResponse<Map<String, Object>> time() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("jvmZone", ZoneId.systemDefault().getId());
        info.put("serverZone", timeProvider.serverZone().getId());
        info.put("serverNow", timeProvider.now());
        info.put("utcInstant", timeProvider.nowInstant());
        info.put("zoneConsistent", ZoneId.systemDefault().equals(timeProvider.serverZone()));
        return ApiResponse.ok(info);
    }
}
