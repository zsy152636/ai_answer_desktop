package com.aia.answer.service;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.Json;
import com.aia.answer.common.PageResult;
import com.aia.answer.domain.AttemptDtos;
import com.aia.answer.domain.WrongDtos;
import com.aia.answer.repository.WrongQuestionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 错题本服务（契约 §5，开发文档 §5.3）。
 *
 * <p>题目改义会产生新版本、新错题记录；列表里同时给出「当前版本号」与「错题版本号」，
 * 让用户看得见版本差异，而不是把不同语义的错题混在一起统计。
 */
@Service
public class WrongQuestionService {

    private static final int REVIEW_PAGE_SIZE = 20;

    private final WrongQuestionRepository repository;
    private final WrongScheduler scheduler;
    private final AttemptService attemptService;
    private final Json json;

    public WrongQuestionService(WrongQuestionRepository repository, WrongScheduler scheduler,
                                AttemptService attemptService, Json json) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.attemptService = attemptService;
        this.json = json;
    }

    public PageResult<WrongDtos.WrongQuestionView> list(WrongDtos.Query query) {
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        PageResult.validate(page, size);
        String now = nowUtc();
        long total = repository.count(query, now);
        List<WrongDtos.WrongQuestionView> items = repository.listPage(query, (page - 1) * size, size, now).stream()
                .map(this::toView).toList();
        return PageResult.of(items, page, size, total);
    }

    public WrongDtos.WrongQuestionDetail detail(String id) {
        WrongQuestionRepository.WrongQuestionRow row = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("错题 " + id));
        List<WrongDtos.ReviewRecord> reviews = repository.listReviews(id, REVIEW_PAGE_SIZE).stream()
                .map(r -> new WrongDtos.ReviewRecord(r.id(), r.result(), r.note(), r.intervalDaysBefore(),
                        r.intervalDaysAfter(), r.nextReviewAt(), r.durationSeconds(), r.reviewedAt()))
                .toList();
        return new WrongDtos.WrongQuestionDetail(toView(row), reviews);
    }

    @Transactional
    public WrongDtos.WrongQuestionDetail review(String id, WrongDtos.ReviewRequest request) {
        boolean correct = "correct".equals(request.result());
        scheduler.applyReview(id, correct, request.note(), request.attemptAnswerId(), request.durationSeconds(),
                nowUtc());
        return detail(id);
    }

    @Transactional
    public WrongDtos.WrongQuestionDetail patchStatus(String id, String status) {
        WrongQuestionRepository.WrongQuestionRow row = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("错题 " + id));
        String now = nowUtc();
        String nextReviewAt = row.nextReviewAt();
        if ("mastered".equals(status) || "archived".equals(status)) {
            nextReviewAt = null;
        } else if ("active".equals(status) && nextReviewAt == null) {
            nextReviewAt = scheduler.nextReviewAt(Math.max(1, row.wrongCount()), now);
        }
        repository.updateStatus(id, status, nextReviewAt, now);
        return detail(id);
    }

    /** 错题再练习：复用答题链路，生成 practice 快照与作答会话。 */
    public AttemptDtos.AttemptSheet practice(WrongDtos.PracticeRequest request) {
        List<String> ids = request == null ? null : request.wrongQuestionIds();
        Integer limit = request == null ? null : request.limit();
        return attemptService.start(new AttemptDtos.StartRequest(null, "wrong_practice", ids, limit));
    }

    private WrongDtos.WrongQuestionView toView(WrongQuestionRepository.WrongQuestionRow row) {
        return new WrongDtos.WrongQuestionView(row.id(), row.questionId(), row.questionVersionId(), row.questionType(),
                row.title(), row.stem(), row.status(), row.wrongCount(), row.correctStreak(), row.lastWrongAt(),
                row.nextReviewAt(), row.lastScore(), row.maxScore(),
                json.convertList(row.knowledgeTagsJson(), String.class), row.currentVersionNo(), row.wrongVersionNo(),
                row.createdAt(), row.updatedAt());
    }

    private static String nowUtc() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
