package com.aia.answer.service;

import com.aia.answer.common.Ids;
import com.aia.answer.common.Json;
import com.aia.answer.repository.MetaRepository;
import com.aia.answer.repository.WrongQuestionRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 错题登记与复习调度（开发文档 §5.3）。
 *
 * <p>调度刻意使用可解释的固定间隔阶梯（1 → 3 → 7 → 15 天），而不是黑箱算法：
 * MVP 阶段必须能用一句话解释「为什么这道题今天出现」。
 */
@Service
public class WrongScheduler {

    private static final int[] INTERVAL_DAYS = {1, 3, 7, 15};

    private final WrongQuestionRepository repository;
    private final MetaRepository metaRepository;
    private final Json json;

    public WrongScheduler(WrongQuestionRepository repository, MetaRepository metaRepository, Json json) {
        this.repository = repository;
        this.metaRepository = metaRepository;
        this.json = json;
    }

    /** 答错登记：按 question_version_id 去重，答错次数累加，状态回到 active。 */
    public String registerWrong(String questionId, String questionVersionId, Double score, double maxScore,
                                String attemptAnswerId, String now) {
        Optional<WrongQuestionRepository.WrongQuestionRow> existing = repository.findRowByVersion(questionVersionId);
        int nextCount = existing.map(row -> row.wrongCount() + 1).orElse(1);
        String nextReviewAt = nextReviewAt(nextCount, now);
        String id = repository.upsertOnWrong(Ids.of("wq"), questionId, questionVersionId, score, maxScore,
                now, nextReviewAt, now);
        metaRepository.audit("wrong_question.registered", "wrong_question", id,
                json.write(java.util.Map.of("wrongCount", nextCount, "attemptAnswerId",
                        attemptAnswerId == null ? "" : attemptAnswerId)), now);
        return id;
    }

    /** 复习结果：正确两次即掌握；答错则回到 active 并重新计间隔。 */
    public ReviewOutcome applyReview(String wrongQuestionId, boolean correct, String note, String attemptAnswerId,
                                     Integer durationSeconds, String now) {
        WrongQuestionRepository.WrongQuestionRow row = repository.findById(wrongQuestionId)
                .orElseThrow(() -> com.aia.answer.common.ApiException.notFound("错题 " + wrongQuestionId));
        Integer beforeDays = row.nextReviewAt() == null ? null : intervalDaysFor(row.wrongCount());
        String status;
        int streak;
        String nextReviewAt;
        if (correct) {
            streak = row.correctStreak() + 1;
            if (streak >= 2) {
                status = "mastered";
                nextReviewAt = null;
            } else {
                status = "reviewing";
                nextReviewAt = nextReviewAt(row.wrongCount(), now);
            }
        } else {
            streak = 0;
            status = "active";
            nextReviewAt = nextReviewAt(row.wrongCount() + 1, now);
        }
        repository.applyReview(wrongQuestionId, status, streak, nextReviewAt, now);
        repository.insertReview(Ids.of("wqr"), wrongQuestionId, attemptAnswerId, correct ? "correct" : "wrong",
                note, beforeDays, nextReviewAt == null ? null : intervalDaysFor(row.wrongCount() + (correct ? 0 : 1)),
                nextReviewAt, durationSeconds, now);
        metaRepository.audit("wrong_question.reviewed", "wrong_question", wrongQuestionId,
                json.write(java.util.Map.of("result", correct ? "correct" : "wrong", "status", status,
                        "correctStreak", streak)), now);
        return new ReviewOutcome(status, streak, nextReviewAt, beforeDays,
                nextReviewAt == null ? null : intervalDaysFor(row.wrongCount() + (correct ? 0 : 1)));
    }

    /** 间隔阶梯：第 n 次答错后安排的天数。 */
    public int intervalDays(int wrongCount) {
        int index = Math.max(0, Math.min(INTERVAL_DAYS.length - 1, wrongCount - 1));
        return INTERVAL_DAYS[index];
    }

    public String nextReviewAt(int wrongCount, String fromIso) {
        Instant base = Instant.parse(fromIso);
        return DateTimeFormatter.ISO_INSTANT.format(base.plus(Duration.ofDays(intervalDays(wrongCount)))
                .truncatedTo(ChronoUnit.SECONDS));
    }

    private Integer intervalDaysFor(int wrongCount) {
        return intervalDays(Math.max(1, wrongCount));
    }

    /** 复习结果。 */
    public record ReviewOutcome(String status, int correctStreak, String nextReviewAt, Integer intervalDaysBefore,
                                Integer intervalDaysAfter) {
    }
}
