package com.aia.answer.service;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.AppInfo;
import com.aia.answer.common.ErrorCode;
import com.aia.answer.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 确定性判分（开发文档 §5.2，契约 §4.1）。
 *
 * <p>设计原则：<b>判分不依赖 AI</b>。单选/判断/多选靠集合比较，填空靠显式归一化规则，
 * 只有简答/论述才标记为待 AI 或人工复核。每次判分都记录规则版本与命中的归一化规则，
 * 使历史结果可复现、可追溯。
 */
@Service
public class GradingService {

    private static final Pattern PUNCTUATION = Pattern.compile("\\p{P}");

    private final Json json;

    public GradingService(Json json) {
        this.json = json;
    }

    public boolean isObjective(String questionType) {
        return switch (questionType) {
            case "single_choice", "multiple_choice", "true_false", "fill_blank" -> true;
            default -> false;
        };
    }

    /** 判分结果。score 为 null 表示待复核（主观题）。 */
    public record Outcome(Double score, String gradingMethod, String gradingStatus, ObjectNode detail) {

        public boolean fullMark() {
            return score != null;
        }
    }

    public Outcome grade(String questionType, JsonNode answerKey, double maxScore, JsonNode userRaw) {
        JsonNode userValue = extractUserValue(userRaw);
        ObjectNode detail = json.object();
        detail.put("ruleVersion", AppInfo.GRADING_RULE_VERSION);
        detail.put("questionType", questionType);
        detail.set("expected", answerKey);
        detail.set("actual", userValue == null || userValue.isMissingNode() ? json.mapper().nullNode() : userValue);

        if (!isObjective(questionType)) {
            String method = answerKey != null && answerKey.hasNonNull("rubric") ? "ai_rubric" : "manual";
            detail.put("note", "主观题由 AI rubric 评分或人工复核，提交阶段只记录作答");
            return new Outcome(null, method, "pending_review", detail);
        }

        if (isEmpty(userValue)) {
            detail.put("note", "未作答");
            detail.put("matched", false);
            return new Outcome(0.0, "ungraded", "graded", detail);
        }

        return switch (questionType) {
            case "single_choice" -> gradeSingleChoice(answerKey, userValue, maxScore, detail);
            case "true_false" -> gradeTrueFalse(answerKey, userValue, maxScore, detail);
            case "multiple_choice" -> gradeMultipleChoice(answerKey, userValue, maxScore, detail);
            case "fill_blank" -> gradeFillBlank(answerKey, userValue, maxScore, detail);
            default -> throw new ApiException(ErrorCode.INTERNAL_ERROR, "未知题型：" + questionType);
        };
    }

    // ------------------------------------------------------------- 各题型规则

    private Outcome gradeSingleChoice(JsonNode answerKey, JsonNode userValue, double maxScore, ObjectNode detail) {
        String expected = text(answerKey.get("value"));
        String actual = userValue.isArray()
                ? (userValue.size() > 0 ? text(userValue.get(0)) : null)
                : text(userValue);
        boolean matched = expected != null && expected.equalsIgnoreCase(actual == null ? "" : actual);
        detail.put("matched", matched);
        detail.put("expectedKey", expected);
        detail.put("actualKey", actual);
        return new Outcome(matched ? maxScore : 0.0, "exact", "graded", detail);
    }

    private Outcome gradeTrueFalse(JsonNode answerKey, JsonNode userValue, double maxScore, ObjectNode detail) {
        Boolean expected = asBoolean(answerKey.get("value"));
        Boolean actual = asBoolean(userValue);
        boolean matched = expected != null && expected.equals(actual);
        detail.put("matched", matched);
        detail.put("expectedBool", expected);
        detail.put("actualBool", actual);
        return new Outcome(matched ? maxScore : 0.0, "exact", "graded", detail);
    }

    private Outcome gradeMultipleChoice(JsonNode answerKey, JsonNode userValue, double maxScore, ObjectNode detail) {
        Set<String> expected = keySet(answerKey.get("value"));
        Set<String> actual = keySet(userValue);
        boolean partialCredit = answerKey.path("partialCredit").asBoolean(false);

        Set<String> hits = new LinkedHashSet<>(expected);
        hits.retainAll(actual);
        Set<String> misses = new LinkedHashSet<>(expected);
        misses.removeAll(actual);
        Set<String> wrongs = new LinkedHashSet<>(actual);
        wrongs.removeAll(expected);

        detail.put("expectedKeys", String.join(",", expected));
        detail.put("actualKeys", String.join(",", actual));
        detail.put("hitKeys", String.join(",", hits));
        detail.put("missKeys", String.join(",", misses));
        detail.put("wrongKeys", String.join(",", wrongs));

        if (misses.isEmpty() && wrongs.isEmpty()) {
            detail.put("matched", true);
            return new Outcome(maxScore, "exact", "graded", detail);
        }
        if (partialCredit && !expected.isEmpty()) {
            double ratio = (double) (hits.size() - wrongs.size()) / (double) expected.size();
            double clamped = Math.max(0.0, Math.min(1.0, ratio));
            detail.put("partialCredit", true);
            detail.put("partialRatio", round(clamped));
            detail.put("matched", false);
            return new Outcome(round(maxScore * clamped), "partial_credit", "graded", detail);
        }
        detail.put("matched", false);
        return new Outcome(0.0, "exact", "graded", detail);
    }

    private Outcome gradeFillBlank(JsonNode answerKey, JsonNode userValue, double maxScore, ObjectNode detail) {
        JsonNode blanks = answerKey.get("blanks");
        if (blanks == null || !blanks.isArray() || blanks.isEmpty()) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "填空题标准答案缺少 blanks");
        }
        List<String> userBlanks = flattenTexts(userValue);
        ArrayNode matches = json.array();
        int hitCount = 0;
        int index = 0;
        for (JsonNode blank : blanks) {
            index++;
            String userText = index - 1 < userBlanks.size() ? userBlanks.get(index - 1) : null;
            JsonNode normalizeConfig = blank.get("normalize");
            String normalizedUser = userText == null ? null : normalizeText(userText, normalizeConfig);
            String usedAccepted = null;
            if (normalizedUser != null && !normalizedUser.isEmpty()) {
                for (JsonNode accepted : blank.path("accepted")) {
                    String normalizedAccepted = normalizeText(accepted.asText(), normalizeConfig);
                    if (normalizedUser.equals(normalizedAccepted)) {
                        usedAccepted = accepted.asText();
                        break;
                    }
                }
            }
            ObjectNode item = json.object();
            item.put("index", index);
            item.put("userText", userText == null ? "" : userText);
            item.put("normalizedUser", normalizedUser == null ? "" : normalizedUser);
            item.put("hit", usedAccepted != null);
            if (usedAccepted != null) {
                item.put("matchedAccepted", usedAccepted);
                hitCount++;
            }
            item.set("normalization", normalizationReport(normalizeConfig));
            matches.add(item);
        }
        double ratio = (double) hitCount / (double) blanks.size();
        detail.set("blanks", matches);
        detail.put("hitCount", hitCount);
        detail.put("blankCount", blanks.size());
        detail.put("normalizationRule", "trim+ignoreCase+ignorePunctuation+ignoreWhitespace（以题目配置为准）");
        detail.put("matched", hitCount == blanks.size());
        return new Outcome(round(maxScore * ratio), "normalized", "graded", detail);
    }

    // ------------------------------------------------------------- 归一化

    /**
     * 文本归一化：全角转半角 → 去标点 → 去空白 → 大小写处理。
     * 具体开关取自题目 {@code normalize} 配置，缺省全部开启（契约 §4.1）。
     */
    public String normalizeText(String value, JsonNode config) {
        if (value == null) {
            return null;
        }
        boolean trim = flag(config, "trim", true);
        boolean ignoreCase = flag(config, "ignoreCase", true);
        boolean ignorePunctuation = flag(config, "ignorePunctuation", true);
        boolean ignoreWhitespace = flag(config, "ignoreWhitespace", true);

        String result = toHalfWidth(value).strip();
        if (ignorePunctuation) {
            result = PUNCTUATION.matcher(result).replaceAll("");
        }
        if (ignoreWhitespace) {
            result = result.replaceAll("\\s+", "");
        } else if (trim) {
            result = result.strip();
        }
        if (ignoreCase) {
            result = result.toLowerCase(Locale.ROOT);
        }
        return result;
    }

    private ObjectNode normalizationReport(JsonNode config) {
        ObjectNode node = json.object();
        node.put("trim", flag(config, "trim", true));
        node.put("ignoreCase", flag(config, "ignoreCase", true));
        node.put("ignorePunctuation", flag(config, "ignorePunctuation", true));
        node.put("ignoreWhitespace", flag(config, "ignoreWhitespace", true));
        return node;
    }

    private boolean flag(JsonNode config, String name, boolean defaultValue) {
        if (config == null || !config.isObject() || !config.has(name)) {
            return defaultValue;
        }
        return config.get(name).asBoolean(defaultValue);
    }

    private String toHalfWidth(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (char c : input.toCharArray()) {
            if (c == 0x3000) {
                sb.append(' ');
            } else if (c >= 0xFF01 && c <= 0xFF5E) {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- 取值与校验

    /** 兼容 {@code {"value":...}}、{@code {"blanks":[...]}} 与裸值三种作答形态。 */
    public JsonNode extractUserValue(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            return null;
        }
        if (raw.isObject()) {
            if (raw.has("value")) {
                return raw.get("value");
            }
            if (raw.has("blanks")) {
                return raw.get("blanks");
            }
            if (raw.has("answers")) {
                return raw.get("answers");
            }
        }
        return raw;
    }

    public boolean isEmpty(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return true;
        }
        if (value.isTextual()) {
            return value.asText().isBlank();
        }
        if (value.isArray() || value.isObject()) {
            return value.isEmpty();
        }
        return false;
    }

    /**
     * 题型答案结构校验 + 规范化。返回规范化后的答案节点（写库的唯一权威）。
     *
     * <p>选项正确性由本方法按 {@code answer.value} 推导，请求里 {@code options[].correct}
     * 仅作为展示字段，不参与存储，避免出现两个正确答案权威来源（开发文档 §4.3 注）。
     */
    public JsonNode canonicalAnswer(String questionType, JsonNode answer, List<String> optionKeys) {
        if (answer == null || answer.isNull()) {
            throw ApiException.validation("标准答案不能为空", "answer");
        }
        ObjectNode canonical = json.object();
        switch (questionType) {
            case "single_choice", "multiple_choice" -> {
                Set<String> keys = new TreeSet<>(optionKeys);
                if (questionType.equals("single_choice")) {
                    String value = text(answer.get("value"));
                    if (value == null || value.isBlank()) {
                        throw ApiException.validation("单选题必须提供 answer.value", "answer.value");
                    }
                    if (!keys.contains(value)) {
                        throw ApiException.validation("answer.value 必须命中已有选项：" + value, "answer.value");
                    }
                    canonical.put("type", "single_choice");
                    canonical.put("value", value);
                } else {
                    Set<String> selected = keySet(answer.get("value"));
                    if (selected.isEmpty()) {
                        throw ApiException.validation("多选题 answer.value 至少需要一个选项", "answer.value");
                    }
                    for (String key : selected) {
                        if (!keys.contains(key)) {
                            throw ApiException.validation("answer.value 含不存在的选项：" + key, "answer.value");
                        }
                    }
                    canonical.put("type", "multiple_choice");
                    ArrayNode array = canonical.putArray("value");
                    selected.forEach(array::add);
                    canonical.put("partialCredit", answer.path("partialCredit").asBoolean(false));
                }
            }
            case "true_false" -> {
                Boolean value = asBoolean(answer.get("value"));
                if (value == null) {
                    throw ApiException.validation("判断题 answer.value 必须是布尔值", "answer.value");
                }
                canonical.put("type", "true_false");
                canonical.put("value", value);
            }
            case "fill_blank" -> {
                JsonNode blanks = answer.get("blanks");
                if (blanks == null || !blanks.isArray() || blanks.isEmpty()) {
                    throw ApiException.validation("填空题至少需要一个空", "answer.blanks");
                }
                if (blanks.size() > 20) {
                    throw ApiException.validation("填空题最多支持 20 个空", "answer.blanks");
                }
                canonical.put("type", "fill_blank");
                ArrayNode out = canonical.putArray("blanks");
                int index = 0;
                for (JsonNode blank : blanks) {
                    index++;
                    List<String> accepted = new ArrayList<>();
                    for (JsonNode item : blank.path("accepted")) {
                        if (!item.asText().isBlank()) {
                            accepted.add(item.asText());
                        }
                    }
                    if (accepted.isEmpty()) {
                        throw ApiException.validation("第 " + index + " 个空必须至少有一个可接受答案", "answer.blanks");
                    }
                    ObjectNode item = json.object();
                    item.put("index", index);
                    ArrayNode acceptedNode = item.putArray("accepted");
                    accepted.forEach(acceptedNode::add);
                    if (blank.has("normalize") && blank.get("normalize").isObject()) {
                        item.set("normalize", blank.get("normalize"));
                    }
                    out.add(item);
                }
            }
            default -> {
                String reference = text(answer.get("referenceAnswer"));
                canonical.put("type", questionType);
                if (reference != null) {
                    canonical.put("referenceAnswer", reference);
                }
                if (answer.has("rubric") && answer.get("rubric").isObject()) {
                    canonical.set("rubric", answer.get("rubric"));
                } else if (!canonical.has("rubric")) {
                    throw ApiException.validation("简答/论述题必须提供 rubric 或 referenceAnswer", "answer.rubric");
                }
            }
        }
        if (answer.has("type") && !answer.get("type").asText().equals(questionType)) {
            throw ApiException.validation("answer.type 与题型不一致", "answer.type");
        }
        return canonical;
    }

    public Set<String> keySet(JsonNode value) {
        Set<String> keys = new TreeSet<>();
        if (value == null || value.isNull()) {
            return keys;
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                String text = text(item);
                if (text != null && !text.isBlank()) {
                    keys.add(text.trim().toUpperCase(Locale.ROOT));
                }
            }
        } else {
            String text = text(value);
            if (text != null && !text.isBlank()) {
                for (String part : text.split("[,，;；\\s]+")) {
                    if (!part.isBlank()) {
                        keys.add(part.trim().toUpperCase(Locale.ROOT));
                    }
                }
            }
        }
        return keys;
    }

    private List<String> flattenTexts(JsonNode value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isNull()) {
            return result;
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                result.add(item.isNull() ? "" : item.asText());
            }
        } else if (value.isObject()) {
            List<String> keys = new ArrayList<>();
            value.fieldNames().forEachRemaining(keys::add);
            keys.sort((a, b) -> {
                try {
                    return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
                } catch (NumberFormatException e) {
                    return a.compareTo(b);
                }
            });
            for (String key : keys) {
                JsonNode item = value.get(key);
                result.add(item.isNull() ? "" : item.asText());
            }
        } else {
            result.add(value.asText());
        }
        return result;
    }

    public Boolean asBoolean(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        String text = node.asText("").trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "true", "t", "1", "yes", "y", "对", "正确" -> true;
            case "false", "f", "0", "no", "n", "错", "错误" -> false;
            default -> null;
        };
    }

    private String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.asText(null);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
