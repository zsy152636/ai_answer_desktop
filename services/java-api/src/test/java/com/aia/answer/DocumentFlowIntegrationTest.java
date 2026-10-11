package com.aia.answer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资料库与本地检索端到端集成测试（开发文档 §12.2、阶段 6 完成定义）。
 *
 * <p>覆盖：文本资料导入 → 分块 → FTS5/关键词检索 → **来源可追溯**（命中能回到具体分块逐字核对）
 * → 版本化索引重建 → 重复导入三分支 → 删除时的文件补偿 → 格式与鉴权边界。
 *
 * <p>走真实 HTTP（含安全过滤器与 JSON 包络），使用独立的临时数据目录与真实 SQLite 文件。
 * 同一测试类共享 Spring 上下文与数据目录，因此每份资料都带**唯一标记词**：既避免 sha256 去重
 * 造成的相互干扰，也让检索断言只可能命中本次导入的资料（测试可乱序执行）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocumentFlowIntegrationTest {

    private static final Path DATA_DIR = createTempDir();
    private static final Path SOURCE_DIR = createTempDir();

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("aia-doc-it-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("aia.data-dir", DATA_DIR::toString);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private com.aia.answer.web.SecurityFilter securityFilter;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @BeforeEach
    void pinLoopbackHost() {
        rest.getRestTemplate().setUriTemplateHandler(
                new org.springframework.web.util.DefaultUriBuilderFactory("http://127.0.0.1:" + port));
    }

    // ------------------------------------------------------------ 主链路

    @Test
    @DisplayName("导入文本资料 → 检索命中 → 命中可回到具体分块逐字核对 → 短查询降级为关键词")
    void importsSearchesAndTracesSource() throws Exception {
        String marker = "柠檬酸循环";
        Path source = writeSource("网络基础.txt", sampleText(marker));
        JsonNode imported = data(HttpMethod.POST, "/api/v1/documents/import",
                importBody(source, "reject"), true);
        String documentId = imported.path("id").asText();
        assertThat(imported.path("status").asText()).isEqualTo("indexed");
        assertThat(imported.path("chunkCount").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(imported.path("parserVersion").asText()).contains("text-v1");
        assertThat(imported.path("sourceKind").asText()).isEqualTo("text");

        JsonNode search = data(HttpMethod.GET, "/api/v1/search?q=" + encode(marker), null, true);
        assertThat(search.path("results")).as("检索响应：" + search).isNotEmpty();
        JsonNode hit = search.path("results").get(0);
        assertThat(hit.path("documentId").asText()).isEqualTo(documentId);
        assertThat(hit.path("chunkId").asText()).isNotBlank();
        assertThat(hit.path("title").asText()).isEqualTo("网络基础");
        assertThat(hit.path("score").asDouble()).isGreaterThan(0.0);
        assertThat(hit.path("snippet").asText()).contains("[[");

        // 来源可追溯：按 chunkId 回到分块列表，原文必须逐字包含命中词
        JsonNode chunks = data(HttpMethod.GET, "/api/v1/documents/" + documentId + "/chunks?size=50", null, true);
        String matchedContent = null;
        for (JsonNode chunk : chunks.path("items")) {
            if (chunk.path("id").asText().equals(hit.path("chunkId").asText())) {
                matchedContent = chunk.path("content").asText();
            }
        }
        assertThat(matchedContent).as("命中必须能定位到真实分块").isNotNull();
        assertThat(matchedContent).contains(marker);
        assertThat(chunks.path("items").get(0).path("contentHash").asText()).hasSize(64);

        // trigram 无法命中不足 3 字符的片段：短查询必须如实降级为关键词模式
        JsonNode shortQuery = data(HttpMethod.GET, "/api/v1/search?q=" + encode("循环"), null, true);
        assertThat(shortQuery.path("mode").asText()).isEqualTo("keyword");
        assertThat(shortQuery.path("results")).isNotEmpty();
        assertThat(shortQuery.path("results").get(0).path("documentId").asText()).isEqualTo(documentId);
    }

    @Test
    @DisplayName("版本化索引重建：active 版本递增、分块整体换代、检索持续可用")
    void reindexSwitchesIndexVersion() throws Exception {
        String marker = "核糖体翻译";
        Path source = writeSource("重建索引.txt", sampleText(marker));
        String documentId = data(HttpMethod.POST, "/api/v1/documents/import", importBody(source, "reject"), true)
                .path("id").asText();

        JsonNode before = data(HttpMethod.GET, "/api/v1/search/index", null, true);
        long activeBefore = before.path("activeVersion").asLong();
        String chunkBefore = data(HttpMethod.GET, "/api/v1/search?q=" + encode(marker), null, true)
                .path("results").get(0).path("chunkId").asText();

        JsonNode rebuild = data(HttpMethod.POST, "/api/v1/documents/reindex", "{}", true);
        assertThat(rebuild.path("failures")).isEmpty();
        assertThat(rebuild.path("chunks").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(rebuild.path("indexState").path("activeVersion").asLong()).isEqualTo(activeBefore + 1);

        JsonNode after = data(HttpMethod.GET, "/api/v1/search?q=" + encode(marker), null, true);
        assertThat(after.path("results")).isNotEmpty();
        assertThat(after.path("results").get(0).path("documentId").asText()).isEqualTo(documentId);
        assertThat(after.path("results").get(0).path("chunkId").asText())
                .as("新版本使用新的分块 id，旧版本分块不再对外服务")
                .isNotEqualTo(chunkBefore);

        JsonNode chunks = data(HttpMethod.GET, "/api/v1/documents/" + documentId + "/chunks?size=50", null, true);
        assertThat(chunks.path("items").get(0).path("indexVersion").asLong()).isEqualTo(activeBefore + 1);
        assertThat(chunks.path("total").asLong()).isGreaterThanOrEqualTo(2);
    }

    // ------------------------------------------------------------ 重复导入与补偿

    @Test
    @DisplayName("重复导入：默认拒绝并返回 409，复用与新建各自可用，删除时按引用计数补偿文件")
    void duplicateImportAndFileCompensation() throws Exception {
        String marker = "线粒体呼吸";
        Path source = writeSource("重复导入.txt", sampleText(marker));
        String sha256 = sha256(source);
        String first = data(HttpMethod.POST, "/api/v1/documents/import", importBody(source, "reject"), true)
                .path("id").asText();

        ResponseEntity<String> rejected = exchange(HttpMethod.POST, "/api/v1/documents/import",
                importBody(source, "reject"), true);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode error = mapper.readTree(rejected.getBody()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("STATE_CONFLICT");
        assertThat(error.path("details").get(0).path("field").asText()).isEqualTo("sourcePath");

        // 客户端据 sha256 取回 typed 的已存在资料列表，再让用户选择复用 / 新建 / 取消
        JsonNode bySha = data(HttpMethod.GET, "/api/v1/documents?sha256=" + sha256, null, true);
        assertThat(bySha.path("total").asLong()).isEqualTo(1);
        assertThat(bySha.path("items").get(0).path("id").asText()).isEqualTo(first);
        assertThat(bySha.path("items").get(0).path("sha256").asText()).isEqualTo(sha256);

        String reused = data(HttpMethod.POST, "/api/v1/documents/import", importBody(source, "reuse"), true)
                .path("id").asText();
        assertThat(reused).isEqualTo(first);

        String second = data(HttpMethod.POST, "/api/v1/documents/import", importBody(source, "new"), true)
                .path("id").asText();
        assertThat(second).isNotEqualTo(first);
        assertThat(data(HttpMethod.GET, "/api/v1/documents?sha256=" + sha256, null, true).path("total").asLong())
                .isEqualTo(2);

        // 还有另一条资料引用同一份文件：删除一条不得删除磁盘文件
        JsonNode deletedFirst = data(HttpMethod.DELETE, "/api/v1/documents/" + second, null, true);
        assertThat(deletedFirst.path("deleted").asBoolean()).isTrue();
        assertThat(deletedFirst.path("fileRemoved").asBoolean()).isFalse();

        // 最后一条引用被删除时，文件一并清理（开发文档 §9.1 补偿路径）
        JsonNode deletedSecond = data(HttpMethod.DELETE, "/api/v1/documents/" + first, null, true);
        assertThat(deletedSecond.path("fileRemoved").asBoolean()).isTrue();
        JsonNode consistency = data(HttpMethod.GET, "/api/v1/system/files", null, true);
        assertThat(consistency.path("missingFiles")).isEmpty();
        assertThat(consistency.path("retentionHours").asInt()).isEqualTo(24);
    }

    // ------------------------------------------------------------ 边界

    @Test
    @DisplayName("格式白名单与鉴权：非文本格式 400 DOCUMENT_FORMAT_UNSUPPORTED，无令牌 401")
    void rejectsUnsupportedFormatAndAnonymousAccess() throws Exception {
        Path pdf = writeSource("试卷.pdf", "%PDF-1.4 not really a pdf");
        ResponseEntity<String> unsupported = exchange(HttpMethod.POST, "/api/v1/documents/import",
                importBody(pdf, "reject"), true);
        assertThat(unsupported.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(unsupported.getBody()).path("error").path("code").asText())
                .isEqualTo("DOCUMENT_FORMAT_UNSUPPORTED");

        Path txt = writeSource("匿名.txt", sampleText("匿名导入"));
        ResponseEntity<String> anonymous = exchange(HttpMethod.POST, "/api/v1/documents/import",
                importBody(txt, "reject"), false);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("索引状态与能力：报告 FTS5 可用性、索引名、文档与分块计数")
    void reportsIndexState() throws Exception {
        JsonNode state = data(HttpMethod.GET, "/api/v1/search/index", null, true);
        assertThat(state.path("indexName").asText()).isEqualTo("document_fts");
        assertThat(state.path("fts5Available").asBoolean()).isTrue();
        assertThat(state.path("activeVersion").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(state.path("documentCount").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(state.path("chunkCount").asLong()).isGreaterThanOrEqualTo(0);
    }

    // ------------------------------------------------------------ 辅助

    /**
     * 生成一份足够长的资料：两段各约 900 字，跨过 800 字装箱目标，因此必然产生 ≥2 个分块。
     * {@code marker} 是本次测试独有的标记词，保证检索断言只可能命中本次导入的资料。
     */
    private String sampleText(String marker) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            builder.append(marker).append("：三次握手确保双方收发能力与初始序号同步，这是可靠传输的起点。");
        }
        builder.append("\n\n");
        for (int i = 0; i < 30; i++) {
            builder.append("滑动窗口用于流量控制，窗口大小决定了在途未确认数据的上限。");
        }
        return builder.toString();
    }

    private Path writeSource(String name, String content) throws IOException {
        Path file = SOURCE_DIR.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private Map<String, Object> importBody(Path source, String onDuplicate) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourcePath", source.toAbsolutePath().toString());
        body.put("sha256", sha256(source));
        body.put("title", source.getFileName().toString().replaceAll("\\.[^.]+$", ""));
        body.put("onDuplicate", onDuplicate);
        return body;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest(Files.readAllBytes(file))) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String raw) {
        return java.net.URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private HttpHeaders headers(boolean withToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (withToken) {
            headers.set("X-Session-Token", securityFilter.sessionToken());
        }
        return headers;
    }

    /**
     * 用 {@link java.net.URI} 直接发起请求：路径里已经做过百分号编码（含中文检索词），
     * 若交给 URI 模板工厂再展开一次会产生双重编码，检索词就永远匹配不上。
     */
    private ResponseEntity<String> exchange(HttpMethod method, String path, Object body, boolean withToken)
            throws IOException {
        String payload = body == null ? null : (body instanceof String text ? text : mapper.writeValueAsString(body));
        java.net.URI uri = java.net.URI.create("http://127.0.0.1:" + port + path);
        return rest.exchange(uri, method, new HttpEntity<>(payload, headers(withToken)), String.class);
    }

    private JsonNode data(HttpMethod method, String path, Object body, boolean withToken) throws IOException {
        ResponseEntity<String> response = exchange(method, path, body, withToken);
        JsonNode envelope = mapper.readTree(response.getBody());
        assertThat(envelope.path("success").asBoolean())
                .as("接口应返回成功：" + path + " → " + envelope).isTrue();
        return envelope.path("data");
    }
}
