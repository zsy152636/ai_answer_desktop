package com.aia.answer.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 题库与题目版本的传输对象（契约 api-contract.md §2）。 */
public final class QuestionDtos {

    private QuestionDtos() {
    }

    public record OptionView(String key, String content, boolean correct) {
    }

    public record VersionBrief(String id, int versionNo, String createdAt, String changeNote) {
    }

    public record QuestionSummary(
            String id,
            String title,
            String questionType,
            String status,
            int difficulty,
            int currentVersionNo,
            List<String> knowledgeTags,
            String updatedAt) {
    }

    public record QuestionVersionView(
            String id,
            int versionNo,
            String stem,
            List<OptionView> options,
            JsonNode answer,
            String explanation,
            JsonNode rubric,
            List<String> knowledgeTags,
            String changeNote,
            String createdAt) {
    }

    public record QuestionView(
            String id,
            String title,
            String questionType,
            String status,
            int difficulty,
            QuestionVersionView currentVersion,
            List<VersionBrief> versions,
            String createdAt,
            String updatedAt) {
    }

    /** 建题 / 改题请求。字段级规则见 QuestionService.validateContent。 */
    public record SaveRequest(
            @NotBlank(message = "题干标题不能为空") @Size(max = 200, message = "标题最长 200 字") String title,
            @NotBlank(message = "题型不能为空")
            @Pattern(regexp = "single_choice|multiple_choice|true_false|fill_blank|short_answer|essay",
                    message = "题型不在允许范围内") String questionType,
            @Min(value = 1, message = "难度最小为 1") @Max(value = 5, message = "难度最大为 5") Integer difficulty,
            @NotBlank(message = "题干不能为空") @Size(max = 5000, message = "题干最长 5000 字") String stem,
            List<OptionView> options,
            @NotNull(message = "标准答案不能为空") JsonNode answer,
            @Size(max = 5000, message = "解析最长 5000 字") String explanation,
            JsonNode rubric,
            List<@Size(max = 32, message = "单个标签最长 32 字") String> knowledgeTags,
            @Size(max = 500, message = "变更说明最长 500 字") String changeNote,
            Integer expectedVersionNo) {
    }

    /** 分页查询条件（过滤参数白名单，见 QuestionService.list）。 */
    public record Query(
            Integer page,
            Integer size,
            String q,
            String questionType,
            String status,
            String tag,
            String sort) {
    }
}
