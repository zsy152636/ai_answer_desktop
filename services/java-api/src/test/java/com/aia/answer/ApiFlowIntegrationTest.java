package com.aia.answer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端接口集成测试（开发文档 §12.1、§12.2 必过业务用例）。
 *
 * <p>走真实 HTTP（含安全过滤器、幂等过滤器、JSON 包络），使用独立的临时数据目录与真实
 * SQLite 文件，验证「建题 → 发布 → 建卷 → 发布快照 → 作答 → 提交判分 → 错题 → 复习 → 备份」
 * 这条主链路，以及未授权访问、版本冲突、幂等提交等边界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiFlowIntegrationTest {

    private static final Path DATA_DIR = createTempDir();

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("aia-it-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("aia.data-dir", DATA_DIR::toString);
        registry.add("aia.auto-backup-enabled", () -> true);
        registry.add("aia.log-tail-lines", () -> 50);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private com.aia.answer.web.SecurityFilter securityFilter;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    /**
     * 服务只绑定 127.0.0.1（开发文档 §2.2），而 TestRestTemplate 默认访问 localhost；
     * 在 IPv6 优先解析的机器上会连到 ::1 导致连接被拒，因此显式固定为 127.0.0.1。
     */
    @org.junit.jupiter.api.BeforeEach
    void pinLoopbackHost() {
        rest.getRestTemplate().setUriTemplateHandler(
                new org.springframework.web.util.DefaultUriBuilderFactory("http://127.0.0.1:" + port));
    }

    // ------------------------------------------------------------ 辅助

    private HttpHeaders headers(boolean withToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (withToken) {
            headers.set("X-Session-Token", securityFilter.sessionToken());
        }
        return headers;
    }

    private JsonNode call(HttpMethod method, String path, String body, boolean withToken) {
        ResponseEntity<String> response = rest.exchange(path, method,
                new HttpEntity<>(body, headers(withToken)), String.class);
        return read(response);
    }

    private JsonNode data(HttpMethod method, String path, String body) {
        JsonNode envelope = call(method, path, body, true);
        assertThat(envelope.path("success").asBoolean()).as("接口应返回成功：" + path + " → " + envelope).isTrue();
        return envelope.path("data");
    }

    private JsonNode read(ResponseEntity<String> response) {
        try {
            return mapper.readTree(response.getBody());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 读配置文件（测试直连磁盘，绕过 HTTP）：受检 IOException 一律转运行时，不污染断言代码。 */
    private static String readFile(Path file) {
        try {
            return Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 直接改配置文件（模拟用户手工编辑）：受检 IOException 一律转运行时。 */
    private static void writeFile(Path file, String content) {
        try {
            Files.writeString(file, content, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------ 用例

    @Test
    @DisplayName("安全基线：健康检查免鉴权，业务接口必须带会话令牌")
    void securityBaseline() {
        ResponseEntity<String> health = rest.getForEntity("/health", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(health).path("status").asText()).isEqualTo("UP");

        ResponseEntity<String> unauthorized = rest.exchange("/api/v1/meta", HttpMethod.GET,
                new HttpEntity<>(headers(false)), String.class);
        assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(read(unauthorized).path("error").path("code").asText()).isEqualTo("UNAUTHORIZED");

        JsonNode meta = data(HttpMethod.GET, "/api/v1/meta", null);
        assertThat(meta.path("dataDir").asText()).isEqualTo(DATA_DIR.toString());
        assertThat(meta.path("schemaVersion").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(meta.path("databaseSizeBytes").asLong()).isPositive();
    }

    @Test
    @DisplayName("主链路：演示数据 → 建题改版 → 建卷发布 → 作答判分 → 错题复习 → 备份校验")
    void fullAnswerLoop() {
        JsonNode seed = data(HttpMethod.POST, "/api/v1/system/seed-demo", "{}");
        assertThat(seed.path("skipped").asBoolean()).isFalse();
        assertThat(seed.path("questionsCreated").asInt()).isEqualTo(6);
        assertThat(seed.path("papersCreated").asInt()).isEqualTo(1);

        // 幂等：重复写入被跳过
        assertThat(data(HttpMethod.POST, "/api/v1/system/seed-demo", "{}").path("skipped").asBoolean()).isTrue();

        // 建题
        JsonNode created = data(HttpMethod.POST, "/api/v1/questions", """
                {
                  "title":"集成测试题","questionType":"single_choice","difficulty":2,
                  "stem":"1 + 1 = ?","options":[{"key":"A","content":"1"},{"key":"B","content":"2"}],
                  "answer":{"type":"single_choice","value":"B"},"explanation":"基础算术",
                  "knowledgeTags":["集成测试"],"changeNote":"初稿"
                }""");
        String questionId = created.path("id").asText();
        assertThat(created.path("currentVersion").path("versionNo").asInt()).isEqualTo(1);
        assertThat(created.path("currentVersion").path("options").get(1).path("correct").asBoolean()).isTrue();

        // 改内容 → 新版本 + 回到草稿
        JsonNode updated = data(HttpMethod.PUT, "/api/v1/questions/" + questionId, """
                {
                  "title":"集成测试题","questionType":"single_choice","difficulty":2,
                  "stem":"1 + 1 = ?（修订）","options":[{"key":"A","content":"1"},{"key":"B","content":"2"}],
                  "answer":{"type":"single_choice","value":"B"},"explanation":"基础算术",
                  "knowledgeTags":["集成测试"],"changeNote":"修订","expectedVersionNo":1
                }""");
        assertThat(updated.path("currentVersion").path("versionNo").asInt()).isEqualTo(2);
        assertThat(updated.path("status").asText()).isEqualTo("draft");

        // 乐观锁冲突
        ResponseEntity<String> conflict = rest.exchange("/api/v1/questions/" + questionId, HttpMethod.PUT,
                new HttpEntity<>("""
                        {"title":"x","questionType":"single_choice","stem":"x","options":[{"key":"A","content":"a"},{"key":"B","content":"b"}],
                         "answer":{"type":"single_choice","value":"A"},"expectedVersionNo":1}""", headers(true)),
                String.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(read(conflict).path("error").path("code").asText()).isEqualTo("VERSION_CONFLICT");

        data(HttpMethod.POST, "/api/v1/questions/" + questionId + "/publish", "{}");

        // 建卷 + 发布快照
        JsonNode paper = data(HttpMethod.POST, "/api/v1/papers", """
                {"title":"集成测试试卷","description":"自动化测试","questions":[{"questionId":"%s","score":10}]}"""
                .formatted(questionId));
        String paperId = paper.path("id").asText();
        JsonNode snapshot = data(HttpMethod.POST, "/api/v1/papers/" + paperId + "/publish", "{}");
        String snapshotId = snapshot.path("id").asText();
        assertThat(snapshot.path("totalScore").asDouble()).isEqualTo(10.0);
        assertThat(snapshot.path("questions").get(0).path("answer").path("value").asText()).isEqualTo("B");

        // 开始答题：未提交不得泄露答案
        JsonNode attempt = data(HttpMethod.POST, "/api/v1/attempts", "{\"paperId\":\"" + paperId + "\"}");
        String attemptId = attempt.path("id").asText();
        assertThat(attempt.path("questions").get(0).path("answer").isNull()).isTrue();
        String snapshotQuestionId = attempt.path("questions").get(0).path("snapshotQuestionId").asText();

        // 保存答案（先落库）
        JsonNode saved = data(HttpMethod.PUT,
                "/api/v1/attempts/" + attemptId + "/answers/" + snapshotQuestionId,
                "{\"answer\":{\"type\":\"single_choice\",\"value\":\"B\"}}");
        assertThat(saved.path("score").isNull()).isTrue();

        // 提交（带幂等键）：两次调用结果一致，不重复生成作答
        HttpHeaders idempotent = headers(true);
        idempotent.set("Idempotency-Key", "it-submit-1");
        ResponseEntity<String> first = rest.exchange("/api/v1/attempts/" + attemptId + "/submit", HttpMethod.POST,
                new HttpEntity<>("{}", idempotent), String.class);
        ResponseEntity<String> second = rest.exchange("/api/v1/attempts/" + attemptId + "/submit", HttpMethod.POST,
                new HttpEntity<>("{}", idempotent), String.class);
        JsonNode firstResult = read(first).path("data");
        JsonNode secondResult = read(second).path("data");
        assertThat(firstResult.path("attempt").path("status").asText()).isEqualTo("graded");
        assertThat(firstResult.path("attempt").path("totalScore").asDouble()).isEqualTo(10.0);
        assertThat(secondResult.path("attempt").path("totalScore").asDouble()).isEqualTo(10.0);
        assertThat(read(second).path("requestId").asText())
                .as("重复提交应命中幂等重放")
                .isEqualTo(read(first).path("requestId").asText());
        assertThat(data(HttpMethod.GET, "/api/v1/attempts/" + attemptId, null).path("answers").size()).isEqualTo(1);

        // 快照不可变：题目改版后历史结果不变
        data(HttpMethod.PUT, "/api/v1/questions/" + questionId, """
                {
                  "title":"集成测试题","questionType":"single_choice","difficulty":3,
                  "stem":"1 + 1 = ?（再次修订）","options":[{"key":"A","content":"1"},{"key":"B","content":"2"},{"key":"C","content":"3"}],
                  "answer":{"type":"single_choice","value":"C"},"explanation":"改义",
                  "knowledgeTags":["集成测试"],"changeNote":"改义","expectedVersionNo":2
                }""");
        JsonNode resultAfterEdit = data(HttpMethod.GET, "/api/v1/attempts/" + attemptId + "/result", null);
        assertThat(resultAfterEdit.path("items").get(0).path("correctAnswer").path("value").asText())
                .as("历史结果必须基于当时快照")
                .isEqualTo("B");
        assertThat(resultAfterEdit.path("attempt").path("totalScore").asDouble()).isEqualTo(10.0);

        // 错题闭环：确认无误 → 全对不进错题本
        assertThat(data(HttpMethod.GET, "/api/v1/wrong-questions?page=1&size=50", null).path("items").size()).isZero();

        // 故意答错一次，进入错题本
        JsonNode wrongAttempt = data(HttpMethod.POST, "/api/v1/attempts", "{\"paperId\":\"" + paperId + "\"}");
        String wrongAttemptId = wrongAttempt.path("id").asText();
        String wrongSnapshotQuestionId = wrongAttempt.path("questions").get(0).path("snapshotQuestionId").asText();
        data(HttpMethod.PUT, "/api/v1/attempts/" + wrongAttemptId + "/answers/" + wrongSnapshotQuestionId,
                "{\"answer\":{\"type\":\"single_choice\",\"value\":\"A\"}}");
        JsonNode wrongResult = data(HttpMethod.POST, "/api/v1/attempts/" + wrongAttemptId + "/submit", "{}");
        assertThat(wrongResult.path("attempt").path("totalScore").asDouble()).isZero();
        assertThat(wrongResult.path("wrongCount").asInt()).isEqualTo(1);

        JsonNode wrongList = data(HttpMethod.GET, "/api/v1/wrong-questions?page=1&size=50", null);
        assertThat(wrongList.path("items").size()).isEqualTo(1);
        String wrongId = wrongList.path("items").get(0).path("id").asText();
        assertThat(wrongList.path("items").get(0).path("nextReviewAt").isNull()).isFalse();

        // 复习一次正确 → reviewing；再复习一次正确 → mastered
        JsonNode firstReview = data(HttpMethod.POST, "/api/v1/wrong-questions/" + wrongId + "/review",
                "{\"result\":\"correct\"}");
        assertThat(firstReview.path("question").path("status").asText()).isEqualTo("reviewing");
        assertThat(firstReview.path("reviews").size()).isEqualTo(1);
        JsonNode secondReview = data(HttpMethod.POST, "/api/v1/wrong-questions/" + wrongId + "/review",
                "{\"result\":\"correct\"}");
        assertThat(secondReview.path("question").path("status").asText()).isEqualTo("mastered");

        // 错题练习复用快照模型
        JsonNode practice = data(HttpMethod.POST, "/api/v1/wrong-questions/practice", "{\"limit\":5}");
        assertThat(practice.path("mode").asText()).isEqualTo("wrong_practice");
        assertThat(practice.path("questions").size()).isEqualTo(1);

        // 备份与完整性
        JsonNode backup = data(HttpMethod.POST, "/api/v1/system/backup", "{}");
        assertThat(backup.path("integrityOk").asBoolean()).isTrue();
        assertThat(backup.path("checksum").asText()).hasSize(64);
        JsonNode verify = data(HttpMethod.POST,
                "/api/v1/system/backups/verify?fileName=" + backup.path("fileName").asText(), "{}");
        assertThat(verify.path("ok").asBoolean()).isTrue();
        assertThat(verify.path("foreignKeyCheck").asText()).isEqualTo("ok");
        JsonNode integrity = data(HttpMethod.POST, "/api/v1/system/integrity-check", "{}");
        assertThat(integrity.path("ok").asBoolean()).isTrue();

        // 恢复只登记请求，要求重启后执行
        JsonNode restore = data(HttpMethod.POST, "/api/v1/system/restore",
                "{\"fileName\":\"" + backup.path("fileName").asText() + "\",\"confirm\":true}");
        assertThat(restore.path("requiresRestart").asBoolean()).isTrue();

        // 删除已被引用的题目必须被拒绝
        ResponseEntity<String> deleteReferenced = rest.exchange("/api/v1/questions/" + questionId, HttpMethod.DELETE,
                new HttpEntity<>(headers(true)), String.class);
        assertThat(deleteReferenced.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("LLM 配置（文件驱动 v1.1）：校验、内存预览、导入确认、掩码、导出、凭据三态、reload last-good")
    void llmProfileLifecycle() {
        String base = "/api/v1/settings/llm-profiles";
        Path settingFile = DATA_DIR.resolve("config").resolve("setting.json");
        String secret = "sk-it-not-a-real-key";

        // 1) 纯校验：不支持的配置版本被拒（错误里带 schema path）
        JsonNode invalid = data(HttpMethod.POST, base + "/validate",
                "{\"config\":{\"schemaVersion\":2,\"profiles\":[]}}");
        assertThat(invalid.path("valid").asBoolean()).isFalse();
        assertThat(invalid.path("errors").get(0).path("path").asText()).isEqualTo("$.schemaVersion");
        assertThat(invalid.path("errors").get(0).path("message").asText()).contains("不支持");

        // 2) 完整 setting.json 形状
        String config = """
                {"schemaVersion":1,"application":{"language":"zh-CN"},"llm":{"defaultProfileId":"it-profile",
                 "profiles":[{"id":"it-profile","name":"集成测试档案","provider":"openai-compatible",
                 "baseUrl":"http://127.0.0.1:11434/v1","model":"qwen3:8b","apiKey":"%s",
                 "timeoutSeconds":60,"connectTimeoutSeconds":5,"allowLocalNetwork":true}]}}""".formatted(secret);
        JsonNode validated = data(HttpMethod.POST, base + "/validate", "{\"config\":" + config + "}");
        assertThat(validated.path("valid").asBoolean()).isTrue();
        assertThat(validated.path("summary").path("profileCount").asInt()).isEqualTo(1);
        assertThat(validated.path("summary").path("providers").get(0).asText()).isEqualTo("openai-compatible");

        // 仅 llm 段同样可校验（契约 §6：setting.json 或仅 llm 段）
        JsonNode sectionOnly = data(HttpMethod.POST, base + "/validate", """
                {"config":{"defaultProfileId":"it-profile","profiles":[{"id":"it-profile","name":"集成测试档案",
                 "provider":"openai-compatible","baseUrl":"http://127.0.0.1:11434/v1","model":"qwen3:8b"}]}}""");
        assertThat(sectionOnly.path("valid").asBoolean()).isTrue();

        // 3) 预览只存在于内存：不写文件、不落表
        JsonNode preview = data(HttpMethod.POST, base + "/import/preview", "{\"config\":" + config + "}");
        String previewId = preview.path("previewId").asText();
        assertThat(previewId).isNotBlank();
        assertThat(preview.path("changes").get(0).path("action").asText()).isEqualTo("create");
        assertThat(Files.exists(settingFile)).as("预览不得写 setting.json").isFalse();

        // 4) 确认后才写入 setting.json（原子写 + revision 变化）
        JsonNode confirmed = data(HttpMethod.POST, base + "/import/confirm",
                "{\"previewId\":\"" + previewId + "\"}");
        assertThat(confirmed.path("created").asInt()).isEqualTo(1);
        assertThat(confirmed.path("configRevision").asText()).hasSize(64);
        assertThat(Files.exists(settingFile)).isTrue();

        // 5) 列表：来源信息齐全，密钥只以掩码出现
        JsonNode profiles = data(HttpMethod.GET, base, null);
        assertThat(profiles.path("defaultProfileId").asText()).isEqualTo("it-profile");
        assertThat(profiles.path("configSource").asText()).isEqualTo("setting.json");
        assertThat(profiles.path("configPath").asText()).endsWith("setting.json");
        assertThat(profiles.path("configRevision").asText()).hasSize(64);
        assertThat(profiles.path("configUpdatedAt").asText()).isNotBlank();
        assertThat(profiles.path("warnings").isArray()).isTrue();
        JsonNode view = profiles.path("profiles").get(0);
        assertThat(view.path("apiKeyMasked").asText()).isEqualTo("******");
        assertThat(view.path("apiKeyConfigured").asBoolean()).isTrue();
        assertThat(view.path("isDefault").asBoolean()).isTrue();
        assertThat(profiles.toString()).as("任何响应都不得包含明文密钥").doesNotContain(secret);

        // 6) 导出：默认不含明文（apiKey 显式置 null），含密钥需显式传参
        JsonNode masked = data(HttpMethod.POST, base + "/export", "{}");
        assertThat(masked.path("containsSecrets").asBoolean()).isFalse();
        // 导出文档与 setting.json 同构：profiles 在 llm 段里，且 apiKey 被显式置 null
        assertThat(masked.path("config").path("llm").path("profiles").get(0).path("apiKey").isNull()).isTrue();
        assertThat(masked.toString()).doesNotContain(secret);

        JsonNode withSecrets = data(HttpMethod.POST, base + "/export", "{\"includeSecrets\":true}");
        assertThat(withSecrets.path("containsSecrets").asBoolean()).isTrue();
        assertThat(withSecrets.path("suggestedFileName").asText()).isEqualTo("setting-with-keys.json");
        assertThat(withSecrets.path("config").path("llm").path("profiles").get(0).path("apiKey").asText())
                .isEqualTo(secret);

        // 7) apiKey 三态：省略=保持；""=清除；非空=替换
        String keepBody = """
                {"name":"集成测试档案","provider":"openai-compatible",
                 "baseUrl":"http://127.0.0.1:11434/v1","model":"qwen3:8b","allowLocalNetwork":true}""";
        JsonNode kept = data(HttpMethod.PUT, base + "/it-profile", keepBody);
        assertThat(kept.path("apiKeyConfigured").asBoolean()).as("省略 apiKey 必须保持原值").isTrue();
        assertThat(readFile(settingFile)).contains(secret);

        JsonNode cleared = data(HttpMethod.PUT, base + "/it-profile", keepBody.replace("\"allowLocalNetwork\":true",
                "\"allowLocalNetwork\":true,\"apiKey\":\"\""));
        assertThat(cleared.path("apiKeyConfigured").asBoolean()).isFalse();
        assertThat(cleared.path("apiKeyMasked").isNull()).isTrue();
        assertThat(readFile(settingFile)).doesNotContain(secret);

        // 8) 凭据接口（路径保留、语义变更）：POST 只写 apiKey，DELETE 清除；响应都不回显密钥
        JsonNode rebound = data(HttpMethod.POST, base + "/it-profile/credential",
                "{\"apiKey\":\"" + secret + "\"}");
        assertThat(rebound.path("apiKeyMasked").asText()).isEqualTo("******");
        assertThat(rebound.toString()).doesNotContain(secret);
        JsonNode unbound = data(HttpMethod.DELETE, base + "/it-profile/credential", null);
        assertThat(unbound.path("apiKeyConfigured").asBoolean()).isFalse();
        assertThat(readFile(settingFile)).doesNotContain(secret);

        // 9) 重复确认同一预览必须被拒绝（预览一次性使用）
        ResponseEntity<String> replay = rest.exchange(base + "/import/confirm", HttpMethod.POST,
                new HttpEntity<>("{\"previewId\":\"" + previewId + "\"}", headers(true)), String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(read(replay).path("error").path("code").asText()).isEqualTo("STATE_CONFLICT");

        // 10) reload：文件非法 → 400 CONFIG_FILE_INVALID（带 schema path）且缓存保留上一份有效配置
        String goodRevision = data(HttpMethod.GET, base, null).path("configRevision").asText();
        writeFile(settingFile, """
                {"schemaVersion":1,"llm":{"defaultProfileId":"it-profile","profiles":[{"id":"it-profile",
                 "name":"集成测试档案","provider":"不存在的供应商","baseUrl":"http://127.0.0.1:11434/v1",
                 "model":"qwen3:8b"}]}}""");
        ResponseEntity<String> badReload = rest.exchange(base + "/reload", HttpMethod.POST,
                new HttpEntity<>("{}", headers(true)), String.class);
        assertThat(badReload.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode badBody = read(badReload);
        assertThat(badBody.path("error").path("code").asText()).isEqualTo("CONFIG_FILE_INVALID");
        assertThat(badBody.path("error").path("details").get(0).path("field").asText())
                .startsWith("$.llm.profiles");
        JsonNode afterBad = data(HttpMethod.GET, base, null);
        assertThat(afterBad.path("configRevision").asText())
                .as("非法配置必须保留上一份有效配置").isEqualTo(goodRevision);
        assertThat(afterBad.path("profiles").get(0).path("provider").asText()).isEqualTo("openai-compatible");
        assertThat(afterBad.path("warnings").size()).isPositive();

        // 11) 修好后 reload 成功；默认档案不能直接删除
        writeFile(settingFile, config);
        JsonNode reloaded = data(HttpMethod.POST, base + "/reload", "{}");
        assertThat(reloaded.path("configRevision").asText()).hasSize(64);
        ResponseEntity<String> deleteDefault = rest.exchange(base + "/it-profile", HttpMethod.DELETE,
                new HttpEntity<>(headers(true)), String.class);
        assertThat(deleteDefault.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(read(deleteDefault).path("error").path("code").asText()).isEqualTo("STATE_CONFLICT");
    }

    @Test
    @DisplayName("错误码：非法排序白名单、资源不存在、字段校验")
    void errorCodes() {
        ResponseEntity<String> badSort = rest.exchange("/api/v1/questions?sort=drop_table", HttpMethod.GET,
                new HttpEntity<>(headers(true)), String.class);
        assertThat(badSort.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(read(badSort).path("error").path("code").asText()).isEqualTo("VALIDATION_ERROR");

        ResponseEntity<String> notFound = rest.exchange("/api/v1/questions/q_missing", HttpMethod.GET,
                new HttpEntity<>(headers(true)), String.class);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(read(notFound).path("error").path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");

        ResponseEntity<String> invalidBody = rest.exchange("/api/v1/questions", HttpMethod.POST,
                new HttpEntity<>("{\"title\":\"\",\"questionType\":\"single_choice\",\"stem\":\"\"}", headers(true)),
                String.class);
        assertThat(invalidBody.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(read(invalidBody).path("error").path("details").isArray()).isTrue();
    }
}
