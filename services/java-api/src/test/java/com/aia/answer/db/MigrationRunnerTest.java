package com.aia.answer.db;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.ErrorCode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 迁移执行器集成测试（开发文档 §12.1「Java 集成测试：SQLite DDL、外键、迁移」）。
 *
 * <p>刻意使用**真实的临时 SQLite 文件**而不是内存库：内存库不体现 WAL、文件锁与
 * 重启后的行为差异，而这些正是迁移最容易出问题的地方。
 */
class MigrationRunnerTest {

    private final MigrationRunner runner = new MigrationRunner();

    private DataSource dataSource(Path databaseFile) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(DataSourceConfig.jdbcUrl(databaseFile));
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(4);
        config.setConnectionTestQuery("SELECT 1");
        return new HikariDataSource(config);
    }

    @Test
    @DisplayName("空库迁移成功，schema_version 记录校验和，核心表与外键生效")
    void migratesEmptyDatabase(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("app.db");
        DataSource dataSource = dataSource(db);
        int version = runner.migrate(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(version).isGreaterThanOrEqualTo(1);
        // 记录数等于「当前能力下真正会执行的脚本数」：被能力跳过的脚本不写入 schema_version
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schema_version", Integer.class))
                .isEqualTo(runner.latestApplicableScriptVersion(SqliteCapabilities.probe(dataSource)));
        String checksum = jdbc.queryForObject("SELECT checksum FROM schema_version WHERE version = 1", String.class);
        assertThat(checksum).hasSize(64);

        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", String.class);
        assertThat(tables).contains("question", "question_version", "question_option", "paper", "paper_question",
                "paper_snapshot", "paper_snapshot_question", "attempt", "attempt_answer", "wrong_question",
                "wrong_question_review", "async_task", "llm_profile", "idempotency_record",
                // 阶段 6：资料库普通表
                "imported_file", "document", "document_chunk", "index_state");

        // 外键必须真的生效（连接 URL 上的 foreign_keys=on）
        Integer foreignKeys = jdbc.queryForObject("PRAGMA foreign_keys", Integer.class);
        assertThat(foreignKeys).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO question_version(id, question_id, version_no, stem, answer_json, content_hash, created_at) "
                        + "VALUES ('qv_x','q_missing',1,'s','{}','h','2026-01-01T00:00:00Z')"))
                .hasMessageContaining("FOREIGN KEY");

        // CHECK 约束同样生效
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO question(id, title, question_type, status, difficulty, created_at, updated_at) "
                        + "VALUES ('q_bad','t','unknown_type','draft',1,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')"))
                .hasMessageContaining("CHECK");

        // 资料表同样有 CHECK 与唯一约束：同一 sha256 只允许一条文件记录
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO document(id, title, file_id, status, source_kind, created_at, updated_at) "
                        + "VALUES ('doc_bad','t','file_missing','unknown','text','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')"))
                .hasMessageContaining("CHECK");

        ((HikariDataSource) dataSource).close();
    }

    @Test
    @DisplayName("重复迁移幂等：第二次执行不重复应用，也不报错")
    void migrationIsIdempotent(@TempDir Path tempDir) {
        Path db = tempDir.resolve("app.db");
        DataSource dataSource = dataSource(db);
        Set<String> capabilities = SqliteCapabilities.probe(dataSource);
        assertThat(runner.migrate(dataSource)).isEqualTo(runner.latestApplicableScriptVersion(capabilities));
        assertThat(runner.migrate(dataSource)).isEqualTo(runner.latestApplicableScriptVersion(capabilities));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schema_version", Integer.class))
                .isEqualTo(runner.latestApplicableScriptVersion(capabilities));
        ((HikariDataSource) dataSource).close();
    }

    @Test
    @DisplayName("迁移脚本被篡改时拒绝启动，绝不覆盖已有数据")
    void rejectsTamperedChecksum(@TempDir Path tempDir) {
        Path db = tempDir.resolve("app.db");
        DataSource dataSource = dataSource(db);
        runner.migrate(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE schema_version SET checksum = 'deadbeef' WHERE version = 1");

        assertThatThrownBy(() -> runner.migrate(dataSource))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.DB_MIGRATION_FAILED))
                .hasMessageContaining("校验和不一致");
        ((HikariDataSource) dataSource).close();
    }

    @Test
    @DisplayName("备份前检查：有数据库文件且存在待执行迁移时才需要备份")
    void detectsPendingMigration(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("app.db");
        assertThat(Files.exists(db)).isFalse();
        DataSource dataSource = dataSource(db);
        runner.migrate(dataSource);
        ((HikariDataSource) dataSource).close();
        assertThat(Files.exists(db)).isTrue();
        // 迁移已到最新：脚本集固定为 V1-V3，latestScriptVersion 不应再增长
        assertThat(runner.latestScriptVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("能力化迁移：缺少 FTS5 时跳过 V3 且不记入 schema_version，能力具备后自动补上")
    void skipsCapabilityGatedMigration(@TempDir Path tempDir) {
        Path db = tempDir.resolve("app.db");
        DataSource dataSource = dataSource(db);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // 1) 模拟不支持 FTS5 的 SQLite 构建
        int versionWithoutFts = runner.migrate(dataSource, Set.of());
        assertThat(versionWithoutFts).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schema_version", Integer.class)).isEqualTo(2);
        assertThat(tableExists(jdbc, "document_fts")).isFalse();
        // 普通表照常可用：检索降级为关键词实现的前提
        assertThat(tableExists(jdbc, "document_chunk")).isTrue();
        assertThat(runner.latestApplicableScriptVersion(Set.of())).isEqualTo(2);
        assertThat(runner.latestApplicableScriptVersion(Set.of(SqliteCapabilities.FTS5_TRIGRAM))).isEqualTo(3);

        // 2) 能力具备后（本次用真实探测）自动补上 V3
        int versionWithFts = runner.migrate(dataSource);
        assertThat(versionWithFts).isEqualTo(runner.latestApplicableScriptVersion(SqliteCapabilities.probe(dataSource)));
        assertThat(tableExists(jdbc, "document_fts")).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schema_version", Integer.class))
                .isEqualTo(runner.latestApplicableScriptVersion(SqliteCapabilities.probe(dataSource)));

        // 3) FTS5 表可用且带 chunk_id 列（检索按 chunk_id 关联原文分块）
        jdbc.update("INSERT INTO document_fts(content, chunk_id) VALUES ('三次握手','chk_probe')");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document_fts WHERE document_fts MATCH '三次握手'",
                Integer.class)).isEqualTo(1);
        ((HikariDataSource) dataSource).close();
    }

    private static boolean tableExists(JdbcTemplate jdbc, String name) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE name = ?", Integer.class, name);
        return count != null && count > 0;
    }
}
