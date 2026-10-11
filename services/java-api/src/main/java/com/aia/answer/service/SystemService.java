package com.aia.answer.service;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.AppInfo;
import com.aia.answer.common.AtomicWrite;
import com.aia.answer.common.ErrorCode;
import com.aia.answer.common.Json;
import com.aia.answer.config.AppProperties;
import com.aia.answer.db.BackupService;
import com.aia.answer.db.DatabaseBootstrap;
import com.aia.answer.db.SqliteCapabilityRegistry;
import com.aia.answer.domain.SystemDtos;
import com.aia.answer.repository.MetaRepository;
import com.aia.answer.service.setting.SettingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 系统设置服务（契约 §8）。
 *
 * <p>恢复是两步式：这里只做校验与「恢复请求」登记，真正的文件替换发生在下次启动、
 * 连接池打开之前。这样既避免在线替换数据库文件导致连接池持有坏句柄，
 * 也让用户有机会看到「需要重启服务」的明确提示。
 */
@Service
public class SystemService {

    private static final Logger log = LoggerFactory.getLogger(SystemService.class);

    private final AppProperties props;
    private final BackupService backupService;
    private final DatabaseBootstrap bootstrap;
    private final MetaRepository metaRepository;
    private final DemoDataService demoDataService;
    private final AiClient aiClient;
    /** 文件驱动配置的唯一落地点：/system/info 要展示它的绝对路径（契约 §8）。 */
    private final SettingStore settingStore;
    private final Json json;
    private final JdbcTemplate jdbc;
    private final SqliteCapabilityRegistry capabilities;

    public SystemService(AppProperties props, BackupService backupService, DatabaseBootstrap bootstrap,
                         MetaRepository metaRepository, DemoDataService demoDataService, AiClient aiClient,
                         SettingStore settingStore, Json json, DataSource dataSource,
                         SqliteCapabilityRegistry capabilities) {
        this.props = props;
        this.backupService = backupService;
        this.bootstrap = bootstrap;
        this.metaRepository = metaRepository;
        this.demoDataService = demoDataService;
        this.aiClient = aiClient;
        this.settingStore = settingStore;
        this.json = json;
        this.jdbc = new JdbcTemplate(dataSource);
        this.capabilities = capabilities;
    }

    public SystemDtos.MetaView meta() {
        return new SystemDtos.MetaView(AppInfo.VERSION, AppInfo.CONTRACT_VERSION, schemaVersion(),
                props.resolveDataDir().toString(), props.databasePath().toString(), backupService.databaseSizeBytes(),
                fts5Available(), aiClient.status(), startedAt());
    }

    public SystemDtos.SystemInfoView info() {
        List<SystemDtos.BackupView> backups = backups();
        return new SystemDtos.SystemInfoView(AppInfo.VERSION, schemaVersion(), props.resolveDataDir().toString(),
                props.logsDir().toString(), props.databasePath().toString(), backupService.databaseSizeBytes(),
                backups.size(), backups, uptimeSeconds(),
                // 契约 §8：配置源固定是 setting.json，路径必须显式展示给用户
                SettingStore.FILE_NAME, settingStore.path().toAbsolutePath().toString(), aiClient.status());
    }

    public List<SystemDtos.BackupView> backups() {
        return backupService.list().stream()
                .map(b -> new SystemDtos.BackupView(b.fileName(), b.sizeBytes(), b.checksum(), b.createdAt(),
                        b.schemaVersion(), b.appVersion(), b.integrityOk(), b.kind()))
                .toList();
    }

    public SystemDtos.BackupView backupNow() {
        BackupService.BackupInfo info = backupService.backupNow("manual");
        return new SystemDtos.BackupView(info.fileName(), info.sizeBytes(), info.checksum(), info.createdAt(),
                info.schemaVersion(), info.appVersion(), info.integrityOk(), info.kind());
    }

    public SystemDtos.RestoreResponse restore(SystemDtos.RestoreRequest request) {
        if (!Boolean.TRUE.equals(request.confirm())) {
            throw ApiException.validation("恢复操作必须显式确认（confirm=true）", "confirm");
        }
        BackupService.BackupInfo info = backupService.requestRestore(request.fileName());
        metaRepository.audit("system.restore_requested", "database", info.fileName(), "{}", nowUtc());
        return new SystemDtos.RestoreResponse(true, true, new SystemDtos.BackupView(info.fileName(), info.sizeBytes(),
                info.checksum(), info.createdAt(), info.schemaVersion(), info.appVersion(), info.integrityOk(),
                info.kind()));
    }

    /** 备份文件的只读校验，恢复前用户可以先单独跑一次。 */
    public SystemDtos.IntegrityView verifyBackup(String fileName) {
        DatabaseBootstrap.IntegrityReport report = backupService.verify(fileName);
        return new SystemDtos.IntegrityView(report.ok(), report.integrityCheck(), report.foreignKeyCheck(),
                report.schemaVersion(), backupService.databaseSizeBytes());
    }

    public SystemDtos.IntegrityView integrityCheck() {
        DatabaseBootstrap.IntegrityReport report = currentDatabaseReport();
        return new SystemDtos.IntegrityView(report.ok(), report.integrityCheck(), report.foreignKeyCheck(),
                report.schemaVersion(), backupService.databaseSizeBytes());
    }

    public SystemDtos.SeedDemoResponse seedDemo() {
        return demoDataService.seed();
    }

    public List<SystemDtos.LogFileView> logs() {
        Path dir = props.logsDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<SystemDtos.LogFileView> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".log"))
                    .forEach(path -> {
                        try {
                            files.add(new SystemDtos.LogFileView(path.getFileName().toString(), Files.size(path),
                                    DateTimeFormatter.ISO_INSTANT.format(Files.getLastModifiedTime(path).toInstant()
                                            .truncatedTo(ChronoUnit.SECONDS))));
                        } catch (IOException ignored) {
                            // 文件在列举过程中被轮转，跳过即可
                        }
                    });
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "读取日志目录失败：" + e.getMessage());
        }
        files.sort(Comparator.comparing(SystemDtos.LogFileView::modifiedAt).reversed());
        return files;
    }

    public SystemDtos.LogTailView logTail(String fileName) {
        Path dir = props.logsDir();
        Path target = dir.resolve(fileName == null ? "" : fileName).normalize();
        if (fileName == null || fileName.isBlank() || !target.startsWith(dir) || !Files.isRegularFile(target)) {
            throw ApiException.notFound("日志文件 " + fileName);
        }
        try {
            List<String> all = Files.readAllLines(target, StandardCharsets.UTF_8);
            int limit = Math.max(1, props.getLogTailLines());
            List<String> tail = all.size() <= limit ? all : all.subList(all.size() - limit, all.size());
            return new SystemDtos.LogTailView(fileName, tail.stream().map(LogRedactor::redact).toList());
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "读取日志失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------- 内部

    private DatabaseBootstrap.IntegrityReport currentDatabaseReport() {
        String url = "jdbc:sqlite:" + props.databasePath().toAbsolutePath().toString().replace('\\', '/')
                + "?foreign_keys=on";
        try (var connection = java.sql.DriverManager.getConnection(url);
             var statement = connection.createStatement()) {
            String integrity;
            try (var rs = statement.executeQuery("PRAGMA integrity_check")) {
                integrity = rs.next() ? rs.getString(1) : "unknown";
            }
            StringBuilder fk = new StringBuilder();
            try (var rs = statement.executeQuery("PRAGMA foreign_key_check")) {
                while (rs.next()) {
                    fk.append(rs.getString(1)).append(' ');
                }
            }
            int version = schemaVersion();
            return new DatabaseBootstrap.IntegrityReport("ok".equalsIgnoreCase(integrity) && fk.isEmpty(),
                    integrity, fk.isEmpty() ? "ok" : fk.toString().trim(), version);
        } catch (Exception e) {
            return new DatabaseBootstrap.IntegrityReport(false, "无法打开数据库：" + e.getMessage(), "unknown", 0);
        }
    }

    public int schemaVersion() {
        Integer version = jdbc.queryForObject("SELECT COALESCE(MAX(version),0) FROM schema_version", Integer.class);
        return version == null ? 0 : version;
    }

    /** FTS5 检索能力自检（FTS5 + trigram）：不可用时检索走关键词降级（开发文档 §10.1）。 */
    public boolean fts5Available() {
        return capabilities.fts5Trigram();
    }

    private long uptimeSeconds() {
        return ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
    }

    private String startedAt() {
        return DateTimeFormatter.ISO_INSTANT.format(
                Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime())
                        .truncatedTo(ChronoUnit.SECONDS));
    }

    private static String nowUtc() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    /** 日志脱敏：任何写进日志的密钥形态都要在这里被压掉后再返回给前端。 */
    static final class LogRedactor {
        private static final java.util.regex.Pattern SECRET = java.util.regex.Pattern.compile(
                "(?i)(sk-[A-Za-z0-9_\\-]{8,}|api[_-]?key\\s*[=:]\\s*\\S+|authorization\\s*[=:]\\s*\\S+|bearer\\s+\\S+)");

        static String redact(String line) {
            return SECRET.matcher(line).replaceAll("***REDACTED***");
        }
    }

    /** 供诊断包与导出复用的脱敏入口。 */
    public String redactedText(String raw) {
        return LogRedactor.redact(raw);
    }

    /** 供诊断使用：数据目录内路径白名单校验。 */
    public boolean isInsideDataDir(Path candidate) {
        return bootstrap.isInsideDataDir(candidate);
    }

    /** 读取任意数据目录内的文本文件（有大小上限），用于诊断。 */
    public String readInsideDataDir(String relative) {
        Path target = props.resolveDataDir().resolve(relative).normalize();
        if (!bootstrap.isInsideDataDir(target) || !Files.isRegularFile(target)) {
            throw ApiException.notFound("文件 " + relative);
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            if (bytes.length > props.getMaxPayloadBytes()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "文件超过读取上限");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "读取文件失败：" + e.getMessage());
        }
    }

    /** 供测试与诊断读取端点文件内容。 */
    public String endpointFileContent() {
        return AtomicWrite.readString(props.resolveEndpointFile()).orElse("");
    }

    /** 供契约测试断言使用的 JSON 帮助方法。 */
    public String toJson(Object value) {
        return json.write(value);
    }
}
