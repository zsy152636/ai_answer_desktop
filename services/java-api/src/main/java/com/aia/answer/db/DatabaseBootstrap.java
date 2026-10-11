package com.aia.answer.db;

import com.aia.answer.common.AtomicWrite;
import com.aia.answer.common.Json;
import com.aia.answer.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * 数据目录与数据库文件生命周期（开发文档 §4.1、§9.1）。
 *
 * <p>职责边界：本类只在「打开连接池之前」运行，负责目录准备与待恢复请求的落地；
 * 一旦连接池建立，数据库文件的任何替换都被禁止（由 {@code SystemService} 通过
 * 写恢复请求 + 重启实现）。
 */
@Component
public class DatabaseBootstrap {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBootstrap.class);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC);

    private final AppProperties props;
    private final Json json;

    public DatabaseBootstrap(AppProperties props, Json json) {
        this.props = props;
        this.json = json;
    }

    /** 创建用户数据目录布局；已存在则幂等跳过。 */
    public void createDirectories() {
        List<Path> dirs = List.of(
                props.resolveDataDir(),
                props.databasePath().getParent(),
                props.backupsDir(),
                props.logsDir(),
                props.runtimeDir(),
                props.tempDir(),
                // 配置文件目录必须在连接池建立之前就存在：首次启动迁移要在建池后立刻写入 setting.json
                props.configDir(),
                props.filesDir().resolve("imports"),
                props.filesDir().resolve("exports"),
                props.resolveDataDir().resolve("index").resolve("fts"));
        for (Path dir : dirs) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new IllegalStateException("无法创建数据目录：" + dir + "（" + e.getMessage() + "）", e);
            }
        }
    }

    /** 打开连接池之前应用待恢复请求。任何校验失败都放弃恢复并保留现有数据。 */
    public void applyPendingRestore() {
        Path requestFile = props.restoreRequestFile();
        Optional<String> raw = AtomicWrite.readString(requestFile);
        if (raw.isEmpty()) {
            return;
        }
        Path backupsDir = props.backupsDir();
        Path target = props.databasePath();
        try {
            JsonNode node = json.readTree(raw.get(), "restoreRequest");
            String fileName = node.path("fileName").asText("");
            Path backup = backupsDir.resolve(fileName).normalize();
            if (fileName.isBlank() || !backup.startsWith(backupsDir) || !Files.isRegularFile(backup)) {
                log.error("恢复请求被拒绝：备份文件不可信或不存在（{}）", fileName);
                Files.deleteIfExists(requestFile);
                return;
            }
            IntegrityReport report = verifyDatabaseFile(backup);
            if (!report.ok()) {
                log.error("恢复请求被拒绝：备份未通过完整性校验（{}）", report.integrityCheck());
                Files.deleteIfExists(requestFile);
                return;
            }

            // 替换前先把当前库另存一份，形成可回退路径
            if (Files.isRegularFile(target)) {
                Path preRestore = backupsDir.resolve("app.db.pre-restore-" + STAMP.format(Instant.now()) + ".db");
                vacuumIntoCurrent(target, preRestore);
                log.info("恢复前已自动备份当前数据库 → {}", preRestore.getFileName());
            }
            deleteDatabaseFiles(target);
            Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(requestFile);
            log.warn("已从备份 {} 恢复数据库（schema 版本 {}）", fileName, report.schemaVersion());
        } catch (Exception e) {
            log.error("恢复请求执行失败，保留现有数据库", e);
            try {
                Files.deleteIfExists(requestFile);
            } catch (IOException ignored) {
                // 忽略：下次启动会再次尝试判定并拒绝
            }
        }
    }

    /**
     * 迁移前备份（开发文档 §2.1「迁移前必须成功备份」）。
     *
     * <p>此时连接池尚未建立，因此用一条独立连接执行 {@code VACUUM INTO}。
     * 只有确实存在待执行迁移且数据库文件已存在时才备份，避免每次启动都产生一份备份。
     */
    public void backupBeforeMigrationIfNeeded(int latestScriptVersion) {
        Path database = props.databasePath();
        if (!Files.isRegularFile(database)) {
            return;
        }
        int current = currentSchemaVersion(database);
        if (current >= latestScriptVersion) {
            return;
        }
        Path target = props.backupsDir().resolve("app-" + STAMP.format(Instant.now()) + "-pre-migration.db");
        try {
            Files.createDirectories(props.backupsDir());
            vacuumIntoCurrent(database, target);
            log.info("迁移前备份完成（schema {} → {}）：{}", current, latestScriptVersion, target.getFileName());
        } catch (Exception e) {
            throw new IllegalStateException("迁移前备份失败，已中止启动以保护现有数据：" + e.getMessage(), e);
        }
    }

    private int currentSchemaVersion(Path database) {
        // 注意：xerial 驱动不解析裸路径上的 ?mode=ro（会把它当成文件名的一部分），
        // 必须写成 jdbc:sqlite:file:<path>?mode=ro 才行；而 file: URI 又要求对空格等字符转义。
        // 用户数据目录可能包含空格与中文，因此这里不做只读打开，PRAGMA/SELECT 本身不会写库。
        String url = "jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/');
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            // schema_version 不存在说明是全新库或极早版本，按 0 处理
            return 0;
        }
    }

    /** 以只读方式打开任意 SQLite 文件并做完整性 + 外键检查。 */    public IntegrityReport verifyDatabaseFile(Path file) {
        String url = "jdbc:sqlite:" + file.toAbsolutePath().toString().replace('\\', '/') + "?foreign_keys=on";
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            String integrity;
            try (ResultSet rs = statement.executeQuery("PRAGMA integrity_check")) {
                integrity = rs.next() ? rs.getString(1) : "unknown";
            }
            StringBuilder fk = new StringBuilder();
            try (ResultSet rs = statement.executeQuery("PRAGMA foreign_key_check")) {
                while (rs.next()) {
                    fk.append(rs.getString(1)).append(' ');
                }
            }
            int schemaVersion = 0;
            try (ResultSet rs = statement.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_version")) {
                schemaVersion = rs.next() ? rs.getInt(1) : 0;
            }
            boolean ok = "ok".equalsIgnoreCase(integrity) && fk.isEmpty() && schemaVersion > 0;
            return new IntegrityReport(ok, integrity, fk.isEmpty() ? "ok" : fk.toString().trim(), schemaVersion);
        } catch (Exception e) {
            return new IntegrityReport(false, "无法打开数据库：" + e.getMessage(), "unknown", 0);
        }
    }

    /** 端点文件：Electron 主进程据此获得随机端口与会话令牌（开发文档 §2.1）。 */
    public void writeEndpointFile(int port, String sessionToken) {
        Path file = props.resolveEndpointFile();
        var node = json.object();
        node.put("service", "java-api");
        node.put("port", port);
        node.put("baseUrl", "http://127.0.0.1:" + port);
        node.put("token", sessionToken);
        node.put("tokenHeader", "X-Session-Token");
        node.put("pid", ProcessHandle.current().pid());
        node.put("dataDir", props.resolveDataDir().toString());
        node.put("startedAt", ApiResponseTimestamps.now());
        try {
            AtomicWrite.writeString(file, json.writePretty(node));
            log.info("Java 端点文件已写出：{}（端口 {}）", file, port);
        } catch (IOException e) {
            log.warn("端点文件写出失败：{}", e.getMessage());
        }
    }

    /** 供 preload 白名单使用的路径约束：目标是数据目录本身或其子路径。 */
    public boolean isInsideDataDir(Path candidate) {
        return candidate.toAbsolutePath().normalize().startsWith(props.resolveDataDir());
    }

    private void vacuumIntoCurrent(Path source, Path backupTarget) throws Exception {
        String url = "jdbc:sqlite:" + source.toAbsolutePath().toString().replace('\\', '/');
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + backupTarget.toAbsolutePath().toString().replace("'", "''").replace('\\', '/') + "'");
        }
    }

    private void deleteDatabaseFiles(Path target) throws IOException {
        Files.deleteIfExists(target);
        Files.deleteIfExists(target.resolveSibling(target.getFileName() + "-wal"));
        Files.deleteIfExists(target.resolveSibling(target.getFileName() + "-shm"));
    }

    /** 完整性检查结果。 */
    public record IntegrityReport(boolean ok, String integrityCheck, String foreignKeyCheck, int schemaVersion) {
    }

    /** 与 ApiResponse 的时间格式保持一致的小工具，避免循环依赖。 */
    static final class ApiResponseTimestamps {
        static String now() {
            return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        }
    }
}
