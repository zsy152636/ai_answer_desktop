package com.aia.answer.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * JSON 读写薄封装。所有持久化的 *_json 列都经过这里，保证 UTF-8 与
 * 「失败即抛业务异常」的统一语义（禁止把 null 静默写库）。
 */
@Component
public class Json {

    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "JSON 序列化失败");
        }
    }

    public String writePretty(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "JSON 序列化失败");
        }
    }

    /** 语法错误映射为 CONFIG_JSON_INVALID（契约 §0.3）。 */
    public JsonNode readTree(String raw, String field) {
        try {
            return mapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.CONFIG_JSON_INVALID,
                    "JSON 语法错误：第 " + (e.getLocation() == null ? "?" : e.getLocation().getLineNr())
                            + " 行第 " + (e.getLocation() == null ? "?" : e.getLocation().getColumnNr()) + " 列",
                    field == null ? List.of() : List.of(new ApiException.FieldViolation(field, "invalid json")));
        }
    }

    public JsonNode readTreeOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public <T> T convert(JsonNode node, Class<T> type) {
        try {
            return mapper.treeToValue(node, type);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "JSON 反序列化失败");
        }
    }

    public <T> T convert(String raw, Class<T> type) {
        try {
            return mapper.readValue(raw, type);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "JSON 反序列化失败");
        }
    }

    public <T> List<T> convertList(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(raw, mapper.getTypeFactory().constructCollectionType(List.class, type));
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "JSON 反序列化失败");
        }
    }

    public <T> Map<String, T> convertMap(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(raw, mapper.getTypeFactory().constructMapType(Map.class, String.class, type));
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "JSON 反序列化失败");
        }
    }

    public ObjectNode object() {
        return mapper.createObjectNode();
    }

    public ArrayNode array() {
        return mapper.createArrayNode();
    }
}
