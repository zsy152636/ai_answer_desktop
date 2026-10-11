package com.aia.answer.service;

import com.aia.answer.config.AppProperties;
import com.aia.answer.db.BackupService;
import com.aia.answer.db.DatabaseBootstrap;
import com.aia.answer.repository.IdempotencyRepository;
import com.aia.answer.repository.MetaRepository;
import com.aia.answer.web.SecurityFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 启动与收尾动作。
 *
 * <p>顺序有意为之：先写端点文件（Electron 才能拿到端口与会话令牌）→ 回收过期租约 →
 * 清理过期幂等记录 → 按需自动备份。全部动作都不得阻塞请求处理。
 */
@Service
public class StartupService {

    private static final Logger log = LoggerFactory.getLogger(StartupService.class);

    private final AppProperties props;
    private final DatabaseBootstrap bootstrap;
    private final BackupService backupService;
    private final TaskService taskService;
    private final IdempotencyRepository idempotencyRepository;
    private final MetaRepository metaRepository;
    private final SecurityFilter securityFilter;
    private final org.springframework.boot.web.context.WebServerApplicationContext webServerContext;

    public StartupService(AppProperties props, DatabaseBootstrap bootstrap, BackupService backupService,
                          TaskService taskService, IdempotencyRepository idempotencyRepository,
                          MetaRepository metaRepository, SecurityFilter securityFilter,
                          org.springframework.boot.web.context.WebServerApplicationContext webServerContext) {
        this.props = props;
        this.bootstrap = bootstrap;
        this.backupService = backupService;
        this.taskService = taskService;
        this.idempotencyRepository = idempotencyRepository;
        this.metaRepository = metaRepository;
        this.securityFilter = securityFilter;
        this.webServerContext = webServerContext;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        int port = webServerContext.getWebServer().getPort();
        bootstrap.writeEndpointFile(port, securityFilter.sessionToken());

        int recovered = taskService.recoverExpiredLeases();
        String now = nowUtc();
        int idempotencyRemoved = idempotencyRepository.deleteExpired(now);
        // LLM 配置已改为文件驱动（setting.json + ConfigService 热加载）：
        // 这里不再 ensureStateRow / deleteExpiredPreviews —— llm_config_state 与
        // llm_import_preview 两张旧表保留结构但运行期不再读写（导入预览改为进程内存 + TTL）。

        boolean backedUp = false;
        if (props.isAutoBackupEnabled()) {
            backedUp = maybeAutoBackup();
        }
        metaRepository.audit("system.started", "system", "java-api",
                "{\"port\":" + port + ",\"recoveredTasks\":" + recovered + "}", now);
        log.info("服务就绪：端口 {}，回收任务 {}，清理幂等记录 {}，自动备份 {}",
                port, recovered, idempotencyRemoved, backedUp ? "已执行" : "未触发");
    }

    private boolean maybeAutoBackup() {
        Optional<String> lastBackupAt = metaRepository.get("last_auto_backup_at");
        if (lastBackupAt.isPresent()) {
            try {
                Instant last = Instant.parse(lastBackupAt.get());
                if (Duration.between(last, Instant.now()).toHours() < props.getAutoBackupHours()) {
                    return false;
                }
            } catch (Exception e) {
                log.warn("上次自动备份时间无法解析，本次强制执行备份");
            }
        }
        try {
            backupService.backupNow("auto");
            metaRepository.put("last_auto_backup_at", nowUtc(), nowUtc());
            return true;
        } catch (Exception e) {
            log.warn("自动备份失败（不影响服务可用）：{}", e.getMessage());
            return false;
        }
    }

    private static String nowUtc() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
