package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.entity.Hall;
import com.evops.service.HallService;
import com.evops.service.MuseumClock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 时区核对（museum-17）：显式呈现服务器时间口径、JVM 默认时区与各展厅设备时区，
 * 供巡检排障核对"设备时区、服务器时间"。只读诊断，不改变任何事实。
 */
@RestController
@RequestMapping("/api/time")
public class TimeDiagnosticController {

    private final MuseumClock clock;
    private final HallService hallService;

    public TimeDiagnosticController(MuseumClock clock, HallService hallService) {
        this.clock = clock;
        this.hallService = hallService;
    }

    @GetMapping("/diagnosis")
    public ApiResponse<Map<String, Object>> diagnosis() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverTimezone", clock.serverZone().getId());
        result.put("serverNow", clock.nowServerWall());
        result.put("defaultDeviceTimezone", clock.defaultDeviceZone().getId());
        result.put("jvmDefaultTimezone", ZoneId.systemDefault().getId());
        result.put("jvmServerZoneConsistent",
                clock.serverZone().getRules().equals(ZoneId.systemDefault().getRules()));

        List<Map<String, Object>> halls = new ArrayList<>();
        for (Hall hall : hallService.listHalls(null)) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("hallId", hall.getId());
            h.put("hallNo", hall.getHallNo());
            h.put("deviceTimezone", hall.getTimezone());
            h.put("deviceNow", clock.nowDeviceWall(clock.deviceZone(hall.getTimezone())));
            halls.add(h);
        }
        result.put("halls", halls);
        return ApiResponse.ok(result);
    }
}
