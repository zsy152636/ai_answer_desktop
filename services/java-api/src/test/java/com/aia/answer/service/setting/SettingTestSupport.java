package com.aia.answer.service.setting;

import com.aia.answer.common.Json;
import com.aia.answer.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * setting.json 相关测试的公共装配与样例（真实临时文件，不发网络请求）。
 *
 * <p>刻意不在样例里硬编码真实密钥形态：测试用的 {@code apiKey} 只是占位字符串，
 * 断言也只检查「不落日志 / 不误写到别处」，不校验任何真实凭据语义。
 */
final class SettingTestSupport {

    static final String SAMPLE_KEY = "sk-test-not-a-real-key";

    private SettingTestSupport() {
    }

    /** 指向临时数据目录的 AppProperties。 */
    static AppProperties properties(Path dataDir) {
        AppProperties props = new AppProperties();
        props.setDataDir(dataDir.toString());
        return props;
    }

    static Json json() {
        return new Json(new ObjectMapper());
    }

    static SettingStore store(Path dataDir) {
        return new SettingStore(properties(dataDir), json());
    }

    static SettingValidator validator() {
        return new SettingValidator(new ObjectMapper());
    }

    /** 一个最小合法配置：单档案 + 默认档案。 */
    static ObjectNode validConfig(String profileId, String model, String apiKey) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.putObject("application").put("language", "zh-CN");
        ObjectNode llm = root.putObject("llm");
        llm.put("defaultProfileId", profileId);
        ArrayNode profiles = llm.putArray("profiles");
        ObjectNode profile = profiles.addObject();
        profile.put("id", profileId);
        profile.put("name", "本地 Ollama");
        profile.put("provider", "ollama");
        profile.put("baseUrl", "http://127.0.0.1:11434/v1");
        profile.put("model", model);
        profile.put("temperature", 0.2);
        profile.put("timeoutSeconds", 60);
        profile.put("connectTimeoutSeconds", 5);
        profile.put("allowLocalNetwork", true);
        if (apiKey != null) {
            profile.put("apiKey", apiKey);
        }
        profile.putObject("parameters").put("stream", true);
        return root;
    }

    /** 追加一个档案（用于并发保存用例）。 */
    static void addProfile(ObjectNode root, String id) {
        ArrayNode profiles = (ArrayNode) root.path("llm").path("profiles");
        ObjectNode profile = profiles.addObject();
        profile.put("id", id);
        profile.put("name", "档案 " + id);
        profile.put("provider", "openai-compatible");
        profile.put("baseUrl", "https://api.example.com/v1");
        profile.put("model", "gpt-4o-mini");
    }

    static String pretty(ObjectNode node) {
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void writeRaw(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    static JsonNode parse(Path file) throws IOException {
        return new ObjectMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** 目录下残留的原子写临时文件；正常情况必须为空。 */
    static List<String> tempFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream.map(path -> path.getFileName().toString())
                    .filter(name -> name.contains(".tmp"))
                    .sorted()
                    .toList();
        }
    }
}
