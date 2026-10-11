package com.aia.answer.service;

import com.aia.answer.common.Json;
import com.aia.answer.repository.AttemptRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 作答总分重算。
 *
 * <p>单独抽出来是因为它有两个触发点：确定性判分完成时，以及主观题被 AI 或人工评分之后。
 * 让两条路径共用同一份汇总逻辑，避免出现「AI 打完分但总分没变」这类不一致。
 */
@Component
public class AttemptTotalsCalculator {

    private final AttemptRepository attemptRepository;
    private final Json json;

    public AttemptTotalsCalculator(AttemptRepository attemptRepository, Json json) {
        this.attemptRepository = attemptRepository;
        this.json = json;
    }

    public double[] recompute(String attemptId) {
        double total = 0;
        double max = 0;
        for (AttemptRepository.AnswerRow row : attemptRepository.listAnswers(attemptId)) {
            max += row.maxScore();
            if (row.score() != null) {
                total += row.score();
            }
        }
        double roundedTotal = round(total);
        double roundedMax = round(max);
        attemptRepository.markGraded(attemptId, roundedTotal, roundedMax,
                DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS)));
        return new double[]{roundedTotal, roundedMax};
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** 供诊断日志使用的作答摘要（不含答案内容）。 */
    public String describe(String attemptId) {
        var rows = attemptRepository.listAnswers(attemptId);
        return json.write(java.util.Map.of("answerCount", rows.size(),
                "pendingReview", rows.stream().filter(r -> "pending_review".equals(r.gradingStatus())).count()));
    }

    /** 便于外部读取单题分值。 */
    public JsonNode maxScoreOf(String attemptId, String snapshotQuestionId) {
        return attemptRepository.findAnswer(attemptId, snapshotQuestionId)
                .map(row -> (JsonNode) json.mapper().valueToTree(row.maxScore()))
                .orElse(null);
    }
}
