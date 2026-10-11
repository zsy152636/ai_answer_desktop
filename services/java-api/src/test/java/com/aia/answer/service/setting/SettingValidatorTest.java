package com.aia.answer.service.setting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * setting schema 校验测试。
 *
 * <p>Schema 只从 classpath 的 {@code contracts/json-schema/setting.schema.json} 加载，
 * 因此本测试同时是「构建期资源复制是否生效」的守门人：
 * 一旦 pom 里的 contracts 资源目录被改动，这里会立刻失败。
 */
class SettingValidatorTest {

    private final SettingValidator validator = SettingTestSupport.validator();

    @Test
    @DisplayName("合法配置通过，且不产生任何错误路径")
    void acceptsValidConfig() {
        SettingValidator.Result result = validator.validate(
                SettingTestSupport.validConfig("p1", "qwen2.5:7b", SettingTestSupport.SAMPLE_KEY));

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("未知 provider（含旧表里的 anthropic）被拒，路径指向 provider")
    void rejectsUnknownProvider() {
        ObjectNode config = SettingTestSupport.validConfig("p1", "qwen2.5:7b", null);
        ((ObjectNode) config.path("llm").path("profiles").get(0)).put("provider", "anthropic");

        SettingValidator.Result result = validator.validate(config);

        assertThat(result.valid()).isFalse();
        // 路径指向具体字段，消息里点名被拒的值（用户据此知道该改成什么）
        assertThat(result.errors()).anySatisfy(error -> {
            assertThat(error.path()).isEqualTo("$.llm.profiles[0].provider");
            assertThat(error.message()).contains("anthropic", "openai-compatible", "ollama");
        });
    }

    @Test
    @DisplayName("缺必填字段被拒：路径停在所属对象上，消息里点名缺失字段")
    void rejectsMissingRequiredField() {
        ObjectNode config = SettingTestSupport.validConfig("p1", "qwen2.5:7b", null);
        ((ObjectNode) config.path("llm").path("profiles").get(0)).remove("model");

        SettingValidator.Result result = validator.validate(config);

        assertThat(result.valid()).isFalse();
        // required 是对象级约束：位置停在 profile 上，字段名在消息里
        assertThat(result.errors()).anySatisfy(error -> {
            assertThat(error.path()).isEqualTo("$.llm.profiles[0]");
            assertThat(error.message()).contains("model");
        });
    }

    @Test
    @DisplayName("类型错误被拒（temperature 为字符串）")
    void rejectsWrongType() {
        ObjectNode config = SettingTestSupport.validConfig("p1", "qwen2.5:7b", null);
        ((ObjectNode) config.path("llm").path("profiles").get(0)).put("temperature", "很热");

        SettingValidator.Result result = validator.validate(config);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.path()).isEqualTo("$.llm.profiles[0].temperature"));
    }

    @Test
    @DisplayName("additionalProperties 违规在三层都被拒：Schema 里认识的字段点名，未知属性名折叠")
    void rejectsAdditionalProperties() {
        // 顶层：baseUrl 是 Schema 里认识的字段（只是层级写错了），必须点名
        ObjectNode topLevel = SettingTestSupport.validConfig("p1", "qwen2.5:7b", null);
        topLevel.put("baseUrl", "http://127.0.0.1:11434/v1");
        assertThat(validator.validate(topLevel).errors())
                .anySatisfy(error -> {
                    assertThat(error.path()).isEqualTo("$.baseUrl");
                    assertThat(error.message()).contains("baseUrl");
                });

        // llm 层：profiles 的兄弟位置
        ObjectNode llmLevel = SettingTestSupport.validConfig("p1", "qwen2.5:7b", null);
        ((ObjectNode) llmLevel.path("llm")).put("timeoutSeconds", 60);
        assertThat(validator.validate(llmLevel).errors())
                .anySatisfy(error -> assertThat(error.path()).isEqualTo("$.llm.timeoutSeconds"));

        // profile 层：旧契约里的 apiKeyRef/credentialRef 已经不在 Schema 里了，一律折叠不透明
        ObjectNode profileLevel = SettingTestSupport.validConfig("p1", "qwen2.5:7b", null);
        ((ObjectNode) profileLevel.path("llm").path("profiles").get(0)).put("apiKeyRef", "credential://llm/p1");
        assertThat(validator.validate(profileLevel).errors())
                .anySatisfy(error -> assertThat(error.path()).isEqualTo("$.llm.profiles[0].<未知属性>"));
    }

    @Test
    @DisplayName("Schema 未定义的属性名不回显：避免把「粘成键名的密钥」写进 path 与日志")
    void neverEchoesUnknownPropertyNames() {
        ObjectNode config = SettingTestSupport.validConfig("p1", "qwen2.5:7b", SettingTestSupport.SAMPLE_KEY);
        ((ObjectNode) config.path("llm").path("profiles").get(0)).put(SettingTestSupport.SAMPLE_KEY, 1);

        SettingValidator.Result result = validator.validate(config);

        assertThat(result.valid()).isFalse();
        // 只有 Schema 里出现过的属性名（如写错层级的 baseUrl、旧字段 apiKeyRef）才回显，
        // 用户完全可能把密钥粘成键名，任意回显等于把密钥写进 warning 与日志
        assertThat(result.errors()).allSatisfy(error -> {
            assertThat(error.path()).doesNotContain(SettingTestSupport.SAMPLE_KEY);
            assertThat(error.message()).doesNotContain(SettingTestSupport.SAMPLE_KEY);
        });
        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.path()).isEqualTo("$.llm.profiles[0].<未知属性>"));
    }
    @Test
    @DisplayName("版本不符、根节点非对象、id 重复与 defaultProfileId 悬空都被拒")
    void rejectsVersionAndCrossFieldViolations() {
        ObjectNode wrongVersion = SettingTestSupport.validConfig("p1", "qwen2.5:7b", null);
        wrongVersion.put("schemaVersion", 2);
        assertThat(validator.validate(wrongVersion).errors())
                .anySatisfy(error -> assertThat(error.path()).isEqualTo("$.schemaVersion"));

        assertThat(validator.validate(new ObjectMapper().createArrayNode()).valid()).isFalse();
        assertThat(validator.validate(null).valid()).isFalse();

        ObjectNode duplicated = SettingTestSupport.validConfig("p1", "qwen2.5:7b", null);
        SettingTestSupport.addProfile(duplicated, "p1");
        assertThat(validator.validate(duplicated).errors())
                .anySatisfy(error -> assertThat(error.path()).isEqualTo("$.llm.profiles[1].id"));

        ObjectNode danglingDefault = SettingTestSupport.validConfig("p1", "qwen2.5:7b", null);
        ((ObjectNode) danglingDefault.path("llm")).put("defaultProfileId", "p-missing");
        assertThat(validator.validate(danglingDefault).errors())
                .anySatisfy(error -> assertThat(error.path()).isEqualTo("$.llm.defaultProfileId"));
    }

    @Test
    @DisplayName("校验错误信息不回显字段值中的密钥")
    void errorMessagesNeverEchoSecrets() {
        // baseUrl 非法时错误信息只描述规则，不应把整段对象值打出来
        ObjectNode config = SettingTestSupport.validConfig("p1", "qwen2.5:7b", SettingTestSupport.SAMPLE_KEY);
        ((ObjectNode) config.path("llm").path("profiles").get(0)).put("timeoutSeconds", 100000);

        SettingValidator.Result result = validator.validate(config);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).allSatisfy(error ->
                assertThat(error.message()).doesNotContain(SettingTestSupport.SAMPLE_KEY));
    }
}
