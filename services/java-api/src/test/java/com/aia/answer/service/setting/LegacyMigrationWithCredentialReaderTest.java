package com.aia.answer.service.setting;

import com.aia.answer.common.Json;
import com.aia.answer.config.AppProperties;
import com.aia.answer.db.MigrationRunner;
import com.aia.answer.repository.LlmProfileRepository;
import com.aia.answer.repository.MetaRepository;
import com.aia.answer.service.setting.legacy.LegacyCredentialReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.0 → v1.1 一次性迁移与「旧密钥读取点」的联调测试。
 *
 * <p>用真实临时 SQLite + 真实旧凭据文件，验证两件事：
 * <ol>
 *   <li>旧 {@code llm_profile} 有数据且无 setting.json 时，迁移会去读旧密钥并写进 setting.json
 *       （证明 {@link LegacyCredentialReader} 这条读取路径真的被调用）；</li>
 *   <li>旧密钥读不到时迁移照样完成、不抛异常，只在审计里记「N 个 profile 的密钥需手工重填」。</li>
 * </ol>
 * 不发任何网络请求。
 */
class LegacyMigrationWithCredentialReaderTest {

    private static final String NOW = "2026-01-01T00:00:00Z";
    private static final String REFERENCE = "credential://llm/p1";

    @Test
    @DisplayName("旧表有数据 + 无 setting.json：读取旧密钥并写进 setting.json")
    void migrationReadsLegacySecretThroughReader(@TempDir Path dataDir) throws IOException {
        Fixture fixture = new Fixture(dataDir);
        fixture.insertLegacyProfile("p1", true);
        // 旧文件后端的落盘形态：secrets/<safe(ref)>.secret
        Path secrets = Files.createDirectories(dataDir.resolve("secrets"));
        Files.writeString(secrets.resolve(REFERENCE.replaceAll("[^A-Za-z0-9._-]", "_") + ".secret"),
                "sk-legacy-migrated", StandardCharsets.UTF_8);

        LegacyCredentialReader reader = new LegacyCredentialReader(dataDir);
        boolean migrated = fixture.migrator(reader).migrateIfNeeded();

        assertThat(migrated).isTrue();
        JsonNode config = fixture.readConfig();
        assertThat(config.path("llm").path("defaultProfileId").asText()).isEqualTo("p1");
        JsonNode profile = config.path("llm").path("profiles").get(0);
        assertThat(profile.path("apiKey").asText()).as("迁移必须走 LegacyCredentialReader 读到旧密钥")
                .isEqualTo("sk-legacy-migrated");
        // 旧实现内部细节不得出现在新配置里
        assertThat(config.toString()).doesNotContain("credential://");
        fixture.close();
    }

    @Test
    @DisplayName("旧密钥读不到也不失败：不写 apiKey，审计里记待重填数量（不含密钥与引用）")
    void migrationSucceedsWhenSecretCannotBeRead(@TempDir Path dataDir) {
        Fixture fixture = new Fixture(dataDir);
        fixture.insertLegacyProfile("p1", true);

        // 数据目录里没有任何 secrets/：读取点返回空
        boolean migrated = fixture.migrator(new LegacyCredentialReader(dataDir)).migrateIfNeeded();

        assertThat(migrated).isTrue();
        JsonNode profile = fixture.readConfig().path("llm").path("profiles").get(0);
        assertThat(profile.has("apiKey")).isFalse();
        String detail = fixture.jdbc().queryForObject(
                "SELECT detail_json FROM audit_event WHERE event_type = 'llm_config.migrated'", String.class);
        assertThat(detail).contains("\"warningCount\":1").doesNotContain("credential://");
        // 旧表数据必须原样保留（不 DROP、不清空）
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM llm_profile", Integer.class)).isEqualTo(1);
        fixture.close();
    }

    // ------------------------------------------------------------ 夹具

    private static final class Fixture implements AutoCloseable {

        private final HikariDataSource dataSource;
        private final JdbcTemplate jdbc;
        private final LlmProfileRepository profiles;
        private final MetaRepository meta;
        private final SettingStore store;
        private final Json json = SettingTestSupport.json();
        private final AppProperties props;

        private Fixture(Path dataDir) {
            Path database = dataDir.resolve("database").resolve("app.db");
            try {
                Files.createDirectories(database.getParent());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/')
                    + "?foreign_keys=on");
            config.setDriverClassName("org.sqlite.JDBC");
            config.setMaximumPoolSize(2);
            config.setConnectionTestQuery("SELECT 1");
            this.dataSource = new HikariDataSource(config);
            new MigrationRunner().migrate(dataSource);
            this.jdbc = new JdbcTemplate(dataSource);
            this.profiles = new LlmProfileRepository(jdbc);
            this.meta = new MetaRepository(jdbc);
            this.props = SettingTestSupport.properties(dataDir);
            this.store = new SettingStore(props, json);
        }

        private void insertLegacyProfile(String id, boolean isDefault) {
            profiles.insert(new LlmProfileRepository.ProfileRow(id, "旧档案", "ollama",
                    "http://127.0.0.1:11434/v1", "qwen2.5:7b", "credential://llm/" + id, 60, 5, false, "{}",
                    true, isDefault, NOW, NOW));
            profiles.ensureStateRow(NOW);
            if (isDefault) {
                profiles.setState(id, NOW);
            }
        }

        private LegacyLlmConfigMigrator migrator(LegacySecretExporter exporter) {
            return new LegacyLlmConfigMigrator(profiles, meta, store, json, exporter);
        }

        private JsonNode readConfig() {
            try {
                return json.mapper().readTree(Files.readString(props.settingFile(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private JdbcTemplate jdbc() {
            return jdbc;
        }

        @Override
        public void close() {
            dataSource.close();
        }
    }
}
