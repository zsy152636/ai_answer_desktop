package com.aia.answer.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/** 错题闭环传输对象（契约 api-contract.md §5）。 */
public final class WrongDtos {

    private WrongDtos() {
    }

    public record WrongQuestionView(
            String id,
            String questionId,
            String questionVersionId,
            String questionType,
            String title,
            String stem,
            String status,
            int wrongCount,
            int correctStreak,
            String lastWrongAt,
            String nextReviewAt,
            Double lastScore,
            Double maxScore,
            List<String> knowledgeTags,
            int currentVersionNo,
            int wrongVersionNo,
            String createdAt,
            String updatedAt) {
    }

    public record ReviewRecord(
            String id,
            String result,
            String note,
            Integer intervalDaysBefore,
            Integer intervalDaysAfter,
            String nextReviewAt,
            Integer durationSeconds,
            String reviewedAt) {
    }

    public record WrongQuestionDetail(WrongQuestionView question, List<ReviewRecord> reviews) {
    }

    public record ReviewRequest(
            @NotBlank(message = "复习结果不能为空")
            @Pattern(regexp = "correct|wrong", message = "复习结果只能是 correct 或 wrong") String result,
            String note,
            Integer durationSeconds,
            String attemptAnswerId) {
    }

    public record PracticeRequest(List<String> wrongQuestionIds, Integer limit) {
    }

    public record StatusRequest(
            @NotBlank(message = "状态不能为空")
            @Pattern(regexp = "active|reviewing|mastered|archived", message = "状态不在允许范围内") String status) {
    }

    public record Query(Integer page, Integer size, String status, Boolean dueOnly, String tag,
                        String questionType, String sort) {
    }
}
