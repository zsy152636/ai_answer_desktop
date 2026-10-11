package com.aia.answer.service;

import com.aia.answer.common.Json;
import com.aia.answer.config.AppProperties;
import com.aia.answer.db.MigrationRunner;
import com.aia.answer.domain.LlmDtos;
import com.aia.answer.repository.MetaRepository;
import com.aia.answer.service.setting.ConfigService;
import com.aia.answer.service.setting.LegacyLlmConfigMigrator;
import com.aia.answer.service.setting.LegacySecretExporter;
import com.aia.answer.service.setting.SettingStore;
import com.aia.answer.service.setting.SettingValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件驱动 LLM 配置在<b>真实 SQLite</b> 上的持久化边界测试。
 *
 * <p>核心主张：v1.1 起运行期不再读写 {@code llm_profile} / {@code llm_config_state} /
 * {@code llm_import_preview}。这里用最直接的方式证明它 —— 迁移出真实库后
 * <b>把 {@code llm_import_preview} 整张表 DROP 掉</b>，再跑完整的预览 + 确认流程：
 * 只要有任何一行 SQL 碰到这张表，测试立刻以 {@code no such table} 失败。
 * 同时旧表行数必须保持不变（零写入），审计事件必须真的落库。
 */
class LlmConfigFileDrivenPersistenceTest {

    private static final String SECRET = "sk-persistence-not-a-real-key";

    @TempDir
    Path dataDir;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new Fixture(dataDir);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    @DisplayName("预览/确认/导出全程不碰 llm_import_preview（整表 DROP 后依然可用），旧表零写入且审计落库")
    void runtimeNeverTouchesLegacyTables() throws IOException {
        // 先证明这张表真的不存在了：任何读写都会抛 no such table
        fixture.jdbc().update("DROP TABLE llm_import_preview");
        assertThat(fixture.tableExists("llm_import_preview")).isFalse();

        String config = """
                {"schemaVersion":1,"application":{"language":"zh-CN"},"llm":{"defaultProfileId":"p1","profiles":[
                 {"id":"p1","name":"持久化档案","provider":"openai-compatible",
                  "baseUrl":"https://api.example.com/v1","model":"gpt-4o-mini","apiKey":"%s",
                  "timeoutSeconds":60,"connectTimeoutSeconds":5,"allowLocalNetwork":false}]}}""".formatted(SECRET);
        JsonNode incoming = fixture.json.mapper().readTree(config);

        // 预览：只在内存里，不写文件、不写库
        LlmDtos.ImportPreviewResponse preview = fixture.service.previewImport(incoming);
        assertThat(preview.changes().get(0).action()).isEqualTo("create");
        assertThat(Files.exists(fixture.store.path())).isFalse();

        // 确认：只写 setting.json
        LlmDtos.ImportConfirmResponse confirmed = fixture.service.confirmImport(preview.previewId());
        assertThat(confirmed.created()).isEqualTo(1);
        assertThat(Files.exists(fixture.store.path())).isTrue();
        assertThat(Files.readString(fixture.store.path(), StandardCharsets.UTF_8)).contains(SECRET);

        // 列表/详情：密钥只以掩码出现
        LlmDtos.ProfileListResponse list = fixture.service.list();
        assertThat(list.profiles().get(0).apiKeyMasked()).isEqualTo("******");
        assertThat(list.profiles().get(0).apiKeyConfigured()).isTrue();
        assertThat(fixture.json.write(list)).doesNotContain(SECRET);

        // 导出两种形态都写审计（真库真表）
        fixture.service.export(false);
        fixture.service.export(true);
        List<String> exportDetails = fixture.jdbc().queryForList(
                "SELECT detail_json FROM audit_event WHERE event_type = 'llm_config.exported'", String.class);
        assertThat(exportDetails).hasSize(2);
        assertThat(exportDetails.toString()).contains("\"includeSecrets\":false")
                .contains("\"includeSecrets\":true")
                .doesNotContain(SECRET);

        // 导入审计同样不含密钥
        String importedDetail = fixture.jdbc().queryForObject(
                "SELECT detail_json FROM audit_event WHERE event_type = 'llm_profile.imported'", String.class);
        assertThat(importedDetail).contains("created").doesNotContain(SECRET);

        // 旧表：仍然是空的（运行期零写入），表结构都还在
        assertThat(fixture.count("llm_profile")).isZero();
        assertThat(fixture.count("llm_config_state")).isZero();
    }

    // ------------------------------------------------------------ 夹具

    private static final class Fixture implements AutoCloseable {

        private final HikariDataSource dataSource;
        private final JdbcTemplate jdbc;
        private final Json json = new Json(new ObjectMapper());
        private final SettingStore store;
        private final SettingValidator validator = new SettingValidator(new ObjectMapper());
        private final LlmProfileService service;

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

            AppProperties props = new AppProperties();
            props.setDataDir(dataDir.toString());
            this.store = new SettingStore(props, json);
            ObjectProvider<LegacySecretExporter> noExporter = provider(null);
            LegacyLlmConfigMigrator migrator = new LegacyLlmConfigMigrator(
                    new com.aia.answer.repository.LlmProfileRepository(jdbc),
                    new MetaRepository(jdbc), store, json, noExporter);
            ConfigService configService = new ConfigService(store, validator, provider(migrator));
            this.service = new LlmProfileService(configService, store, validator, new MetaRepository(jdbc),
                    new AiClient(props, json), json);
        }

        private boolean tableExists(String name) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", Integer.class, name);
            return count != null && count > 0;
        }

        private int count(String table) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            return count == null ? 0 : count;
        }

        private JdbcTemplate jdbc() {
            return jdbc;
        }

        @Override
        public void close() {
            dataSource.close();
        }
    }

    /** 极简 ObjectProvider：本测试不需要一次性迁移与旧密钥。 */
    private static <T> ObjectProvider<T> provider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }
}
