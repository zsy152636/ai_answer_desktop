package com.aia.answer.web;

import com.aia.answer.common.AppInfo;
import com.aia.answer.service.SystemService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查（契约 §1）。只有这个端点不校验会话令牌，且不返回任何敏感信息。
 */
@RestController
public class HealthController {

    private final SystemService systemService;

    public HealthController(SystemService systemService) {
        this.systemService = systemService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", AppInfo.JAVA_SERVICE);
        body.put("version", AppInfo.VERSION);
        body.put("schemaVersion", systemService.schemaVersion());
        body.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        return body;
    }
}
