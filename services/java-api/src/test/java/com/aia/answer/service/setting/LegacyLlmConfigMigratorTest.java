package com.aia.answer.service.setting;

import com.aia.answer.common.Json;
import com.aia.answer.config.AppProperties;
import com.aia.answer.db.MigrationRunner;
import com.aia.answer.repository.LlmProfileRepository;
import com.aia.answer.repository.MetaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 首次启动迁移测试（保留旧表 + 自动迁移）。
 *
 * <p>用<b>真实临时 SQLite 文件</b>而不是内存库：迁移读的是旧表真实列与真实行，
 * 内存库体现不出列名/约束差异。不发任何网络请求。
 */
class LegacyLlmConfigMigratorTest {

    private static final String NOW = "2026-01-01T00:00:00Z";

    @Test
    @DisplayName("旧表有数据且无 setting.json：生成完整配置（含 provider/model/默认档案）")
    void migratesLegacyRows(@TempDir Path dataDir) {
        Fixture fixture = fixture(dataDir);
        fixture.insertProfile("p1", "本地 Ollama", "ollama", "http://127.0.0.1:11434/v1", "qwen2.5:7b", true, true);
        fixture.insertProfile("p2", "云端", "openai-compatible", "https://api.example.com/v1", "gpt-4o-mini",
                false, false);
        boolean migrated = fixture.migrator(reference -> Optional.of("sk-migrated-" + reference.length()))
                .migrateIfNeeded();

        assertThat(migrated).isTrue();
        assertThat(Files.exists(fixture.settingFile())).isTrue();
        JsonNode config = fixture.readConfig();
        assertThat(config.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(config.path("llm").path("defaultProfileId").asText()).isEqualTo("p1");
        assertThat(config.path("llm").path("profiles")).hasSize(2);
        JsonNode first = config.path("llm").path("profiles").get(0);
        assertThat(first.path("id").asText()).isEqualTo("p1");
        assertThat(first.path("provider").asText()).isEqualTo("ollama");
        assertThat(first.path("baseUrl").asText()).isEqualTo("http://127.0.0.1:11434/v1");
        assertThat(first.path("model").asText()).isEqualTo("qwen2.5:7b");
        assertThat(first.path("allowLocalNetwork").asBoolean()).isTrue();
        assertThat(first.path("apiKey").asText()).startsWith("sk-migrated-");

        // 迁移标记与审计事件都必须落库，且审计详情里不含密钥
        JdbcTemplate jdbc = fixture.jdbc();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_meta WHERE key = ?", Integer.class,
                LegacyLlmConfigMigrator.MIGRATED_AT_KEY)).isEqualTo(1);
        String detail = jdbc.queryForObject(
                "SELECT detail_json FROM audit_event WHERE event_type = 'llm_config.migrated'", String.class);
        assertThat(detail).contains("\"profiles\":2").doesNotContain("sk-migrated");
        fixture.close();
    }

    @Test
    @DisplayName("setting.json 已存在：不覆盖，直接让路")
    void neverOverwritesExistingSettingFile(@TempDir Path dataDir) throws Exception {
        Fixture fixture = fixture(dataDir);
        fixture.insertProfile("p1", "旧档案", "ollama", "http://127.0.0.1:11434/v1", "qwen2.5:7b", true, true);
        String existing = SettingTestSupport.pretty(SettingTestSupport.validConfig("user-p", "user-model", null));
        SettingTestSupport.writeRaw(fixture.settingFile(), existing);

        boolean migrated = fixture.migrator(reference -> Optional.of("sk-ignored")).migrateIfNeeded();

        assertThat(migrated).isFalse();
        assertThat(Files.readString(fixture.settingFile())).isEqualTo(existing);
        assertThat(fixture.readConfig().path("llm").path("profiles").get(0).path("model").asText())
                .isEqualTo("user-model");
        fixture.close();
    }

    @Test
    @DisplayName("旧表为空：不生成 setting.json（不凭空造配置）")
    void skipsWhenNoLegacyRows(@TempDir Path dataDir) {
        Fixture fixture = fixture(dataDir);

        boolean migrated = fixture.migrator(reference -> Optional.empty()).migrateIfNeeded();

        assertThat(migrated).isFalse();
        assertThat(Files.exists(fixture.settingFile())).isFalse();
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM app_meta WHERE key = ?", Integer.class,
                LegacyLlmConfigMigrator.MIGRATED_AT_KEY)).isZero();
        fixture.close();
    }

    @Test
    @DisplayName("幂等：同一实例第二次调用、以及写完之后再调用都不重复写")
    void migrationIsIdempotent(@TempDir Path dataDir) throws Exception {
        Fixture fixture = fixture(dataDir);
        fixture.insertProfile("p1", "本地", "ollama", "http://127.0.0.1:11434/v1", "qwen2.5:7b", true, false);
        LegacyLlmConfigMigrator migrator = fixture.migrator(reference -> Optional.empty());

        assertThat(migrator.migrateIfNeeded()).isTrue();
        String firstRevision = SettingStore.sha256(fixture.settingFile());
        // 第二次调用：本进程已尝试过，直接返回
        assertThat(migrator.migrateIfNeeded()).isFalse();
        // 换一个实例模拟「下一次启动」：文件已存在，同样不写
        assertThat(fixture.migrator(reference -> Optional.empty()).migrateIfNeeded()).isFalse();
        assertThat(SettingStore.sha256(fixture.settingFile())).isEqualTo(firstRevision);
        // 旧表数据必须原样保留
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM llm_profile", Integer.class)).isEqualTo(1);
        fixture.close();
    }

    @Test
    @DisplayName("读不到密钥的档案不写 apiKey，新配置里也不出现凭据引用")
    void reportsProfilesWhoseSecretsCannotBeExported(@TempDir Path dataDir) throws Exception {
        Fixture fixture = fixture(dataDir);
        fixture.insertProfile("p1", "有密钥", "ollama", "http://127.0.0.1:11434/v1", "qwen2.5:7b", true, true);

        // 凭据后端读不到（后续切片删除凭据包后就是这个形态）
        assertThat(fixture.migrator(reference -> Optional.empty()).migrateIfNeeded()).isTrue();

        JsonNode profile = fixture.readConfig().path("llm").path("profiles").get(0);
        assertThat(profile.has("apiKey")).isFalse();
        assertThat(profile.path("id").asText()).isEqualTo("p1");
        // 凭据引用是旧实现的内部细节：不写进新配置
        assertThat(fixture.readConfig().toString()).doesNotContain("credential://");
        fixture.close();
    }

    // ------------------------------------------------------------ 夹具

    /** 真实 SQLite 临时库 + 已迁移 schema + 旧 LLM 仓储。 */
    private static Fixture fixture(Path dataDir) {
        Path database = dataDir.resolve("database").resolve("app.db");
        try {
            Files.createDirectories(database.getParent());
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Fixture(dataDir, database);
    }

    private static final class Fixture implements AutoCloseable {

        private final HikariDataSource dataSource;
        private final JdbcTemplate jdbc;
        private final LlmProfileRepository profiles;
        private final MetaRepository meta;
        private final SettingStore store;
        private final Json json = SettingTestSupport.json();
        private final AppProperties props;

        private Fixture(Path dataDir, Path database) {
            HikariConfig config = new HikariConfig();
            // 只保留真实业务库需要的 foreign_keys=on；WAL 等 PRAGMA 与本次迁移无关
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

        private void insertProfile(String id, String name, String provider, String baseUrl, String model,
                                   boolean isDefault, boolean allowLocalNetwork) {
            profiles.insert(new LlmProfileRepository.ProfileRow(id, name, provider, baseUrl, model,
                    "credential://llm/" + id, 60, 5, allowLocalNetwork, "{}", allowLocalNetwork, isDefault,
                    NOW, NOW));
            profiles.ensureStateRow(NOW);
            if (isDefault) {
                profiles.setState(id, NOW);
            }
        }

        private LegacyLlmConfigMigrator migrator(LegacySecretExporter exporter) {
            return new LegacyLlmConfigMigrator(profiles, meta, store, json, exporter);
        }

        private Path settingFile() {
            return props.settingFile();
        }

        private JdbcTemplate jdbc() {
            return jdbc;
        }

        private JsonNode readConfig() {
            try {
                return SettingTestSupport.parse(settingFile());
            } catch (java.io.IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            dataSource.close();
        }
    }
}
