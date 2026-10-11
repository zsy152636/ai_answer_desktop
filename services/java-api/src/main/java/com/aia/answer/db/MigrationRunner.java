package com.aia.answer.db;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迁移执行器：以 {@code schema_version} 为权威记录，逐版本执行并把脚本 SHA-256 写入。
 *
 * <p>规则（开发文档 §4.3、§14）：
 * <ul>
 *   <li>已应用版本校验和不一致 → 直接失败，绝不覆盖用户数据；</li>
 *   <li>每个脚本在自己的事务里执行，失败即整体回滚并抛 {@code DB_MIGRATION_FAILED}；</li>
 *   <li><b>失败</b>不提供「跳过继续」的模式，避免半迁移状态。</li>
 * </ul>
 *
 * <p>能力化迁移（开发文档 §10.1）：脚本可在头部声明 {@code -- @requires <capability>}，
 * 例如 FTS5 虚拟表只能在支持 FTS5 的 SQLite 构建上创建。运行期缺少该能力时，
 * 该脚本被**显式跳过且不写入 schema_version**（日志有 WARN），其余脚本照常执行；
 * 一旦运行环境具备该能力，下次启动会自动补上。这与「失败跳过」有本质区别：
 * 跳过的原因是可判定的环境能力，而不是把执行错误掩盖掉。
 */
@Component
public class MigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);
    private static final Pattern FILE_PATTERN = Pattern.compile("V(\\d+)__(.+)\\.sql", Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUIRES_PATTERN =
            Pattern.compile("^\\s*--\\s*@requires\\s+([A-Za-z0-9_-]+)\\s*$", Pattern.MULTILINE);
    private static final String LOCATION = "classpath*:db/migration/V*.sql";

    public int migrate(DataSource dataSource) {
        return migrate(dataSource, SqliteCapabilities.probe(dataSource));
    }

    /**
     * 按给定能力集合执行迁移。
     *
     * @param capabilities 运行期可用能力（见 {@link SqliteCapabilities}）；测试可显式传入以验证跳过路径
     */
    public int migrate(DataSource dataSource, Set<String> capabilities) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                    + "version INTEGER PRIMARY KEY, description TEXT NOT NULL DEFAULT '', "
                    + "applied_at TEXT NOT NULL, checksum TEXT NOT NULL)");
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.DB_MIGRATION_FAILED, "无法初始化 schema_version 表：" + rootMessage(e));
        }

        Map<Integer, String> applied = new HashMap<>();
        jdbc.query("SELECT version, checksum FROM schema_version", rs -> {
            applied.put(rs.getInt("version"), rs.getString("checksum"));
        });

        List<MigrationScript> scripts = loadScripts();
        int executed = 0;
        int skipped = 0;
        int currentVersion = applied.keySet().stream().max(Integer::compareTo).orElse(0);
        for (MigrationScript script : scripts) {
            Set<String> missing = missingCapabilities(script, capabilities);
            if (!missing.isEmpty()) {
                skipped++;
                if (applied.get(script.version()) == null) {
                    log.warn("跳过迁移 V{}（{}）：当前 SQLite 构建缺少能力 {}，该脚本未记入 schema_version，"
                                    + "环境具备能力后会自动补上", script.version(), script.description(), missing);
                }
                continue;
            }
            String existing = applied.get(script.version());
            if (existing != null) {
                if (!existing.equalsIgnoreCase(script.checksum())) {
                    throw new ApiException(ErrorCode.DB_MIGRATION_FAILED,
                            "迁移脚本 V" + script.version() + " 已被修改（校验和不一致），已停止启动以保护现有数据");
                }
                continue;
            }
            applyScript(dataSource, script);
            executed++;
            currentVersion = Math.max(currentVersion, script.version());
        }
        // 「当前 schema 版本」以实际记录为准：被能力跳过的脚本不算已应用
        log.info("数据库迁移完成：本次执行 {} 个脚本，跳过 {} 个（能力不满足），当前 schema 版本 {}，数据文件 {}",
                executed, skipped, currentVersion, "app.db");
        return currentVersion;
    }

    public int currentVersion(JdbcTemplate jdbc) {
        Integer v = jdbc.queryForObject("SELECT COALESCE(MAX(version),0) FROM schema_version", Integer.class);
        return v == null ? 0 : v;
    }

    /** 迁移脚本的最高版本（含能力不满足的脚本），用于判断「本次启动是否有待执行迁移」。 */
    public int latestScriptVersion() {
        return loadScripts().stream().mapToInt(MigrationScript::version).max().orElse(0);
    }

    /**
     * 在当前能力下真正会执行到的最高版本。
     *
     * <p>迁移前备份要按这个值判断，否则「有脚本被能力跳过」会让每次启动都误判为有待执行迁移。
     */
    public int latestApplicableScriptVersion(Set<String> capabilities) {
        return loadScripts().stream()
                .filter(script -> missingCapabilities(script, capabilities).isEmpty())
                .mapToInt(MigrationScript::version)
                .max()
                .orElse(0);
    }

    private static Set<String> missingCapabilities(MigrationScript script, Set<String> capabilities) {
        if (script.requires().isEmpty()) {
            return Set.of();
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String required : script.requires()) {
            if (!capabilities.contains(required)) {
                missing.add(required);
            }
        }
        return missing;
    }

    private void applyScript(DataSource dataSource, MigrationScript script) {
        // 注意：整个脚本 + schema_version 记录必须走同一条连接。
        // 若在持写事务时再从连接池取第二条连接写 schema_version，SQLite 会直接报 SQLITE_BUSY。
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ScriptUtils.executeSqlScript(connection,
                        new EncodedResource(new ClassPathResource(script.path()), StandardCharsets.UTF_8));
                try (var statement = connection.prepareStatement(
                        "INSERT INTO schema_version(version, description, applied_at, checksum) VALUES (?,?,?,?)")) {
                    statement.setInt(1, script.version());
                    statement.setString(2, script.description());
                    statement.setString(3, nowUtc());
                    statement.setString(4, script.checksum());
                    statement.executeUpdate();
                }
                connection.commit();
                log.info("已应用迁移 V{}（{}）", script.version(), script.description());
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // 回滚失败时仍以原始异常为准
                }
                throw new ApiException(ErrorCode.DB_MIGRATION_FAILED,
                        "迁移 V" + script.version() + " 执行失败：" + rootMessage(e));
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new ApiException(ErrorCode.DB_MIGRATION_FAILED, "迁移 V" + script.version() + " 连接失败：" + rootMessage(e));
        }
    }

    private List<MigrationScript> loadScripts() {
        List<MigrationScript> scripts = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(LOCATION);
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                Matcher matcher = FILE_PATTERN.matcher(filename);
                if (!matcher.matches()) {
                    continue;
                }
                int version = Integer.parseInt(matcher.group(1));
                String description = matcher.group(2).replace('_', ' ');
                byte[] bytes = resource.getInputStream().readAllBytes();
                String checksum = sha256(bytes);
                String path = "db/migration/" + filename;
                scripts.add(new MigrationScript(version, description, checksum, path, requiresOf(bytes)));
            }
        } catch (IOException e) {
            throw new ApiException(ErrorCode.DB_MIGRATION_FAILED, "无法读取迁移脚本：" + rootMessage(e));
        }
        scripts.sort(Comparator.comparingInt(MigrationScript::version));
        if (scripts.isEmpty()) {
            throw new ApiException(ErrorCode.DB_MIGRATION_FAILED, "未找到任何迁移脚本（db/migration/V*.sql）");
        }
        return scripts;
    }

    /** 解析脚本头部的能力声明（{@code -- @requires <capability>}，可多行）。 */
    private static Set<String> requiresOf(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        Matcher matcher = REQUIRES_PATTERN.matcher(text);
        Set<String> requires = new LinkedHashSet<>();
        while (matcher.find()) {
            requires.add(matcher.group(1).toLowerCase());
        }
        return Set.copyOf(requires);
    }

    private static String sha256(byte[] bytes) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest(bytes)) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nowUtc() {
        return java.time.format.DateTimeFormatter.ISO_INSTANT
                .format(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    private static String rootMessage(Throwable t) {
        Throwable cursor = t;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null ? cursor.getClass().getSimpleName() : message;
    }

    private record MigrationScript(int version, String description, String checksum, String path,
                                   Set<String> requires) {
    }
}
