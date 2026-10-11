package com.aia.answer.service;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 确定性判分规则单测（开发文档 §5.2、§12.1「Java 单元测试：判分规则」）。
 *
 * <p>这些用例是判分规则的活文档：任何规则调整都必须先在这里体现，并同步提升
 * {@code AppInfo.GRADING_RULE_VERSION}。
 */
class GradingServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Json json = new Json(mapper);
    private final GradingService grading = new GradingService(json);

    private JsonNode node(String raw) {
        return json.readTree(raw, null);
    }

    // ------------------------------------------------------------ 单选 / 判断

    @Test
    @DisplayName("单选题：选项一致得满分，不一致得 0，未作答记 ungraded")
    void singleChoice() {
        JsonNode key = node("{\"type\":\"single_choice\",\"value\":\"C\"}");

        GradingService.Outcome correct = grading.grade("single_choice", key, 5, node("\"C\""));
        assertThat(correct.score()).isEqualTo(5.0);
        assertThat(correct.gradingMethod()).isEqualTo("exact");
        assertThat(correct.gradingStatus()).isEqualTo("graded");

        GradingService.Outcome wrong = grading.grade("single_choice", key, 5, node("\"A\""));
        assertThat(wrong.score()).isZero();
        assertThat(wrong.detail().path("matched").asBoolean()).isFalse();

        GradingService.Outcome blank = grading.grade("single_choice", key, 5, node("\"\""));
        assertThat(blank.score()).isZero();
        assertThat(blank.gradingMethod()).isEqualTo("ungraded");
    }

    @Test
    @DisplayName("单选题：兼容 {\"value\":\"C\"} 与数组包装两种作答形态")
    void singleChoiceAnswerShapes() {
        JsonNode key = node("{\"type\":\"single_choice\",\"value\":\"C\"}");
        assertThat(grading.grade("single_choice", key, 5, node("{\"value\":\"c\"}")).score()).isEqualTo(5.0);
        assertThat(grading.grade("single_choice", key, 5, node("[\"C\"]")).score()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("判断题：布尔与中英文文本都归一化")
    void trueFalse() {
        JsonNode key = node("{\"type\":\"true_false\",\"value\":true}");
        assertThat(grading.grade("true_false", key, 2, node("true")).score()).isEqualTo(2.0);
        assertThat(grading.grade("true_false", key, 2, node("\"对\"")).score()).isEqualTo(2.0);
        assertThat(grading.grade("true_false", key, 2, node("false")).score()).isZero();
    }

    // ------------------------------------------------------------ 多选

    @Test
    @DisplayName("多选题：默认全对给分，漏选或多选都得 0")
    void multipleChoiceAllOrNothing() {
        JsonNode key = node("{\"type\":\"multiple_choice\",\"value\":[\"A\",\"C\"],\"partialCredit\":false}");
        assertThat(grading.grade("multiple_choice", key, 6, node("[\"C\",\"A\"]")).score()).isEqualTo(6.0);
        assertThat(grading.grade("multiple_choice", key, 6, node("[\"A\"]")).score()).isZero();
        assertThat(grading.grade("multiple_choice", key, 6, node("[\"A\",\"B\",\"C\"]")).score()).isZero();
    }

    @Test
    @DisplayName("多选题：开启部分分时按 (命中-错误)/正确数 归一化并夹取到 [0,1]")
    void multipleChoicePartialCredit() {
        JsonNode key = node("{\"type\":\"multiple_choice\",\"value\":[\"A\",\"B\",\"C\",\"D\"],\"partialCredit\":true}");
        GradingService.Outcome half = grading.grade("multiple_choice", key, 8, node("[\"A\",\"B\"]"));
        assertThat(half.score()).isEqualTo(4.0);
        assertThat(half.gradingMethod()).isEqualTo("partial_credit");

        GradingService.Outcome negative = grading.grade("multiple_choice", key, 8, node("[\"A\",\"X\",\"Y\"]"));
        assertThat(negative.score()).isZero();
    }

    // ------------------------------------------------------------ 填空

    @Test
    @DisplayName("填空题：大小写、全角、标点、空白都按归一化规则比较")
    void fillBlankNormalization() {
        JsonNode key = node("""
                {"type":"fill_blank","blanks":[
                  {"index":1,"accepted":["foreign_keys"],"normalize":{"ignoreCase":true,"ignorePunctuation":true,"ignoreWhitespace":true}}
                ]}""");
        assertThat(grading.grade("fill_blank", key, 4, node("[\"FOREIGN KEYS\"]")).score()).isEqualTo(4.0);
        assertThat(grading.grade("fill_blank", key, 4, node("[\"foreign_keys.\"]")).score()).isEqualTo(4.0);
        assertThat(grading.grade("fill_blank", key, 4, node("[\"ｆｏｒｅｉｇｎ＿ｋｅｙｓ\"]")).score()).isEqualTo(4.0);
        assertThat(grading.grade("fill_blank", key, 4, node("[\"busy_timeout\"]")).score()).isZero();
    }

    @Test
    @DisplayName("填空题：多空按命中比例给分，并记录每个空的命中情况")
    void fillBlankPartial() {
        JsonNode key = node("""
                {"type":"fill_blank","blanks":[
                  {"index":1,"accepted":["A"]},
                  {"index":2,"accepted":["B"]}
                ]}""");
        GradingService.Outcome outcome = grading.grade("fill_blank", key, 10, node("[\"a\",\"x\"]"));
        assertThat(outcome.score()).isEqualTo(5.0);
        assertThat(outcome.gradingMethod()).isEqualTo("normalized");
        assertThat(outcome.detail().path("hitCount").asInt()).isEqualTo(1);
        assertThat(outcome.detail().path("blanks").get(0).path("hit").asBoolean()).isTrue();
        assertThat(outcome.detail().path("blanks").get(1).path("hit").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("填空题：按序号键值对象作答也能对齐")
    void fillBlankIndexedObject() {
        JsonNode key = node("""
                {"type":"fill_blank","blanks":[
                  {"index":1,"accepted":["甲"]},
                  {"index":2,"accepted":["乙"]}
                ]}""");
        assertThat(grading.grade("fill_blank", key, 4, node("{\"2\":\"乙\",\"1\":\"甲\"}")).score()).isEqualTo(4.0);
    }

    // ------------------------------------------------------------ 主观题

    @Test
    @DisplayName("主观题：提交阶段只标记待复核，不给分")
    void subjectivePendingReview() {
        JsonNode withRubric = node("{\"type\":\"short_answer\",\"rubric\":{\"criteria\":[]}}");
        GradingService.Outcome outcome = grading.grade("short_answer", withRubric, 10, node("\"我的回答\""));
        assertThat(outcome.score()).isNull();
        assertThat(outcome.gradingStatus()).isEqualTo("pending_review");
        assertThat(outcome.gradingMethod()).isEqualTo("ai_rubric");

        JsonNode withoutRubric = node("{\"type\":\"essay\"}");
        assertThat(grading.grade("essay", withoutRubric, 10, node("\"x\"")).gradingMethod()).isEqualTo("manual");
    }

    // ------------------------------------------------------------ 答案规范化与校验

    @Test
    @DisplayName("答案校验：单选题答案必须命中已有选项")
    void canonicalSingleChoiceValidation() {
        assertThat(grading.canonicalAnswer("single_choice",
                node("{\"type\":\"single_choice\",\"value\":\"B\"}"), List.of("A", "B")).path("value").asText())
                .isEqualTo("B");
        assertThatThrownBy(() -> grading.canonicalAnswer("single_choice",
                node("{\"type\":\"single_choice\",\"value\":\"Z\"}"), List.of("A", "B")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("必须命中已有选项");
    }

    @Test
    @DisplayName("答案校验：多选题选项去重排序，填空题至少一个可接受答案")
    void canonicalMultipleAndFillBlank() {
        JsonNode multi = grading.canonicalAnswer("multiple_choice",
                node("{\"type\":\"multiple_choice\",\"value\":[\"C\",\"A\",\"A\"]}"), List.of("A", "B", "C"));
        assertThat(multi.get("value").size()).isEqualTo(2);
        assertThat(multi.get("value").get(0).asText()).isEqualTo("A");
        assertThat(multi.get("value").get(1).asText()).isEqualTo("C");

        assertThatThrownBy(() -> grading.canonicalAnswer("fill_blank",
                node("{\"type\":\"fill_blank\",\"blanks\":[{\"accepted\":[\"  \"]}]}"), List.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("至少有一个可接受答案");
    }

    @Test
    @DisplayName("答案校验：题型与 answer.type 不一致必须报错")
    void canonicalTypeMismatch() {
        assertThatThrownBy(() -> grading.canonicalAnswer("true_false",
                node("{\"type\":\"single_choice\",\"value\":true}"), List.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("与题型不一致");
    }

    @Test
    @DisplayName("题型分类：只有客观题走确定性判分")
    void objectiveClassification() {
        assertThat(grading.isObjective("single_choice")).isTrue();
        assertThat(grading.isObjective("multiple_choice")).isTrue();
        assertThat(grading.isObjective("true_false")).isTrue();
        assertThat(grading.isObjective("fill_blank")).isTrue();
        assertThat(grading.isObjective("short_answer")).isFalse();
        assertThat(grading.isObjective("essay")).isFalse();
    }

    @Test
    @DisplayName("判分明细必须带规则版本，保证历史结果可解释")
    void detailCarriesRuleVersion() {
        ArrayNode blanks = mapper.createArrayNode();
        ObjectNode key = mapper.createObjectNode();
        key.put("type", "fill_blank");
        key.set("blanks", blanks);
        GradingService.Outcome outcome = grading.grade("single_choice",
                node("{\"type\":\"single_choice\",\"value\":\"A\"}"), 1, node("\"A\""));
        assertThat(outcome.detail().path("ruleVersion").asText())
                .isEqualTo(com.aia.answer.common.AppInfo.GRADING_RULE_VERSION);
    }
}
