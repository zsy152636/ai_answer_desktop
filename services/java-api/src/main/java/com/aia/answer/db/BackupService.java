package com.aia.answer.db;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.AppInfo;
import com.aia.answer.common.AtomicWrite;
import com.aia.answer.common.ErrorCode;
import com.aia.answer.common.Json;
import com.aia.answer.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 备份与恢复校验（开发文档 §1.1「SQLite 数据库迁移、自动备份、恢复校验」）。
 *
 * <p>备份使用 SQLite 原生 {@code VACUUM INTO}：在 WAL 模式下同样生成一致性快照，
 * 不需要停机，也不依赖文件复制。恢复分两步：这里只做校验与请求登记，
 * 真正的替换发生在下次启动、连接池打开之前（{@link DatabaseBootstrap#applyPendingRestore()}）。
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC);

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final Json json;
    private final DatabaseBootstrap bootstrap;

    public BackupService(DataSource dataSource, AppProperties props, Json json, DatabaseBootstrap bootstrap) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.props = props;
        this.json = json;
        this.bootstrap = bootstrap;
    }

    /** 立即备份。kind 仅用于文件名标记：manual / auto / pre-migration。 */
    public BackupInfo backupNow(String kind) {
        try {
            Files.createDirectories(props.backupsDir());
            String fileName = "app-" + STAMP.format(Instant.now())
                    + ("manual".equals(kind) ? "" : "-" + kind) + ".db";
            Path target = props.backupsDir().resolve(fileName);
            int suffix = 1;
            while (Files.exists(target)) {
                target = props.backupsDir().resolve(fileName.replace(".db", "-" + (suffix++) + ".db"));
            }
            jdbc.execute("VACUUM INTO '" + sqlLiteral(target) + "'");

            String checksum = AtomicWrite.sha256(target);
            long size = Files.size(target);
            DatabaseBootstrap.IntegrityReport report = bootstrap.verifyDatabaseFile(target);
            if (!report.ok()) {
                Files.deleteIfExists(target);
                throw new ApiException(ErrorCode.INTERNAL_ERROR,
                        "备份未通过完整性校验，已删除该文件（integrity=" + report.integrityCheck()
                                + ", foreignKey=" + report.foreignKeyCheck() + ", schema=" + report.schemaVersion() + "）");
            }
            BackupInfo info = new BackupInfo(target.getFileName().toString(), size, checksum,
                    nowUtc(), report.schemaVersion(), AppInfo.VERSION, true, kind);
            writeMeta(info);
            pruneOld();
            log.info("数据库备份完成：{}（{} 字节，schema {}）", info.fileName(), size, report.schemaVersion());
            return info;
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "备份失败：" + e.getMessage());
        }
    }

    public List<BackupInfo> list() {
        Path dir = props.backupsDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<BackupInfo> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".db"))
                    .filter(Files::isRegularFile)
                    .forEach(p -> result.add(readInfo(p)));
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "读取备份目录失败：" + e.getMessage());
        }
        result.sort(Comparator.comparing(BackupInfo::createdAt).reversed());
        return result;
    }

    public Optional<BackupInfo> find(String fileName) {
        return list().stream().filter(b -> b.fileName().equals(fileName)).findFirst();
    }

    public Path resolveBackup(String fileName) {
        Path dir = props.backupsDir();
        Path candidate = dir.resolve(fileName == null ? "" : fileName).normalize();
        if (fileName == null || fileName.isBlank() || !candidate.startsWith(dir) || !Files.isRegularFile(candidate)) {
            throw ApiException.notFound("备份文件 " + fileName);
        }
        return candidate;
    }

    /** 只读校验指定备份：完整性、外键、schema 版本。 */
    public DatabaseBootstrap.IntegrityReport verify(String fileName) {
        return bootstrap.verifyDatabaseFile(resolveBackup(fileName));
    }

    /** 当前数据库文件大小，用于系统信息页展示。 */
    public long databaseSizeBytes() {
        try {
            Path db = props.databasePath();
            if (!Files.isRegularFile(db)) {
                return 0L;
            }
            long size = Files.size(db);
            Path wal = db.resolveSibling(db.getFileName() + "-wal");
            if (Files.isRegularFile(wal)) {
                size += Files.size(wal);
            }
            return size;
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 记录恢复请求，返回待恢复的备份信息。 */
    public BackupInfo requestRestore(String fileName) {
        Path backup = resolveBackup(fileName);
        DatabaseBootstrap.IntegrityReport report = bootstrap.verifyDatabaseFile(backup);
        if (!report.ok()) {
            throw new ApiException(ErrorCode.STATE_CONFLICT,
                    "备份未通过完整性校验，拒绝恢复：" + report.integrityCheck());
        }
        BackupInfo info = readInfo(backup);
        var node = json.object();
        node.put("fileName", info.fileName());
        node.put("requestedAt", nowUtc());
        node.put("checksum", info.checksum());
        node.put("schemaVersion", info.schemaVersion());
        try {
            AtomicWrite.writeString(props.restoreRequestFile(), json.writePretty(node));
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "无法写入恢复请求：" + e.getMessage());
        }
        return info;
    }

    private void pruneOld() {
        int keep = Math.max(1, props.getBackupRetentionCount());
        List<BackupInfo> all = list();
        for (int i = keep; i < all.size(); i++) {
            BackupInfo info = all.get(i);
            try {
                Files.deleteIfExists(props.backupsDir().resolve(info.fileName()));
                Files.deleteIfExists(props.backupsDir().resolve(info.fileName() + ".meta.json"));
                log.info("按保留策略清理旧备份：{}", info.fileName());
            } catch (IOException e) {
                log.warn("清理旧备份失败：{}", info.fileName());
            }
        }
    }

    private BackupInfo readInfo(Path file) {
        Path meta = file.resolveSibling(file.getFileName() + ".meta.json");
        Optional<String> raw = AtomicWrite.readString(meta);
        if (raw.isPresent()) {
            var node = json.readTreeOrNull(raw.get());
            if (node != null) {
                return new BackupInfo(
                        file.getFileName().toString(),
                        node.path("sizeBytes").asLong(file.toFile().length()),
                        node.path("checksum").asText(""),
                        node.path("createdAt").asText(nowUtc()),
                        node.path("schemaVersion").asInt(0),
                        node.path("appVersion").asText(AppInfo.VERSION),
                        true,
                        node.path("kind").asText("unknown"));
            }
        }
        try {
            return new BackupInfo(file.getFileName().toString(), Files.size(file),
                    AtomicWrite.sha256(file),
                    DateTimeFormatter.ISO_INSTANT.format(Files.getLastModifiedTime(file).toInstant()
                            .truncatedTo(ChronoUnit.SECONDS)),
                    0, AppInfo.VERSION, true, "unknown");
        } catch (IOException e) {
            return new BackupInfo(file.getFileName().toString(), 0L, "", nowUtc(), 0, AppInfo.VERSION, false, "unknown");
        }
    }

    private void writeMeta(BackupInfo info) {
        Path meta = props.backupsDir().resolve(info.fileName() + ".meta.json");
        var node = json.object();
        node.put("fileName", info.fileName());
        node.put("sizeBytes", info.sizeBytes());
        node.put("checksum", info.checksum());
        node.put("createdAt", info.createdAt());
        node.put("schemaVersion", info.schemaVersion());
        node.put("appVersion", info.appVersion());
        node.put("kind", info.kind());
        try {
            AtomicWrite.writeString(meta, json.writePretty(node));
        } catch (IOException e) {
            log.warn("备份元数据写出失败：{}", e.getMessage());
        }
    }

    private String sqlLiteral(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
    }

    private static String nowUtc() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    /** 备份文件元信息（契约 §8）。 */
    public record BackupInfo(String fileName, long sizeBytes, String checksum, String createdAt,
                             int schemaVersion, String appVersion, boolean integrityOk, String kind) {
    }
}
