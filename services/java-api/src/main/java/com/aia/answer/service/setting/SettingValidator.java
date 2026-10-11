package com.aia.answer.service.setting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.PathType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * setting.json 校验器。
 *
 * <p>Schema 唯一来源是 {@code contracts/json-schema/setting.schema.json}：
 * 构建期由 {@code services/java-api/pom.xml} 的 {@code contracts/json-schema} 资源目录
 * 整体复制进 classpath，因此这里以 classpath 资源方式加载，绝不复制一份到 Java 侧。
 *
 * <p>除 Schema 之外还要检查两类纯 JSON Schema 表达不了的跨字段规则：
 * <ul>
 *   <li>{@code llm.profiles[].id} 在同一文件内唯一；</li>
 *   <li>{@code llm.defaultProfileId} 必须指向存在的 profile（null/缺失表示不使用默认档案）。</li>
 * </ul>
 *
 * <p>安全边界：错误信息与日志只包含 JSON 路径与 Schema 提示，不回显字段值
 * （字段值可能是 {@code apiKey}，绝不能进入日志或异常消息）。
 */
@Component
public class SettingValidator {

    private static final Logger log = LoggerFactory.getLogger(SettingValidator.class);
    private static final String SCHEMA_RESOURCE = "contracts/json-schema/setting.schema.json";

    /** 支持的配置版本；不匹配一律拒绝加载。 */
    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper mapper;
    private volatile JsonSchema schema;
    /** Schema 全文的属性名集合（用于判断属性名是否可回显），懒加载后缓存。 */
    private volatile Set<String> knownProperties;

    public SettingValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public SettingValidator() {
        this(new ObjectMapper());
    }

    /** 校验结果。{@code errors} 中永远不含字段值。 */
    public record Result(boolean valid, List<Error> errors) {

        public Result {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public static Result ok() {
            return new Result(true, List.of());
        }
    }

    /** 单条校验错误：{@code path} 形如 {@code $.llm.profiles[0].provider}。 */
    public record Error(String path, String message) {
    }

    /** 校验一个配置对象。传入 {@code null} 视为非法。 */
    public Result validate(JsonNode config) {
        List<Error> errors = new ArrayList<>();
        if (config == null || !config.isObject()) {
            errors.add(new Error("$", "配置根节点必须是 JSON 对象"));
            return new Result(false, errors);
        }
        int version = config.path("schemaVersion").asInt(-1);
        if (version != SCHEMA_VERSION) {
            errors.add(new Error("$.schemaVersion", "不支持的配置版本："
                    + (version < 0 ? "缺失或类型错误" : version) + "，当前仅支持 " + SCHEMA_VERSION));
            return new Result(false, errors);
        }
        for (ValidationMessage message : schema().validate(config)) {
            errors.add(toError(message));
        }
        errors.addAll(crossFieldErrors(config));
        return new Result(errors.isEmpty(), errors);
    }

    /** 跨字段规则：id 唯一 + defaultProfileId 必须存在。 */
    private List<Error> crossFieldErrors(JsonNode config) {
        List<Error> errors = new ArrayList<>();
        JsonNode profiles = config.path("llm").path("profiles");
        Set<String> ids = new LinkedHashSet<>();
        if (profiles.isArray()) {
            int index = 0;
            for (JsonNode profile : profiles) {
                String id = profile.path("id").asText("");
                if (!id.isBlank() && !ids.add(id)) {
                    errors.add(new Error("$.llm.profiles[" + index + "].id", "档案 id 重复：" + id));
                }
                index++;
            }
        }
        JsonNode defaultNode = config.path("llm").path("defaultProfileId");
        if (defaultNode.isTextual()) {
            String defaultId = defaultNode.asText("");
            if (!defaultId.isBlank() && !ids.contains(defaultId)) {
                errors.add(new Error("$.llm.defaultProfileId", "defaultProfileId 未指向任何档案：" + defaultId));
            }
        }
        return errors;
    }

    /**
     * 把 networknt 的校验消息转成「可读路径 + 可读消息」。
     *
     * <p>{@code additionalProperties} 是唯一需要特殊处理的断言，原因见
     * {@link #sanitizeProperty(String)}：它的位置停在父对象、消息里又重复了属性名。
     */
    private Error toError(ValidationMessage message) {
        if (!"additionalProperties".equals(message.getMessageKey())) {
            return new Error(instancePath(message), messageOf(message));
        }
        String property = message.getProperty();
        if (property == null || property.isBlank()) {
            property = disallowedProperty(message);
        }
        boolean defined = isDefinedProperty(property);
        String rendered = defined ? property : "<未知属性>";
        return new Error(appendProperty(instancePath(message), rendered),
                "该位置不接受属性：" + rendered
                        + "（" + (defined ? "字段层级写错了" : "且该属性名未在 Schema 中定义，故不回显") + "）");
    }

    /**
     * 属性名是否在 Schema 里定义过（任意层级，含 {@code $defs}）。
     *
     * <p>只有定义过的名字才回显：这样「把 {@code baseUrl} 写到顶层」「还在用旧字段 {@code apiKeyRef}」
     * 这类层级错误能被点名。用户完全可能把密钥粘成了键名，任意回显等于把密钥写进 warning 与日志，
     * 因此未定义的一律折叠成不透明占位符。判定只看键名是否出现过，属性名本身不是秘密。
     */
    private boolean isDefinedProperty(String property) {
        return property != null && !property.isBlank() && knownProperties().contains(property);
    }

    /** 收集 Schema 全文出现过的所有属性名（只做一次，之后缓存）。 */
    private Set<String> knownProperties() {
        Set<String> cached = knownProperties;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (knownProperties == null) {
                Set<String> names = new java.util.LinkedHashSet<>();
                collectPropertyNames(schema().getSchemaNode(), names);
                knownProperties = Set.copyOf(names);
            }
            return knownProperties;
        }
    }

    private void collectPropertyNames(JsonNode node, Set<String> names) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            node.path("properties").fieldNames().forEachRemaining(names::add);
            node.properties().forEach(entry -> collectPropertyNames(entry.getValue(), names));
        } else if (node.isArray()) {
            node.forEach(child -> collectPropertyNames(child, names));
        }
    }

    /** {@code /llm/profiles/0/provider} → {@code $.llm.profiles[0].provider}。 */
    private String instancePath(ValidationMessage message) {
        String pointer = message.getInstanceLocation() == null ? "" : message.getInstanceLocation().toString();
        return "$" + pointer.replace("/", ".").replaceAll("\\.(\\d+)", "[$1]");
    }

    /**
     * 错误消息。
     *
     * <p>枚举违规时把<b>被拒的值</b>拼进消息，否则用户只看到「枚举中没有值」却不知道是哪一个值；
     * 该值只取自 enum/const 这类白名单断言（永远不是 {@code apiKey}、{@code baseUrl} 这类自由文本），
     * 因此不会让密钥进入日志或异常消息。
     */
    private String messageOf(ValidationMessage message) {
        String text = message.getMessage() == null ? "" : message.getMessage();
        if (!"enum".equals(message.getMessageKey()) && !"const".equals(message.getMessageKey())) {
            return text;
        }
        JsonNode offending = message.getInstanceNode();
        if (offending == null || offending.isNull() || offending.isContainerNode()) {
            return text;
        }
        String value = offending.asText();
        return value.isBlank() ? text : "值 \"" + value + "\" 不在允许范围内：" + text;
    }

    /**
     * 从 additionalProperties 消息里兜底取被拒的属性名（正常路径走 {@link ValidationMessage#getProperty()}）。
     *
     * <p>只取键名，绝不把字段值带进错误信息：{@code apiKey} 这类字段的值不得进入日志或异常。
     * 消息模板随语言变化（英文 {@code extraneous key [x]}、中文 {@code 未定义属性“x”}），
     * 因此这里同时接受方括号与中英文引号三种书写。
     */
    private String disallowedProperty(ValidationMessage message) {
        String text = message.getMessage() == null ? "" : message.getMessage();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\[([^\\[\\]]+)]|\"([^\"]+)\"|“([^”]+)”").matcher(text);
        if (!matcher.find()) {
            return null;
        }
        for (int group = 1; group <= matcher.groupCount(); group++) {
            if (matcher.group(group) != null) {
                return matcher.group(group);
            }
        }
        return null;
    }

    private String appendProperty(String path, String property) {
        if (property == null || property.isBlank()) {
            return path;
        }
        return "$".equals(path) ? "$." + property : path + "." + property;
    }

    private JsonSchema schema() {
        JsonSchema current = schema;
        if (current == null) {
            synchronized (this) {
                current = schema;
                if (current == null) {
                    try (InputStream in = new ClassPathResource(SCHEMA_RESOURCE).getInputStream()) {
                        JsonNode node = mapper.readTree(in);
                        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                                .pathType(PathType.JSON_POINTER)
                                .build();
                        current = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                                .getSchema(node, config);
                        schema = current;
                    } catch (IOException e) {
                        // 只报资源名与原因，不含任何配置内容
                        throw new IllegalStateException("无法加载配置 Schema：" + SCHEMA_RESOURCE, e);
                    }
                }
            }
        }
        return current;
    }
}
