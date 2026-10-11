package com.aia.answer.db;

import com.aia.answer.config.AppProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;

/**
 * 手工装配 SQLite 数据源。
 *
 * <p>顺序是硬约束：目录准备 → 待恢复请求 → 迁移前备份 → 建池 → 迁移。迁移必须在任何请求被处理前完成，
 * 因此放在 {@code @Bean} 方法体内而不是事件监听器里。
 *
 * <p>PRAGMA 基线（开发文档 §4.2）：{@code foreign_keys=ON}（每个连接都开）、
 * {@code busy_timeout=5000}、{@code journal_mode=WAL}、{@code synchronous=NORMAL}。
 * WAL 的启用前置条件是备份/恢复校验已通过（见 {@link BackupService#verify(String)} 与
 * {@link DatabaseBootstrap#verifyDatabaseFile(Path)}），此约束记录于 docs/architecture/decisions.md。
 *
 * <p>迁移前备份必须早于连接池建立（ADR-006），因此能力探测在池外先用一条独立连接完成；
 * 迁移阶段再按池内连接复探一次，保证与实际执行环境一致。
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean(destroyMethod = "close")
    public DataSource dataSource(AppProperties props, DatabaseBootstrap bootstrap, MigrationRunner migration) {
        bootstrap.createDirectories();
        bootstrap.applyPendingRestore();
        // ADR-006：迁移前备份必须在连接池建立之前完成，此时只能用独立连接探测能力
        Set<String> preRebootCapabilities = probeBeforePool(props.databasePath());
        bootstrap.backupBeforeMigrationIfNeeded(migration.latestApplicableScriptVersion(preRebootCapabilities));

        HikariConfig config = new HikariConfig();
        config.setPoolName("aia-sqlite");
        config.setJdbcUrl(jdbcUrl(props.databasePath()));
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(1);
        config.setAutoCommit(true);
        config.setConnectionTimeout(15_000);
        config.setIdleTimeout(120_000);
        config.setMaxLifetime(600_000);
        config.setConnectionTestQuery("SELECT 1");

        HikariDataSource dataSource = new HikariDataSource(config);
        Set<String> capabilities = SqliteCapabilities.probe(dataSource);
        log.info("SQLite 能力探测：{}", capabilities.isEmpty() ? "（无附加能力）" : capabilities);
        migration.migrate(dataSource, capabilities);
        log.info("SQLite 数据源就绪：{}", props.databasePath());
        return dataSource;
    }

    /**
     * 建池前的能力探测。
     *
     * <p>刻意不加 WAL 等 PRAGMA：探测发生在迁移前备份之前，不应改变数据库的日志模式。
     * 数据库文件尚不存在时直接返回空集合——此时 {@code backupBeforeMigrationIfNeeded} 会提前返回，
     * 不会因为能力集合为空而多做一次备份。
     */
    private static Set<String> probeBeforePool(Path databasePath) {
        if (!Files.isRegularFile(databasePath)) {
            return Set.of();
        }
        String url = "jdbc:sqlite:" + databasePath.toAbsolutePath().toString().replace('\\', '/');
        try (Connection connection = DriverManager.getConnection(url)) {
            return SqliteCapabilities.probe(connection);
        } catch (SQLException e) {
            log.warn("建池前能力探测失败，按无附加能力处理：{}", e.getMessage());
            return Set.of();
        }
    }

    static String jdbcUrl(Path databasePath) {
        String normalized = databasePath.toAbsolutePath().toString().replace('\\', '/');
        return "jdbc:sqlite:" + normalized
                + "?foreign_keys=on"
                + "&journal_mode=WAL"
                + "&synchronous=NORMAL"
                + "&busy_timeout=5000";
    }
}
