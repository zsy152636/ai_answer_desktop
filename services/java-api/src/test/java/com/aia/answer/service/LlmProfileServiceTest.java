package com.aia.answer.service;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.ErrorCode;
import com.aia.answer.common.Json;
import com.aia.answer.config.AppProperties;
import com.aia.answer.domain.LlmDtos;
import com.aia.answer.repository.MetaRepository;
import com.aia.answer.service.setting.ConfigService;
import com.aia.answer.service.setting.LegacyLlmConfigMigrator;
import com.aia.answer.service.setting.LegacySecretExporter;
import com.aia.answer.service.setting.SettingStore;
import com.aia.answer.service.setting.SettingValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 文件驱动 LLM 配置服务测试（契约 §6，真实临时目录，绝不发网络请求）。
 *
 * <p>刻意<b>不注入任何 DataSource</b>：{@link MetaRepository} 用只记录调用的子类，
 * 它底层的 {@code JdbcTemplate} 没有数据源 —— 一旦有代码在运行期真的去读/写
 * {@code llm_profile}/{@code llm_config_state}/{@code llm_import_preview}，
 * 这里会立刻抛异常而不是悄悄通过。真库上的「预览表零访问」另见
 * {@code LlmConfigFileDrivenPersistenceTest}。
 */
class LlmProfileServiceTest {

    private static final String SECRET = "sk-test-not-a-real-key";
    private static final String BASE_URL = "https://api.example.com/v1";
    private static final String PROVIDER = "openai-compatible";

    @TempDir
    Path dataDir;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new Fixture(dataDir, Duration.ofMinutes(10));
    }

    // ------------------------------------------------------------ 读取

    @Test
    @DisplayName("未写文件时列表为空，但来源信息齐全（configSource/configPath）")
    void listsConfigSourceBeforeAnyWrite() {
        LlmDtos.ProfileListResponse list = fixture.service.list();

        assertThat(list.profiles()).isEmpty();
        assertThat(list.defaultProfileId()).isNull();
        assertThat(list.configRevision()).isNull();
        assertThat(list.configSource()).isEqualTo("setting.json");
        assertThat(list.configPath()).isEqualTo(fixture.store.path().toAbsolutePath().toString());
        assertThat(list.warnings()).isEmpty();
        assertThat(Files.exists(fixture.store.path())).isFalse();
    }

    // ------------------------------------------------------------ 新建 / 保存 / 删除

    @Test
    @DisplayName("新建档案落到 setting.json：内容可读、revision 变化、首个档案自动成为默认")
    void createWritesSettingJson() throws IOException {
        LlmDtos.ProfileView created = fixture.service.create(input("p1", PROVIDER, BASE_URL, "gpt-4o-mini",
                new TextNode(SECRET)));

        assertThat(created.apiKeyMasked()).isEqualTo("******");
        assertThat(created.apiKeyConfigured()).isTrue();
        assertThat(created.isDefault()).isTrue();

        JsonNode onDisk = fixture.readConfig();
        assertThat(onDisk.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(onDisk.path("llm").path("defaultProfileId").asText()).isEqualTo("p1");
        JsonNode profile = onDisk.path("llm").path("profiles").get(0);
        assertThat(profile.path("id").asText()).isEqualTo("p1");
        assertThat(profile.path("provider").asText()).isEqualTo(PROVIDER);
        assertThat(profile.path("baseUrl").asText()).isEqualTo(BASE_URL);
        // 明文只允许出现在这个文件里
        assertThat(profile.path("apiKey").asText()).isEqualTo(SECRET);

        String firstRevision = fixture.service.list().configRevision();
        assertThat(firstRevision).hasSize(64);

        fixture.service.create(input("p2", "ollama", "http://127.0.0.1:11434/v1", "qwen2.5:7b", null));
        LlmDtos.ProfileListResponse list = fixture.service.list();
        assertThat(list.configRevision()).isNotEqualTo(firstRevision);
        assertThat(list.profiles()).hasSize(2);
        // 已指定默认档案后，新建不会抢走默认标记
        assertThat(list.defaultProfileId()).isEqualTo("p1");
        assertThat(list.profiles()).filteredOn(view -> "p2".equals(view.id()))
                .allSatisfy(view -> {
                    assertThat(view.isDefault()).isFalse();
                    assertThat(view.apiKeyConfigured()).isFalse();
                    assertThat(view.apiKeyMasked()).isNull();
                });
        // 没有密钥的档案不得出现 apiKey 字段（写 null 会让整份配置校验失败）
        assertThat(fixture.readConfig().path("llm").path("profiles").get(1).has("apiKey")).isFalse();
    }

    @Test
    @DisplayName("id 重复被拒（VALIDATION_ERROR + schema path），且文件不变")
    void createRejectsDuplicateId() throws IOException {
        fixture.service.create(input("p1", PROVIDER, BASE_URL, "gpt-4o-mini", null));
        String revision = fixture.service.list().configRevision();
        String onDisk = Files.readString(fixture.store.path(), StandardCharsets.UTF_8);

        ApiException error = catchThrowableOfType(ApiException.class,
                () -> fixture.service.create(input("p1", PROVIDER, BASE_URL, "gpt-4o", null)));

        assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(error.details()).isNotEmpty();
        assertThat(error.details().get(0).field()).isEqualTo("$.llm.profiles[1].id");
        assertThat(error.getMessage()).contains("未写入");
        assertThat(fixture.service.list().configRevision()).isEqualTo(revision);
        assertThat(Files.readString(fixture.store.path(), StandardCharsets.UTF_8)).isEqualTo(onDisk);
    }

    @Test
    @DisplayName("校验失败不落盘，且 details 里带准确 schema path")
    void createValidatesBeforeWriting() {
        ApiException badProvider = catchThrowableOfType(ApiException.class,
                () -> fixture.service.create(input("p1", "anthropic", BASE_URL, "claude", null)));
        assertThat(badProvider.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(badProvider.details().get(0).field()).isEqualTo("$.llm.profiles[0].provider");

        ApiException badUrl = catchThrowableOfType(ApiException.class,
                () -> fixture.service.create(input("p1", PROVIDER, "ftp://example.com", "gpt-4o-mini", null)));
        assertThat(badUrl.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(badUrl.details().get(0).field()).isEqualTo("$.llm.profiles[0].baseUrl");

        // 两次都失败：文件从未创建，缓存仍是空的
        assertThat(Files.exists(fixture.store.path())).isFalse();
        assertThat(fixture.service.list().configRevision()).isNull();
        assertThat(fixture.service.list().profiles()).isEmpty();
    }

    @Test
    @DisplayName("apiKey 三态：省略=保持原值；\"\"/null=清除；非空=替换（trim）")
    void apiKeyThreeStates() throws IOException {
        fixture.service.create(input("p1", PROVIDER, BASE_URL, "gpt-4o-mini", new TextNode(SECRET)));

        // 1) 字段省略（null 引用）→ 保持原值
        LlmDtos.ProfileView kept = fixture.service.update("p1",
                input("p1", PROVIDER, BASE_URL, "gpt-4o", null));
        assertThat(kept.apiKeyConfigured()).isTrue();
        assertThat(kept.apiKeyMasked()).isEqualTo("******");
        assertThat(fixture.readConfig().path("llm").path("profiles").get(0).path("apiKey").asText())
                .isEqualTo(SECRET);

        // 2) 显式 JSON null → 清除
        LlmDtos.ProfileView clearedByNull = fixture.service.update("p1",
                input("p1", PROVIDER, BASE_URL, "gpt-4o", NullNode.getInstance()));
        assertThat(clearedByNull.apiKeyConfigured()).isFalse();
        assertThat(fixture.readConfig().path("llm").path("profiles").get(0).has("apiKey")).isFalse();

        // 3) 空字符串 → 清除（已是空态，仍是合法写入）
        LlmDtos.ProfileView clearedByEmpty = fixture.service.update("p1",
                input("p1", PROVIDER, BASE_URL, "gpt-4o", new TextNode("")));
        assertThat(clearedByEmpty.apiKeyConfigured()).isFalse();

        // 4) 非空 → 替换并 trim
        LlmDtos.ProfileView replaced = fixture.service.update("p1",
                input("p1", PROVIDER, BASE_URL, "gpt-4o", new TextNode("   sk-replaced   ")));
        assertThat(replaced.apiKeyConfigured()).isTrue();
        assertThat(fixture.readConfig().path("llm").path("profiles").get(0).path("apiKey").asText())
                .isEqualTo("sk-replaced");

        // 5) 只有空白字符 → 按清除处理
        fixture.service.update("p1", input("p1", PROVIDER, BASE_URL, "gpt-4o", new TextNode("   ")));
        assertThat(fixture.service.list().profiles().get(0).apiKeyConfigured()).isFalse();
        assertThat(fixture.readConfig().path("llm").path("profiles").get(0).has("apiKey")).isFalse();
    }

    @Test
    @DisplayName("列表/详情响应永不含明文：掩码恒为 ******，apiKeyConfigured 反映文件事实")
    void responsesNeverContainPlaintext() throws IOException {
        fixture.service.create(input("p1", PROVIDER, BASE_URL, "gpt-4o-mini", new TextNode(SECRET)));
        fixture.service.create(input("p2", "ollama", "http://127.0.0.1:11434/v1", "qwen2.5:7b", null));

        LlmDtos.ProfileListResponse list = fixture.service.list();
        String serialized = fixture.json.write(list);
        assertThat(serialized).doesNotContain(SECRET);
        assertThat(serialized).doesNotContain("\"apiKeyRef\"").doesNotContain("credentialConfigured")
                .doesNotContain("updatedAt");

        LlmDtos.ProfileView view = list.profiles().stream().filter(p -> "p1".equals(p.id())).findFirst()
                .orElseThrow();
        assertThat(view.apiKeyMasked()).isEqualTo("******");
        assertThat(view.apiKeyConfigured()).isTrue();
        // 详情（单条返回）同样只有掩码
        assertThat(fixture.json.write(fixture.service.update("p1",
                input("p1", PROVIDER, BASE_URL, "gpt-4o-mini", null)))).doesNotContain(SECRET);

        // 手工把密钥写成空串（用户直接编辑文件）：视为未配置
        fixture.configService.save(root ->
                ((ObjectNode) root.path("llm").path("profiles").get(0)).put("apiKey", ""));
        fixture.service.list();
        assertThat(fixture.service.list().profiles().stream().filter(p -> "p1".equals(p.id())).findFirst()
                .orElseThrow().apiKeyConfigured()).isFalse();
    }

    @Test
    @DisplayName("默认档案：切换、置空需 disableAi、删除默认冲突、删除普通档案从文件消失")
    void defaultProfileRules() throws IOException {
        fixture.service.create(input("p1", PROVIDER, BASE_URL, "gpt-4o-mini", null));
        fixture.service.create(input("p2", "ollama", "http://127.0.0.1:11434/v1", "qwen2.5:7b", null));

        // 指向不存在的 id → 明确拒绝（不是「静默置空」）
        ApiException notFound = catchThrowableOfType(ApiException.class,
                () -> fixture.service.setDefault("p-missing", null));
        assertThat(notFound.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(notFound.getMessage()).contains("p-missing");

        // 置空必须显式 disableAi
        ApiException needsFlag = catchThrowableOfType(ApiException.class,
                () -> fixture.service.setDefault(null, null));
        assertThat(needsFlag.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(needsFlag.details().get(0).field()).isEqualTo("disableAi");

        // 切换默认 → 文件同步
        LlmDtos.ProfileListResponse switched = fixture.service.setDefault("p2", null);
        assertThat(switched.defaultProfileId()).isEqualTo("p2");
        assertThat(fixture.readConfig().path("llm").path("defaultProfileId").asText()).isEqualTo("p2");

        // 删除正在使用的默认档案 → STATE_CONFLICT，且文件不变
        ApiException conflict = catchThrowableOfType(ApiException.class, () -> fixture.service.delete("p2"));
        assertThat(conflict.code()).isEqualTo(ErrorCode.STATE_CONFLICT);
        assertThat(fixture.service.list().profiles()).hasSize(2);

        // 删除普通档案 → 文件里不再存在该条目
        fixture.service.delete("p1");
        assertThat(fixture.service.list().profiles()).extracting(LlmDtos.ProfileView::id).containsExactly("p2");
        assertThat(fixture.readConfig().path("llm").path("profiles")).hasSize(1);

        // 置空默认（AI 关闭）
        assertThat(fixture.service.setDefault(null, true).defaultProfileId()).isNull();
        assertThat(fixture.readConfig().path("llm").path("defaultProfileId").isNull()).isTrue();
        // 关闭后 delete 不再冲突
        fixture.service.delete("p2");
        assertThat(fixture.service.list().profiles()).isEmpty();
    }

    @Test
    @DisplayName("删除不存在的档案 → RESOURCE_NOT_FOUND")
    void deleteMissingProfile() {
        ApiException error = catchThrowableOfType(ApiException.class, () -> fixture.service.delete("nope"));
        assertThat(error.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ------------------------------------------------------------ 凭据接口

    @Test
    @DisplayName("credential POST/DELETE 写文件、不回显密钥，并写审计")
    void credentialEndpointsWriteFileAndAudit() throws IOException {
        fixture.service.create(input("p1", PROVIDER, BASE_URL, "gpt-4o-mini", null));

        LlmDtos.ProfileView bound = fixture.service.setCredential("p1", SECRET);
        assertThat(bound.apiKeyMasked()).isEqualTo("******");
        assertThat(fixture.json.write(bound)).doesNotContain(SECRET);
        assertThat(fixture.readConfig().path("llm").path("profiles").get(0).path("apiKey").asText())
                .isEqualTo(SECRET);
        assertThat(fixture.meta.events).contains("llm_profile.credential_bound");

        LlmDtos.ProfileView cleared = fixture.service.clearCredential("p1");
        assertThat(cleared.apiKeyConfigured()).isFalse();
        assertThat(fixture.readConfig().path("llm").path("profiles").get(0).has("apiKey")).isFalse();
        assertThat(fixture.meta.events).contains("llm_profile.credential_removed");

        ApiException blank = catchThrowableOfType(ApiException.class,
                () -> fixture.service.setCredential("p1", "   "));
        assertThat(blank.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        // 审计 detail 里绝不允许出现密钥
        assertThat(fixture.meta.details).allSatisfy(detail -> assertThat(detail).doesNotContain(SECRET));
    }

    // ------------------------------------------------------------ 纯校验

    @Test
    @DisplayName("validate 纯校验：接受完整 setting.json / 仅 llm 段 / 旧 llm-config.json，且不写文件")
    void validateAcceptsThreeShapesAndNeverWrites() {
        // 1) 完整 setting.json
        LlmDtos.ValidationResponse full = fixture.service.validate(fixture.json.readTreeOrNull("""
                {"schemaVersion":1,"application":{"language":"zh-CN"},"llm":{"defaultProfileId":"p1",
                 "profiles":[{"id":"p1","name":"档案","provider":"ollama","baseUrl":"http://127.0.0.1:11434/v1",
                 "model":"qwen2.5:7b"}]}}"""));
        assertThat(full.valid()).isTrue();
        assertThat(full.summary().profileCount()).isEqualTo(1);
        assertThat(full.summary().providers()).containsExactly("ollama");

        // 2) 仅 llm 段（缺 schemaVersion 也能校验）
        LlmDtos.ValidationResponse section = fixture.service.validate(fixture.json.readTreeOrNull("""
                {"defaultProfileId":"p1","profiles":[{"id":"p1","name":"档案","provider":"openai-compatible",
                 "baseUrl":"https://api.example.com/v1","model":"gpt-4o-mini"}]}"""));
        assertThat(section.valid()).isTrue();

        // 3) 旧 llm-config.json 形状：apiKeyRef 视为「无密钥」而不是校验错误
        LlmDtos.ValidationResponse legacy = fixture.service.validate(fixture.json.readTreeOrNull("""
                {"schemaVersion":1,"defaultProfileId":"p1","exportedAt":"2026-01-01T00:00:00Z",
                 "profiles":[{"id":"p1","name":"档案","provider":"openai-compatible",
                 "baseUrl":"https://api.example.com/v1","model":"gpt-4o-mini",
                 "apiKeyRef":"credential://llm/p1"}]}"""));
        assertThat(legacy.valid()).isTrue();

        // 4) 跨字段规则：defaultProfileId 悬空 / id 重复
        LlmDtos.ValidationResponse dangling = fixture.service.validate(fixture.json.readTreeOrNull("""
                {"schemaVersion":1,"llm":{"defaultProfileId":"p-missing","profiles":[
                 {"id":"p1","name":"档案","provider":"ollama","baseUrl":"http://127.0.0.1:11434/v1",
                  "model":"qwen2.5:7b"}]}}"""));
        assertThat(dangling.valid()).isFalse();
        assertThat(dangling.errors()).anySatisfy(issue -> {
            assertThat(issue.path()).isEqualTo("$.llm.defaultProfileId");
            assertThat(issue.message()).contains("未指向任何档案");
        });

        LlmDtos.ValidationResponse duplicated = fixture.service.validate(fixture.json.readTreeOrNull("""
                {"schemaVersion":1,"llm":{"defaultProfileId":"p1","profiles":[
                 {"id":"p1","name":"A","provider":"ollama","baseUrl":"http://127.0.0.1:11434/v1","model":"m"},
                 {"id":"p1","name":"B","provider":"ollama","baseUrl":"http://127.0.0.1:11434/v1","model":"m"}]}}"""));
        assertThat(duplicated.valid()).isFalse();
        assertThat(duplicated.errors()).anySatisfy(issue ->
                assertThat(issue.message()).contains("id 重复"));

        // 不支持的版本
        LlmDtos.ValidationResponse unsupported = fixture.service.validate(
                fixture.json.readTreeOrNull("{\"schemaVersion\":2,\"profiles\":[]}"));
        assertThat(unsupported.valid()).isFalse();
        assertThat(unsupported.errors().get(0).path()).isEqualTo("$.schemaVersion");

        // 纯校验不得写任何文件
        assertThat(Files.exists(fixture.store.path())).isFalse();
    }

    @Test
    @DisplayName("脱敏导出文档（apiKey 为 null）可以被重新校验，不会被当成非法配置")
    void validateAcceptsSecretlessExport() {
        String exportedWithoutKeys = """
                {"schemaVersion":1,"application":{"language":"zh-CN"},"llm":{"defaultProfileId":"p1","profiles":[
                 {"id":"p1","name":"档案","provider":"ollama","baseUrl":"http://127.0.0.1:11434/v1",
                  "model":"qwen2.5:7b","apiKey":null}]}}""";
        assertThat(fixture.service.validate(fixture.json.readTreeOrNull(exportedWithoutKeys)).valid()).isTrue();
    }

    // ------------------------------------------------------------ 导入预览 / 确认

    @Test
    @DisplayName("导入预览只在内存：不改文件；确认后才写文件；同一预览只能确认一次")
    void previewIsInMemoryAndConfirmWritesFile() throws IOException {
        ObjectNode incoming = (ObjectNode) fixture.json.readTreeOrNull("""
                {"schemaVersion":1,"llm":{"defaultProfileId":"p1","profiles":[
                 {"id":"p1","name":"导入档案","provider":"openai-compatible","baseUrl":"https://api.example.com/v1",
                  "model":"gpt-4o-mini","apiKey":"%s"}]}}""".formatted(SECRET));

        LlmDtos.ImportPreviewResponse preview = fixture.service.previewImport(incoming);
        assertThat(preview.previewId()).isNotBlank();
        assertThat(preview.errors()).isEmpty();
        assertThat(preview.changes()).hasSize(1);
        assertThat(preview.changes().get(0).action()).isEqualTo("create");
        assertThat(preview.expiresAt()).isNotBlank();
        // 预览阶段：文件不存在、缓存未变
        assertThat(Files.exists(fixture.store.path())).isFalse();
        assertThat(fixture.service.list().configRevision()).isNull();

        LlmDtos.ImportConfirmResponse confirmed = fixture.service.confirmImport(preview.previewId());
        assertThat(confirmed.created()).isEqualTo(1);
        assertThat(confirmed.configRevision()).hasSize(64);
        assertThat(fixture.readConfig().path("llm").path("profiles").get(0).path("apiKey").asText())
                .isEqualTo(SECRET);
        assertThat(fixture.meta.events).contains("llm_profile.imported");
        assertThat(fixture.meta.details).allSatisfy(detail -> assertThat(detail).doesNotContain(SECRET));

        // 预览一次性使用：重复确认是 STATE_CONFLICT（不是静默重复导入）
        ApiException replay = catchThrowableOfType(ApiException.class,
                () -> fixture.service.confirmImport(preview.previewId()));
        assertThat(replay.code()).isEqualTo(ErrorCode.STATE_CONFLICT);

        // 第二次预览：已存在 → unchanged，确认后文件不变
        String revision = fixture.service.list().configRevision();
        LlmDtos.ImportPreviewResponse second = fixture.service.previewImport(incoming);
        assertThat(second.changes().get(0).action()).isEqualTo("unchanged");
        LlmDtos.ImportConfirmResponse secondConfirm = fixture.service.confirmImport(second.previewId());
        assertThat(secondConfirm.unchanged()).isEqualTo(1);
        assertThat(secondConfirm.created()).isZero();
        assertThat(fixture.service.list().configRevision()).isEqualTo(revision);
    }

    @Test
    @DisplayName("导入预览不因脱敏导出而清掉已有密钥（apiKey 缺失/null = 无密钥信息）")
    void importKeepsExistingSecretWhenIncomingHasNoKey() throws IOException {
        fixture.service.create(input("p1", PROVIDER, BASE_URL, "gpt-4o-mini", new TextNode(SECRET)));

        LlmDtos.ImportPreviewResponse preview = fixture.service.previewImport(fixture.json.readTreeOrNull("""
                {"schemaVersion":1,"llm":{"defaultProfileId":"p1","profiles":[
                 {"id":"p1","name":"改名后的档案","provider":"openai-compatible",
                  "baseUrl":"https://api.example.com/v1","model":"gpt-4o-mini","apiKey":null}]}}"""));
        assertThat(preview.changes().get(0).action()).isEqualTo("update");
        fixture.service.confirmImport(preview.previewId());

        JsonNode profile = fixture.readConfig().path("llm").path("profiles").get(0);
        assertThat(profile.path("name").asText()).isEqualTo("改名后的档案");
        assertThat(profile.path("apiKey").asText()).as("缺少密钥信息的导入不得清掉密钥").isEqualTo(SECRET);
    }

    @Test
    @DisplayName("非法配置的预览在确认时以 VALIDATION_ERROR 拒绝，不写文件")
    void confirmRejectsInvalidPreview() {
        LlmDtos.ImportPreviewResponse preview = fixture.service.previewImport(fixture.json.readTreeOrNull("""
                {"schemaVersion":1,"llm":{"profiles":[{"id":"p1","name":"档案","provider":"anthropic",
                 "baseUrl":"https://api.example.com/v1","model":"claude"}]}}"""));
        assertThat(preview.errors()).isNotEmpty();

        ApiException error = catchThrowableOfType(ApiException.class,
                () -> fixture.service.confirmImport(preview.previewId()));
        assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(error.details().get(0).field()).isEqualTo("$.llm.profiles[0].provider");
        assertThat(Files.exists(fixture.store.path())).isFalse();
    }

    @Test
    @DisplayName("previewId 过期返回 STATE_CONFLICT（TTL 10 分钟，测试注入 0）")
    void expiredPreviewIsRejected() {
        Fixture expiring = new Fixture(dataDir, Duration.ZERO);
        LlmDtos.ImportPreviewResponse preview = expiring.service.previewImport(expiring.json.readTreeOrNull("""
                {"schemaVersion":1,"llm":{"defaultProfileId":"p1","profiles":[
                 {"id":"p1","name":"档案","provider":"ollama","baseUrl":"http://127.0.0.1:11434/v1",
                  "model":"qwen2.5:7b"}]}}"""));

        ApiException error = catchThrowableOfType(ApiException.class,
                () -> expiring.service.confirmImport(preview.previewId()));
        assertThat(error.code()).isEqualTo(ErrorCode.STATE_CONFLICT);
        assertThat(error.getMessage()).contains("过期");
        assertThat(Files.exists(expiring.store.path())).isFalse();

        // 不存在的 previewId 同样是 STATE_CONFLICT
        ApiException unknown = catchThrowableOfType(ApiException.class,
                () -> expiring.service.confirmImport("prev_does_not_exist"));
        assertThat(unknown.code()).isEqualTo(ErrorCode.STATE_CONFLICT);
    }

    // ------------------------------------------------------------ 导出

    @Test
    @DisplayName("导出默认不含密钥；includeSecrets=true 才含明文；两种情况都写审计且审计不含密钥")
    void exportOmitsSecretsByDefaultAndAuditsBoth() {
        fixture.service.create(input("p1", PROVIDER, BASE_URL, "gpt-4o-mini", new TextNode(SECRET)));

        LlmDtos.ExportResponse masked = fixture.service.export(null);
        assertThat(masked.includeSecrets()).isFalse();
        assertThat(masked.containsSecrets()).isFalse();
        assertThat(masked.suggestedFileName()).isEqualTo("setting.json");
        assertThat(masked.config().path("llm").path("profiles").get(0).path("apiKey").isNull()).isTrue();
        // 与 setting.json 同构：schemaVersion / application / llm 都在
        assertThat(masked.config().path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(masked.config().path("application").path("language").asText()).isEqualTo("zh-CN");
        assertThat(masked.config().toString()).doesNotContain(SECRET);

        LlmDtos.ExportResponse withKeys = fixture.service.export(true);
        assertThat(withKeys.includeSecrets()).isTrue();
        assertThat(withKeys.containsSecrets()).isTrue();
        assertThat(withKeys.suggestedFileName()).isEqualTo("setting-with-keys.json");
        assertThat(withKeys.config().path("llm").path("profiles").get(0).path("apiKey").asText())
                .isEqualTo(SECRET);

        // 审计：两次导出都留痕，且 detail 里只有布尔与数量
        assertThat(fixture.meta.events.stream().filter("llm_config.exported"::equals)).hasSize(2);
        assertThat(fixture.meta.details).anySatisfy(detail -> assertThat(detail).contains("\"includeSecrets\":false"));
        assertThat(fixture.meta.details).anySatisfy(detail -> assertThat(detail).contains("\"includeSecrets\":true"));
        assertThat(fixture.meta.details).allSatisfy(detail -> assertThat(detail).doesNotContain(SECRET));
    }

    @Test
    @DisplayName("配置不存在时导出仍返回可用的空骨架（不会 500）")
    void exportWithoutConfigReturnsSkeleton() {
        LlmDtos.ExportResponse exported = fixture.service.export(false);
        assertThat(exported.config().path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(exported.config().path("llm").path("profiles")).isEmpty();
        assertThat(exported.config().path("llm").path("defaultProfileId").isNull()).isTrue();
    }

    // ------------------------------------------------------------ reload

    @Test
    @DisplayName("reload：文件非法返回 CONFIG_FILE_INVALID（带 schema path），缓存保留上一份有效配置")
    void reloadKeepsLastGood() throws IOException {
        fixture.service.create(input("p1", PROVIDER, BASE_URL, "gpt-4o-mini", new TextNode(SECRET)));
        String goodRevision = fixture.service.list().configRevision();

        // 1) Schema 违规
        fixture.writeRaw("""
                {"schemaVersion":1,"llm":{"defaultProfileId":"p1","profiles":[{"id":"p1","name":"档案",
                 "provider":"anthropic","baseUrl":"%s","model":"claude"}]}}""".formatted(BASE_URL));
        ApiException invalid = catchThrowableOfType(ApiException.class, () -> fixture.service.reload());
        assertThat(invalid.code()).isEqualTo(ErrorCode.CONFIG_FILE_INVALID);
        assertThat(invalid.details().get(0).field()).isEqualTo("$.llm.profiles[0].provider");
        assertThat(invalid.getMessage()).contains("保留上一份有效配置");

        LlmDtos.ProfileListResponse afterInvalid = fixture.service.list();
        assertThat(afterInvalid.configRevision()).isEqualTo(goodRevision);
        assertThat(afterInvalid.profiles().get(0).provider()).isEqualTo(PROVIDER);
        assertThat(afterInvalid.warnings()).isNotEmpty();
        assertThat(afterInvalid.warnings().get(0).code()).isEqualTo("CONFIG_FILE_INVALID");
        assertThat(afterInvalid.warnings().get(0).path()).isEqualTo("$.llm.profiles[0].provider");
        // 缓存里的密钥仍是上一份有效配置的
        assertThat(afterInvalid.profiles().get(0).apiKeyConfigured()).isTrue();

        // 2) JSON 语法错误（行列号在消息里，缓存同样保留）
        fixture.writeRaw("{ this is not json ");
        ApiException syntax = catchThrowableOfType(ApiException.class, () -> fixture.service.reload());
        assertThat(syntax.code()).isEqualTo(ErrorCode.CONFIG_FILE_INVALID);
        assertThat(syntax.getMessage()).contains("行");
        assertThat(fixture.service.list().configRevision()).isEqualTo(goodRevision);

        // 3) 修好后 reload 成功，warnings 清空
        fixture.writeRaw(fixture.json.writePretty(fixture.json.readTreeOrNull("""
                {"schemaVersion":1,"application":{"language":"zh-CN"},"llm":{"defaultProfileId":"p1","profiles":[
                 {"id":"p1","name":"档案","provider":"ollama","baseUrl":"http://127.0.0.1:11434/v1",
                  "model":"qwen2.5:7b"}]}}""")));
        LlmDtos.ReloadResponse reloaded = fixture.service.reload();
        assertThat(reloaded.configRevision()).isNotEqualTo(goodRevision).hasSize(64);
        assertThat(reloaded.warnings()).isEmpty();
        assertThat(fixture.service.list().profiles().get(0).provider()).isEqualTo("ollama");

        // 4) 文件被删除是合法状态：回到空快照
        Files.delete(fixture.store.path());
        LlmDtos.ReloadResponse empty = fixture.service.reload();
        assertThat(empty.configRevision()).isNull();
        assertThat(fixture.service.list().profiles()).isEmpty();
    }

    // ------------------------------------------------------------ 调用档案

    @Test
    @DisplayName("buildCallProfile 从文件读密钥：需要密钥的 provider 缺密钥时报 CREDENTIAL_MISSING")
    void buildCallProfileReadsFile() {
        fixture.service.create(input("local", "ollama", "http://127.0.0.1:11434/v1", "qwen2.5:7b", null));
        fixture.service.create(input("cloud", PROVIDER, BASE_URL, "gpt-4o-mini", new TextNode(SECRET)));

        // ollama 不需要密钥：不带 apiKey 也是可用档案
        JsonNode local = fixture.service.buildCallProfile("local");
        assertThat(local.path("id").asText()).isEqualTo("local");
        assertThat(local.has("apiKey")).isFalse();
        assertThat(local.path("allowLocalNetwork").asBoolean()).isTrue();
        assertThat(fixture.service.hasUsableProfile()).isTrue();

        JsonNode cloud = fixture.service.buildCallProfile("cloud");
        assertThat(cloud.path("apiKey").asText()).isEqualTo(SECRET);

        // openai-compatible 缺密钥 → CREDENTIAL_MISSING
        fixture.service.clearCredential("cloud");
        ApiException missing = catchThrowableOfType(ApiException.class,
                () -> fixture.service.buildCallProfile("cloud"));
        assertThat(missing.code()).isEqualTo(ErrorCode.CREDENTIAL_MISSING);
        assertThat(missing.getMessage()).doesNotContain(SECRET);

        ApiException unknown = catchThrowableOfType(ApiException.class,
                () -> fixture.service.buildCallProfile("p-missing"));
        assertThat(unknown.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        // 没有默认档案时，profileId 省略 → CREDENTIAL_MISSING
        fixture.service.setDefault(null, true);
        assertThat(fixture.service.hasUsableProfile()).isFalse();
        ApiException noDefault = catchThrowableOfType(ApiException.class,
                () -> fixture.service.buildCallProfile(null));
        assertThat(noDefault.code()).isEqualTo(ErrorCode.CREDENTIAL_MISSING);
    }

    @Test
    @DisplayName("profile 级 temperature：读写与 Schema 校验（0–2），并适配到调用的 parameters.temperature")
    void profileTemperatureRoundTrip() throws IOException {
        LlmDtos.ProfileView created = fixture.service.create(inputWithTemperature(0.2, null));
        assertThat(created.temperature()).isEqualTo(0.2);
        assertThat(fixture.readConfig().path("llm").path("profiles").get(0).path("temperature").asDouble())
                .isEqualTo(0.2);

        // 未提供 temperature → 保持原值（不是清空）
        LlmDtos.ProfileView kept = fixture.service.update("p1",
                input("p1", "ollama", "http://127.0.0.1:11434/v1", "qwen2.5:7b", null));
        assertThat(kept.temperature()).isEqualTo(0.2);

        // 超出 Schema 范围（0–2）→ VALIDATION_ERROR，schema path 指向 profile 的 temperature
        ApiException tooHot = catchThrowableOfType(ApiException.class,
                () -> fixture.service.update("p1", inputWithTemperature(3.0, null)));
        assertThat(tooHot.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(tooHot.details()).anySatisfy(violation ->
                assertThat(violation.field()).isEqualTo("$.llm.profiles[0].temperature"));
        assertThat(fixture.readConfig().path("llm").path("profiles").get(0).path("temperature").asDouble())
                .as("校验失败不得落盘").isEqualTo(0.2);

        // 调用档案：profile 级温度适配进 parameters.temperature（Python 的 LlmCallProfile 只认这个位置）
        assertThat(fixture.service.buildCallProfile("p1").path("parameters").path("temperature").asDouble())
                .isEqualTo(0.2);

        // parameters 里显式写了 temperature 时不覆盖用户的选择
        fixture.service.update("p1", inputWithTemperature(0.2, Map.of("temperature", 0.9)));
        assertThat(fixture.service.buildCallProfile("p1").path("parameters").path("temperature").asDouble())
                .isEqualTo(0.9);
    }

    @Test
    @DisplayName("test-connection：缺档案/缺字段本地即拒；AI 服务不可用时 category=unavailable，且不回显密钥")
    void testConnectionFailsFastWithoutNetwork() {
        fixture.service.create(input("p1", PROVIDER, BASE_URL, "gpt-4o-mini", new TextNode(SECRET)));

        // profileId 不存在 → RESOURCE_NOT_FOUND（读文件，不发请求）
        ApiException missingProfile = catchThrowableOfType(ApiException.class,
                () -> fixture.service.testConnection(new LlmDtos.TestConnectionRequest(null, "p-missing")));
        assertThat(missingProfile.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        // profile 与 profileId 都没给 → VALIDATION_ERROR
        ApiException neither = catchThrowableOfType(ApiException.class,
                () -> fixture.service.testConnection(new LlmDtos.TestConnectionRequest(null, null)));
        assertThat(neither.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);

        // 内联档案缺 model → 本地校验失败，不触碰任何网络
        ObjectNode incomplete = fixture.json.object();
        incomplete.put("provider", PROVIDER);
        incomplete.put("baseUrl", BASE_URL);
        incomplete.put("apiKey", SECRET);
        ApiException badInline = catchThrowableOfType(ApiException.class,
                () -> fixture.service.testConnection(new LlmDtos.TestConnectionRequest(incomplete, null)));
        assertThat(badInline.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(badInline.details().get(0).field()).isEqualTo("model");

        // 完整内联档案：Python AI 服务未启动 → 统一映射为 unavailable，响应里没有密钥
        ObjectNode inline = fixture.json.object();
        inline.put("provider", PROVIDER);
        inline.put("baseUrl", BASE_URL);
        inline.put("model", "gpt-4o-mini");
        inline.put("apiKey", SECRET);
        LlmDtos.TestConnectionResponse inlineResult = fixture.service.testConnection(
                new LlmDtos.TestConnectionRequest(inline, null));
        assertThat(inlineResult.ok()).isFalse();
        assertThat(inlineResult.category()).isEqualTo("unavailable");
        assertThat(fixture.json.write(inlineResult)).doesNotContain(SECRET);

        // profileId 形式：密钥从 setting.json 取，同样不回显，也不落任何新文件
        LlmDtos.TestConnectionResponse byId = fixture.service.testConnection(
                new LlmDtos.TestConnectionRequest(null, "p1"));
        assertThat(byId.category()).isEqualTo("unavailable");
        assertThat(byId.message()).doesNotContain(SECRET);
        assertThat(fixture.json.write(byId)).doesNotContain(SECRET);
    }

    // ------------------------------------------------------------ 夹具

    private LlmDtos.ProfileInput input(String id, String provider, String baseUrl, String model, JsonNode apiKey) {
        return new LlmDtos.ProfileInput(id, "档案 " + id, provider, baseUrl, model, apiKey,
                null, 60, 5, "ollama".equals(provider), Map.of("stream", true), null);
    }

    private LlmDtos.ProfileInput inputWithTemperature(Double temperature, Map<String, Object> parameters) {
        return new LlmDtos.ProfileInput("p1", "档案 p1", "ollama", "http://127.0.0.1:11434/v1", "qwen2.5:7b",
                null, temperature, 60, 5, true, parameters, null);
    }

    /** 真实临时目录 + 真实 ConfigService；MetaRepository 用只记录调用的子类（无 DataSource）。 */
    private static final class Fixture {

        private final AppProperties props;
        private final Json json = new Json(new ObjectMapper());
        private final SettingStore store;
        private final SettingValidator validator = new SettingValidator(new ObjectMapper());
        private final RecordingMeta meta = new RecordingMeta();
        private final ConfigService configService;
        private final LlmProfileService service;

        private Fixture(Path dataDir, Duration previewTtl) {
            this.props = new AppProperties();
            props.setDataDir(dataDir.toString());
            this.store = new SettingStore(props, json);
            // 本测试不涉及一次性迁移（没有旧表）：显式给一个「读不到密钥」的导出点
            ObjectProvider<LegacySecretExporter> noExporter = provider(null);
            LegacyLlmConfigMigrator migrator = new LegacyLlmConfigMigrator(null, null, store, json, noExporter);
            this.configService = new ConfigService(store, validator, provider(migrator));
            AiClient aiClient = new AiClient(props, json);
            this.service = new LlmProfileService(configService, store, validator, meta, aiClient, json, previewTtl);
        }

        private JsonNode readConfig() throws IOException {
            return json.mapper().readTree(Files.readString(store.path(), StandardCharsets.UTF_8));
        }

        private void writeRaw(String content) throws IOException {
            Files.createDirectories(store.path().getParent());
            Files.writeString(store.path(), content, StandardCharsets.UTF_8);
        }
    }

    /** 只记录审计调用：没有 DataSource，任何真实 SQL 都会立刻失败。 */
    private static final class RecordingMeta extends MetaRepository {

        private final List<String> events = new ArrayList<>();
        private final List<String> details = new ArrayList<>();

        private RecordingMeta() {
            super(new JdbcTemplate());
        }

        @Override
        public void audit(String eventType, String entityType, String entityId, String detailJson, String now) {
            events.add(eventType);
            details.add(detailJson == null ? "" : detailJson);
        }
    }

    /** 极简 ObjectProvider：把实例直接交给 ConfigService / 迁移器（无实例时返回 null）。 */
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
