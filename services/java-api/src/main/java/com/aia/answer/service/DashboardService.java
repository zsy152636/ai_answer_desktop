package com.aia.answer.service;

import com.aia.answer.common.Json;
import com.aia.answer.domain.AttemptDtos;
import com.aia.answer.domain.SystemDtos;
import com.aia.answer.domain.WrongDtos;
import com.aia.answer.repository.AttemptRepository;
import com.aia.answer.repository.PaperRepository;
import com.aia.answer.repository.QuestionRepository;
import com.aia.answer.repository.WrongQuestionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 首页仪表盘聚合（契约 §1）。
 *
 * <p>刻意只做只读聚合，把「继续答题 / 待复习 / 最近记录」三块信息一次取回，避免首屏串行多次请求。
 */
@Service
public class DashboardService {

    private static final int RECENT_LIMIT = 5;

    private final QuestionRepository questionRepository;
    private final PaperRepository paperRepository;
    private final AttemptRepository attemptRepository;
    private final WrongQuestionRepository wrongQuestionRepository;
    private final AiClient aiClient;
    private final Json json;

    public DashboardService(QuestionRepository questionRepository, PaperRepository paperRepository,
                            AttemptRepository attemptRepository, WrongQuestionRepository wrongQuestionRepository,
                            AiClient aiClient, Json json) {
        this.questionRepository = questionRepository;
        this.paperRepository = paperRepository;
        this.attemptRepository = attemptRepository;
        this.wrongQuestionRepository = wrongQuestionRepository;
        this.aiClient = aiClient;
        this.json = json;
    }

    public SystemDtos.DashboardView dashboard() {
        String now = nowUtc();
        List<AttemptDtos.AttemptSummary> recentAttempts = attemptRepository.listRecent(RECENT_LIMIT).stream()
                .map(row -> new AttemptDtos.AttemptSummary(row.id(), row.paperId(), row.paperSnapshotId(),
                        row.paperTitle(), row.mode(), row.status(), row.totalScore(), row.maxScore(),
                        row.questionCount(), row.startedAt(), row.submittedAt(), row.gradedAt()))
                .toList();
        List<WrongDtos.WrongQuestionView> recentWrong = wrongQuestionRepository.listRecent(RECENT_LIMIT).stream()
                .map(row -> new WrongDtos.WrongQuestionView(row.id(), row.questionId(), row.questionVersionId(),
                        row.questionType(), row.title(), row.stem(), row.status(), row.wrongCount(),
                        row.correctStreak(), row.lastWrongAt(), row.nextReviewAt(), row.lastScore(), row.maxScore(),
                        json.convertList(row.knowledgeTagsJson(), String.class), row.currentVersionNo(),
                        row.wrongVersionNo(), row.createdAt(), row.updatedAt()))
                .toList();
        return new SystemDtos.DashboardView(
                (int) questionRepository.countAll(),
                (int) questionRepository.countByStatus("published"),
                (int) paperRepository.countAll(),
                (int) paperRepository.countByStatus("published"),
                (int) attemptRepository.countByStatus("in_progress"),
                (int) wrongQuestionRepository.countDue(now),
                (int) wrongQuestionRepository.countByStatus("active"),
                (int) wrongQuestionRepository.countByStatus("mastered"),
                recentAttempts, recentWrong, aiClient.status());
    }

    private static String nowUtc() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
