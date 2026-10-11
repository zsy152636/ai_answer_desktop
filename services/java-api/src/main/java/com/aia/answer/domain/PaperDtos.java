package com.aia.answer.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 试卷与发布快照的传输对象（契约 api-contract.md §3）。 */
public final class PaperDtos {

    private PaperDtos() {
    }

    public record PaperSummary(
            String id,
            String title,
            String status,
            int questionCount,
            double totalScore,
            String publishedAt,
            String createdAt,
            String updatedAt,
            String latestSnapshotId) {
    }

    public record PaperQuestionView(
            String id,
            String questionId,
            String questionVersionId,
            int orderNo,
            double score,
            String title,
            String questionType,
            int versionNo) {
    }

    public record PaperView(
            String id,
            String title,
            String description,
            String status,
            int questionCount,
            double totalScore,
            String publishedAt,
            String createdAt,
            String updatedAt,
            String latestSnapshotId,
            List<PaperQuestionView> questions) {
    }

    public record QuestionRef(
            @NotBlank(message = "questionId 不能为空") String questionId,
            String questionVersionId,
            Double score) {
    }

    public record SaveRequest(
            @NotBlank(message = "试卷标题不能为空") @Size(max = 200, message = "标题最长 200 字") String title,
            @Size(max = 1000, message = "说明最长 1000 字") String description,
            List<QuestionRef> questions) {
    }

    public record SnapshotQuestionView(
            String id,
            int orderNo,
            double score,
            String questionId,
            String questionVersionId,
            String questionType,
            String stem,
            List<QuestionDtos.OptionView> options,
            JsonNode answer,
            String explanation,
            JsonNode rubric,
            List<String> knowledgeTags) {
    }

    public record PaperSnapshotView(
            String id,
            String paperId,
            int versionNo,
            String title,
            String description,
            int questionCount,
            double totalScore,
            String publishedAt,
            List<SnapshotQuestionView> questions) {
    }

    public record Query(Integer page, Integer size, String q, String status) {
    }
}
