package com.aia.answer.web;

import com.aia.answer.common.ApiResponse;
import com.aia.answer.domain.SystemDtos;
import com.aia.answer.service.DashboardService;
import com.aia.answer.service.SystemService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 元信息与仪表盘（契约 §1）。
 */
@RestController
@RequestMapping("/api/v1")
public class MetaController {

    private final SystemService systemService;
    private final DashboardService dashboardService;

    public MetaController(SystemService systemService, DashboardService dashboardService) {
        this.systemService = systemService;
        this.dashboardService = dashboardService;
    }

    @GetMapping("/meta")
    public ApiResponse<SystemDtos.MetaView> meta() {
        return ApiResponse.ok(systemService.meta());
    }

    @GetMapping("/dashboard")
    public ApiResponse<SystemDtos.DashboardView> dashboard() {
        return ApiResponse.ok(dashboardService.dashboard());
    }
}
