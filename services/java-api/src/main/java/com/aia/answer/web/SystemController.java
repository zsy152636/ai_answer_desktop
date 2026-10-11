package com.aia.answer.web;

import com.aia.answer.common.ApiResponse;
import com.aia.answer.domain.SystemDtos;
import com.aia.answer.service.SystemService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统设置（契约 §8）。
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final SystemService service;

    public SystemController(SystemService service) {
        this.service = service;
    }

    @GetMapping("/info")
    public ApiResponse<SystemDtos.SystemInfoView> info() {
        return ApiResponse.ok(service.info());
    }

    @GetMapping("/backups")
    public ApiResponse<List<SystemDtos.BackupView>> backups() {
        return ApiResponse.ok(service.backups());
    }

    @PostMapping("/backup")
    public ApiResponse<SystemDtos.BackupView> backup() {
        return ApiResponse.ok(service.backupNow());
    }

    @PostMapping("/restore")
    public ApiResponse<SystemDtos.RestoreResponse> restore(@Valid @RequestBody SystemDtos.RestoreRequest request) {
        return ApiResponse.ok(service.restore(request));
    }

    @PostMapping("/integrity-check")
    public ApiResponse<SystemDtos.IntegrityView> integrityCheck() {
        return ApiResponse.ok(service.integrityCheck());
    }

    /** 对指定备份做只读校验，供恢复前二次确认。 */
    @PostMapping("/backups/verify")
    public ApiResponse<SystemDtos.IntegrityView> verifyBackup(@RequestParam String fileName) {
        return ApiResponse.ok(service.verifyBackup(fileName));
    }

    @PostMapping("/seed-demo")
    public ApiResponse<SystemDtos.SeedDemoResponse> seedDemo() {
        return ApiResponse.ok(service.seedDemo());
    }

    @GetMapping("/logs")
    public ApiResponse<List<SystemDtos.LogFileView>> logs() {
        return ApiResponse.ok(service.logs());
    }

    @GetMapping("/logs/tail")
    public ApiResponse<SystemDtos.LogTailView> logTail(@RequestParam String file) {
        return ApiResponse.ok(service.logTail(file));
    }
}
