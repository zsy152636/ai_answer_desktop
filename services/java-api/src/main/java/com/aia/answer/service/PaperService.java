package com.aia.answer.service;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.ErrorCode;
import com.aia.answer.common.Ids;
import com.aia.answer.common.Json;
import com.aia.answer.common.PageResult;
import com.aia.answer.domain.PaperDtos;
import com.aia.answer.domain.QuestionDtos;
import com.aia.answer.repository.MetaRepository;
import com.aia.answer.repository.PaperRepository;
import com.aia.answer.repository.QuestionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 试卷服务：草稿组成、发布快照、历史快照读取（契约 §3，开发文档 §5.1）。
 *
 * <p>发布动作会为每道题写入不可变 {@code snapshot_json}。判分一律读快照，
 * 因此题目后续改版不会影响任何历史作答与历史结果。
 */
@Service
public class PaperService {

    public static final double DEFAULT_SCORE = 5.0;
    private static final double MAX_SCORE = 100.0;

    private final PaperRepository repository;
    private final QuestionRepository questionRepository;
    private final QuestionService questionService;
    private final MetaRepository metaRepository;
    private final Json json;

    public PaperService(PaperRepository repository, QuestionRepository questionRepository,
                        QuestionService questionService, MetaRepository metaRepository, Json json) {
        this.repository = repository;
        this.questionRepository = questionRepository;
        this.questionService = questionService;
        this.metaRepository = metaRepository;
        this.json = json;
    }

    // ------------------------------------------------------------- 查询

    public PageResult<PaperDtos.PaperSummary> list(PaperDtos.Query query) {
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        PageResult.validate(page, size);
        long total = repository.count(query);
        List<PaperDtos.PaperSummary> items = repository.listPage(query, (page - 1) * size, size).stream()
                .map(this::toSummary)
                .toList();
        return PageResult.of(items, page, size, total);
    }

    public PaperDtos.PaperView get(String id) {
        PaperRepository.PaperRow paper = requirePaper(id);
        List<PaperDtos.PaperQuestionView> questions;
        if (paper.currentSnapshotId() != null) {
            questions = repository.listSnapshotQuestions(paper.currentSnapshotId()).stream()
                    .map(row -> {
                        JsonNode node = json.readTree(row.snapshotJson(), "snapshot_json");
                        return new PaperDtos.PaperQuestionView(row.id(), node.path("questionId").asText(),
                                node.path("questionVersionId").asText(), row.orderNo(), row.score(),
                                node.path("title").asText(), node.path("questionType").asText(),
                                node.path("versionNo").asInt());
                    })
                    .toList();
        } else {
            questions = repository.listPaperQuestions(id).stream()
                    .map(row -> new PaperDtos.PaperQuestionView(row.id(), row.questionId(), row.questionVersionId(),
                            row.orderNo(), row.score(), row.title(), row.questionType(), row.versionNo()))
                    .toList();
        }
        double totalScore = questions.stream().mapToDouble(PaperDtos.PaperQuestionView::score).sum();
        return new PaperDtos.PaperView(paper.id(), paper.title(), paper.description(), paper.status(),
                questions.size(), round(totalScore), paper.publishedAt(), paper.createdAt(), paper.updatedAt(),
                paper.currentSnapshotId(), questions);
    }

    public PaperDtos.PaperSnapshotView getSnapshot(String paperId, String snapshotId) {
        requirePaper(paperId);
        PaperRepository.SnapshotRow snapshot = repository.findSnapshot(snapshotId)
                .orElseThrow(() -> ApiException.notFound("试卷快照 " + snapshotId));
        if (!paperId.equals(snapshot.paperId())) {
            throw ApiException.notFound("试卷快照 " + snapshotId);
        }
        return toSnapshotView(snapshot);
    }

    public List<PaperDtos.PaperSnapshotView> listSnapshots(String paperId) {
        requirePaper(paperId);
        return repository.listSnapshots(paperId).stream().map(this::toSnapshotView).toList();
    }

    /** 供答题服务读取快照题目（含标准答案）。 */
    public List<PaperDtos.SnapshotQuestionView> snapshotQuestions(String snapshotId) {
        return repository.listSnapshotQuestions(snapshotId).stream().map(this::parseSnapshotQuestion).toList();
    }

    // ------------------------------------------------------------- 写入

    @Transactional
    public PaperDtos.PaperView create(PaperDtos.SaveRequest request) {
        String paperId = Ids.of("p");
        String now = nowUtc();
        repository.insertPaper(paperId, request.title().trim(), blankToNull(request.description()), "draft", now);
        replaceQuestions(paperId, request.questions());
        metaRepository.audit("paper.created", "paper", paperId, "{}", now);
        return get(paperId);
    }

    @Transactional
    public PaperDtos.PaperView update(String id, PaperDtos.SaveRequest request) {
        PaperRepository.PaperRow paper = requirePaper(id);
        if (!"draft".equals(paper.status())) {
            throw ApiException.conflict("只有草稿状态的试卷可以修改题目组成，已发布试卷请先归档后新建");
        }
        String now = nowUtc();
        repository.updatePaper(id, request.title().trim(), blankToNull(request.description()), now);
        replaceQuestions(id, request.questions());
        return get(id);
    }

    @Transactional
    public PaperDtos.PaperView archive(String id) {
        PaperRepository.PaperRow paper = requirePaper(id);
        if ("archived".equals(paper.status())) {
            return get(id);
        }
        repository.updateStatus(id, "archived", nowUtc());
        metaRepository.audit("paper.archived", "paper", id, "{}", nowUtc());
        return get(id);
    }

    @Transactional
    public PaperDtos.PaperSnapshotView publish(String id) {
        PaperRepository.PaperRow paper = requirePaper(id);
        if ("archived".equals(paper.status())) {
            throw ApiException.conflict("已归档试卷不能发布");
        }
        if (paper.currentSnapshotId() != null) {
            // 已发布试卷重复发布：返回当前快照，保持幂等
            PaperRepository.SnapshotRow existing = repository.findSnapshot(paper.currentSnapshotId())
                    .orElseThrow(() -> ApiException.notFound("试卷快照"));
            return toSnapshotView(existing);
        }
        List<PaperRepository.PaperQuestionDetail> drafts = repository.listPaperQuestions(id);
        if (drafts.isEmpty()) {
            throw ApiException.conflict("试卷至少需要一道题目才能发布");
        }
        String now = nowUtc();
        PaperRepository.SnapshotRow snapshot = buildSnapshot(id, "paper", paper.title(), paper.description(),
                drafts.stream().map(d -> new SnapshotItem(d.questionVersionId(), d.score())).toList(), now);
        repository.setCurrentSnapshot(id, snapshot.id(), now);
        metaRepository.audit("paper.published", "paper", id,
                json.write(java.util.Map.of("snapshotId", snapshot.id(), "versionNo", snapshot.versionNo())), now);
        return toSnapshotView(snapshot);
    }

    /**
     * 生成「错题练习」临时快照。
     *
     * <p>练习不属于任何试卷，因此 {@code paper_id} 为 NULL、{@code kind='practice'}，
     * 这样答题链路可以完全复用试卷快照的判分与结果结构，而不必引入第二套数据模型。
     */
    @Transactional
    public PaperRepository.SnapshotRow buildPracticeSnapshot(String title, List<SnapshotItem> items) {
        if (items.isEmpty()) {
            throw ApiException.conflict("没有可练习的错题");
        }
        return buildSnapshot(null, "practice", title, null, items, nowUtc());
    }

    private PaperRepository.SnapshotRow buildSnapshot(String paperId, String kind, String title, String description,
                                                      List<SnapshotItem> items, String now) {
        List<PaperRepository.SnapshotQuestionRow> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        double total = 0;
        int order = 0;
        String snapshotId = Ids.of("sn");
        for (SnapshotItem item : items) {
            order++;
            QuestionService.VersionContent content = questionService.loadContent(item.questionVersionId());
            if ("archived".equals(content.questionStatus())) {
                throw ApiException.conflict("题目「" + content.title() + "」已归档，不能加入试卷");
            }
            if (!seen.add(content.questionVersionId())) {
                throw ApiException.conflict("同一题目版本不能在一张试卷中重复出现：" + content.title());
            }
            double score = item.score() == null ? DEFAULT_SCORE : item.score();
            if (score <= 0 || score > MAX_SCORE) {
                throw ApiException.validation("单题分值必须在 0 到 " + (int) MAX_SCORE + " 之间", "score");
            }
            total += score;
            rows.add(new PaperRepository.SnapshotQuestionRow(Ids.of("sq"), snapshotId, content.questionId(),
                    content.questionVersionId(), order, score, buildSnapshotJson(content)));
        }
        if (total <= 0) {
            throw ApiException.conflict("试卷总分必须大于 0");
        }
        int versionNo = paperId == null ? 1 : repository.nextSnapshotVersionNo(paperId);
        PaperRepository.SnapshotRow snapshot = new PaperRepository.SnapshotRow(snapshotId, paperId, versionNo,
                title, description, rows.size(), round(total), now, now);
        repository.insertSnapshot(snapshot);
        repository.insertSnapshotQuestions(rows);
        return snapshot;
    }

    private void replaceQuestions(String paperId, List<PaperDtos.QuestionRef> refs) {
        repository.deletePaperQuestions(paperId);
        if (refs == null || refs.isEmpty()) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        List<PaperRepository.PaperQuestionRow> rows = new ArrayList<>();
        int order = 0;
        for (PaperDtos.QuestionRef ref : refs) {
            order++;
            QuestionRepository.QuestionRow question = questionRepository.findQuestion(ref.questionId())
                    .orElseThrow(() -> ApiException.validation("题目不存在：" + ref.questionId(), "questions.questionId"));
            String requestedVersion = ref.questionVersionId();
            String versionId;
            if (requestedVersion == null || requestedVersion.isBlank()) {
                if (question.currentVersionId() == null) {
                    throw ApiException.conflict("题目「" + question.title() + "」没有可用版本");
                }
                versionId = question.currentVersionId();
            } else {
                versionId = requestedVersion;
            }
            final String resolvedVersionId = versionId;
            QuestionRepository.VersionRow version = questionRepository.findVersion(resolvedVersionId)
                    .orElseThrow(() -> ApiException.validation("题目版本不存在：" + resolvedVersionId,
                            "questions.questionVersionId"));
            if (!version.questionId().equals(ref.questionId())) {
                throw ApiException.validation("题目版本不属于该题目：" + resolvedVersionId, "questions.questionVersionId");
            }
            if (!seen.add(resolvedVersionId)) {
                throw ApiException.conflict("同一题目版本不能重复加入试卷：" + question.title());
            }
            double score = ref.score() == null ? DEFAULT_SCORE : ref.score();
            if (score <= 0 || score > MAX_SCORE) {
                throw ApiException.validation("单题分值必须在 0 到 " + (int) MAX_SCORE + " 之间", "questions.score");
            }
            rows.add(new PaperRepository.PaperQuestionRow(Ids.of("pq"), paperId, ref.questionId(), resolvedVersionId,
                    order, score));
        }
        repository.insertPaperQuestions(rows);
    }

    /** 快照内的题目内容：判分与结果展示都以此为准。 */
    private String buildSnapshotJson(QuestionService.VersionContent content) {
        ObjectNode node = json.object();
        node.put("questionId", content.questionId());
        node.put("questionVersionId", content.questionVersionId());
        node.put("versionNo", content.versionNo());
        node.put("questionType", content.questionType());
        node.put("title", content.title());
        node.put("stem", content.stem());
        node.set("options", json.mapper().valueToTree(content.options()));
        node.set("answer", content.answer());
        if (content.explanation() != null) {
            node.put("explanation", content.explanation());
        }
        if (content.rubric() != null) {
            node.set("rubric", content.rubric());
        }
        node.set("knowledgeTags", json.mapper().valueToTree(content.knowledgeTags()));
        return json.write(node);
    }

    public PaperDtos.SnapshotQuestionView parseSnapshotQuestion(PaperRepository.SnapshotQuestionRow row) {
        JsonNode node = json.readTree(row.snapshotJson(), "snapshot_json");
        List<QuestionDtos.OptionView> options = json.convertList(json.write(node.path("options")),
                QuestionDtos.OptionView.class);
        return new PaperDtos.SnapshotQuestionView(row.id(), row.orderNo(), row.score(),
                node.path("questionId").asText(), node.path("questionVersionId").asText(),
                node.path("questionType").asText(), node.path("stem").asText(), options,
                node.path("answer"), node.path("explanation").isMissingNode() ? null : node.path("explanation").asText(),
                node.path("rubric").isMissingNode() ? null : node.path("rubric"),
                json.convertList(json.write(node.path("knowledgeTags")), String.class));
    }

    private PaperDtos.PaperSnapshotView toSnapshotView(PaperRepository.SnapshotRow snapshot) {
        List<PaperDtos.SnapshotQuestionView> questions = snapshotQuestions(snapshot.id());
        return new PaperDtos.PaperSnapshotView(snapshot.id(), snapshot.paperId(), snapshot.versionNo(),
                snapshot.title(), snapshot.description(), snapshot.questionCount(), snapshot.totalScore(),
                snapshot.publishedAt(), questions);
    }

    private PaperDtos.PaperSummary toSummary(PaperRepository.PaperSummaryRow row) {
        return new PaperDtos.PaperSummary(row.id(), row.title(), row.status(), row.questionCount(),
                round(row.totalScore()), row.publishedAt(), row.createdAt(), row.updatedAt(), row.latestSnapshotId());
    }

    private PaperRepository.PaperRow requirePaper(String id) {
        return repository.findPaper(id).orElseThrow(() -> ApiException.notFound("试卷 " + id));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String nowUtc() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    /** 快照条目：题目版本 + 分值。 */
    public record SnapshotItem(String questionVersionId, Double score) {
    }

    /** 校验发布的前置条件，供服务内部复用。 */
    static void requireNotArchived(String status, String what) {
        if ("archived".equals(status)) {
            throw new ApiException(ErrorCode.STATE_CONFLICT, what + "已归档，不能执行该操作");
        }
    }
}
