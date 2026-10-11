package com.aia.answer.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 答题与判分传输对象（契约 api-contract.md §4）。 */
public final class AttemptDtos {

    private AttemptDtos() {
    }

    public record StartRequest(
            String paperId,
            String mode,
            List<String> wrongQuestionIds,
            Integer limit) {
    }

    public record AttemptSummary(
            String id,
            String paperId,
            String paperSnapshotId,
            String paperTitle,
            String mode,
            String status,
            Double totalScore,
            Double maxScore,
            int questionCount,
            String startedAt,
            String submittedAt,
            String gradedAt) {
    }

    public record SheetQuestion(
            String snapshotQuestionId,
            int orderNo,
            double score,
            String questionType,
            String stem,
            List<QuestionDtos.OptionView> options,
            List<String> knowledgeTags,
            JsonNode answer,
            String explanation) {
    }

    public record AnswerView(
            String snapshotQuestionId,
            String questionId,
            String questionVersionId,
            JsonNode answer,
            Double score,
            double maxScore,
            String gradingMethod,
            String gradingStatus,
            JsonNode gradingDetail,
            String savedAt) {
    }

    public record AttemptSheet(
            String id,
            String paperId,
            String paperSnapshotId,
            String paperTitle,
            String mode,
            String status,
            Double totalScore,
            Double maxScore,
            String startedAt,
            String submittedAt,
            String gradedAt,
            List<SheetQuestion> questions,
            List<AnswerView> answers) {
    }

    public record ResultItem(
            String snapshotQuestionId,
            int orderNo,
            String questionType,
            String stem,
            List<QuestionDtos.OptionView> options,
            JsonNode userAnswer,
            JsonNode correctAnswer,
            String explanation,
            Double score,
            double maxScore,
            boolean correct,
            String gradingMethod,
            String gradingStatus,
            JsonNode gradingDetail,
            List<String> knowledgeTags) {
    }

    public record AttemptResult(
            AttemptSummary attempt,
            List<ResultItem> items,
            int correctCount,
            int wrongCount,
            int unansweredCount,
            int pendingReviewCount) {
    }

    public record SaveAnswerRequest(@NotNull(message = "answer 不能为空") JsonNode answer) {
    }

    /** 主观题人工评分（AI 建议分的复核入口）。 */
    public record ManualGradeRequest(
            @NotNull(message = "score 不能为空") Double score,
            String note) {
    }

    public record AiGradeResponse(List<String> taskIds, int pendingCount) {
    }
}
