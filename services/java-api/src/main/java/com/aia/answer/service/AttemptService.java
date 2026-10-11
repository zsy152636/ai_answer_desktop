package com.aia.answer.service;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.ErrorCode;
import com.aia.answer.common.Ids;
import com.aia.answer.common.Json;
import com.aia.answer.common.PageResult;
import com.aia.answer.domain.AttemptDtos;
import com.aia.answer.domain.PaperDtos;
import com.aia.answer.repository.AttemptRepository;
import com.aia.answer.repository.MetaRepository;
import com.aia.answer.repository.PaperRepository;
import com.aia.answer.repository.WrongQuestionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 答题服务：整卷作答、单题保存、提交判分、结果查询（契约 §4）。
 *
 * <p>两条硬规则：
 * <ol>
 *   <li><b>先持久化作答，再判分</b>：保存答案与判分是两个独立动作，提交时才产生分数；</li>
 *   <li><b>判分只读快照</b>：标准答案来自 {@code paper_snapshot_question.snapshot_json}，
 *       因此题目后续改版不会改变任何历史结果。</li>
 * </ol>
 */
@Service
public class AttemptService {

    private static final Logger log = LoggerFactory.getLogger(AttemptService.class);
    private static final int PRACTICE_LIMIT = 20;

    private final AttemptRepository attemptRepository;
    private final PaperRepository paperRepository;
    private final PaperService paperService;
    private final WrongQuestionRepository wrongQuestionRepository;
    private final GradingService gradingService;
    private final WrongScheduler wrongScheduler;
    private final MetaRepository metaRepository;
    private final TaskService taskService;
    private final AttemptTotalsCalculator totalsCalculator;
    private final Json json;

    public AttemptService(AttemptRepository attemptRepository, PaperRepository paperRepository,
                          PaperService paperService, WrongQuestionRepository wrongQuestionRepository,
                          GradingService gradingService, WrongScheduler wrongScheduler,
                          MetaRepository metaRepository, TaskService taskService,
                          AttemptTotalsCalculator totalsCalculator, Json json) {
        this.attemptRepository = attemptRepository;
        this.paperRepository = paperRepository;
        this.paperService = paperService;
        this.wrongQuestionRepository = wrongQuestionRepository;
        this.gradingService = gradingService;
        this.wrongScheduler = wrongScheduler;
        this.metaRepository = metaRepository;
        this.taskService = taskService;
        this.totalsCalculator = totalsCalculator;
        this.json = json;
    }

    // ------------------------------------------------------------- 查询

    public PageResult<AttemptDtos.AttemptSummary> list(String status, String paperId, Integer pageParam,
                                                       Integer sizeParam) {
        int page = pageParam == null ? 1 : pageParam;
        int size = sizeParam == null ? 20 : sizeParam;
        PageResult.validate(page, size);
        long total = attemptRepository.count(status, paperId);
        List<AttemptDtos.AttemptSummary> items = attemptRepository.listPage(status, paperId, (page - 1) * size, size)
                .stream().map(this::toSummary).toList();
        return PageResult.of(items, page, size, total);
    }

    public AttemptDtos.AttemptSheet sheet(String attemptId) {
        AttemptRepository.AttemptRow attempt = requireAttempt(attemptId);
        boolean reveal = !"in_progress".equals(attempt.status());
        List<PaperDtos.SnapshotQuestionView> questions = paperService.snapshotQuestions(attempt.paperSnapshotId());
        List<AttemptDtos.SheetQuestion> sheetQuestions = questions.stream()
                .map(q -> new AttemptDtos.SheetQuestion(q.id(), q.orderNo(), q.score(), q.questionType(), q.stem(),
                        q.options(), q.knowledgeTags(), reveal ? q.answer() : null, reveal ? q.explanation() : null))
                .toList();
        List<AttemptDtos.AnswerView> answers = attemptRepository.listAnswers(attemptId).stream()
                .map(this::toAnswerView).toList();
        return new AttemptDtos.AttemptSheet(attempt.id(), attempt.paperId(), attempt.paperSnapshotId(),
                paperTitle(attempt), attempt.mode(), attempt.status(), attempt.totalScore(), attempt.maxScore(),
                attempt.startedAt(), attempt.submittedAt(), attempt.gradedAt(), sheetQuestions, answers);
    }

    public AttemptDtos.AttemptResult result(String attemptId) {
        AttemptRepository.AttemptRow attempt = requireAttempt(attemptId);
        if ("in_progress".equals(attempt.status())) {
            throw ApiException.conflict("本次作答尚未提交，暂不能查看结果");
        }
        List<PaperDtos.SnapshotQuestionView> questions = paperService.snapshotQuestions(attempt.paperSnapshotId());
        Map<String, AttemptRepository.AnswerRow> answers = new HashMap<>();
        for (AttemptRepository.AnswerRow row : attemptRepository.listAnswers(attemptId)) {
            answers.put(row.snapshotQuestionId(), row);
        }
        List<AttemptDtos.ResultItem> items = new ArrayList<>(questions.size());
        int correct = 0;
        int wrong = 0;
        int unanswered = 0;
        int pending = 0;
        for (PaperDtos.SnapshotQuestionView question : questions) {
            AttemptRepository.AnswerRow row = answers.get(question.id());
            JsonNode userAnswer = row == null ? NullNode.getInstance() : json.readTreeOrNull(row.answerJson());
            JsonNode userValue = gradingService.extractUserValue(userAnswer);
            boolean answered = !gradingService.isEmpty(userValue);
            boolean correctFlag = row != null && row.score() != null && row.score() >= question.score() - 1e-6;
            if (!answered) {
                unanswered++;
            } else if (correctFlag) {
                correct++;
            } else {
                wrong++;
            }
            if (row != null && "pending_review".equals(row.gradingStatus())) {
                pending++;
            }
            items.add(new AttemptDtos.ResultItem(question.id(), question.orderNo(), question.questionType(),
                    question.stem(), question.options(), userAnswer, question.answer(), question.explanation(),
                    row == null ? null : row.score(), question.score(), correctFlag,
                    row == null ? "ungraded" : row.gradingMethod(),
                    row == null ? "graded" : row.gradingStatus(),
                    row == null ? null : json.readTreeOrNull(row.gradingDetailJson()), question.knowledgeTags()));
        }
        return new AttemptDtos.AttemptResult(toSummary(attempt), items, correct, wrong, unanswered, pending);
    }

    // ------------------------------------------------------------- 开始答题

    @Transactional
    public AttemptDtos.AttemptSheet start(AttemptDtos.StartRequest request) {
        String mode = request != null && request.mode() != null ? request.mode() : "paper";
        return switch (mode) {
            case "paper" -> startPaper(request == null ? null : request.paperId());
            case "wrong_practice" -> startPractice(request);
            default -> throw ApiException.validation("mode 只支持 paper 或 wrong_practice", "mode");
        };
    }

    private AttemptDtos.AttemptSheet startPaper(String paperId) {
        if (paperId == null || paperId.isBlank()) {
            throw ApiException.validation("paperId 不能为空", "paperId");
        }
        PaperRepository.PaperRow paper = paperRepository.findPaper(paperId)
                .orElseThrow(() -> ApiException.notFound("试卷 " + paperId));
        if (!"published".equals(paper.status()) || paper.currentSnapshotId() == null) {
            throw ApiException.conflict("试卷尚未发布，无法开始答题");
        }
        PaperRepository.SnapshotRow snapshot = paperRepository.findSnapshot(paper.currentSnapshotId())
                .orElseThrow(() -> ApiException.notFound("试卷快照"));
        String now = nowUtc();
        String attemptId = Ids.of("at");
        attemptRepository.insertAttempt(new AttemptRepository.AttemptRow(attemptId, paperId, snapshot.id(), "paper",
                "in_progress", snapshot.questionCount(), null, snapshot.totalScore(), now, null, null, null, null));
        metaRepository.audit("attempt.started", "attempt", attemptId,
                json.write(Map.of("paperId", paperId, "snapshotId", snapshot.id())), now);
        return sheet(attemptId);
    }

    private AttemptDtos.AttemptSheet startPractice(AttemptDtos.StartRequest request) {
        String now = nowUtc();
        List<WrongQuestionRepository.WrongQuestionRow> rows;
        if (request.wrongQuestionIds() != null && !request.wrongQuestionIds().isEmpty()) {
            rows = wrongQuestionRepository.listByIds(request.wrongQuestionIds());
            if (rows.size() != request.wrongQuestionIds().size()) {
                throw ApiException.notFound("部分错题不存在，请刷新错题本");
            }
        } else {
            int limit = request.limit() == null ? PRACTICE_LIMIT : Math.max(1, Math.min(100, request.limit()));
            rows = wrongQuestionRepository.listDue(now, limit);
            if (rows.isEmpty()) {
                // 没有到期错题时退回最近错题，避免「点了按钮什么都没发生」
                rows = wrongQuestionRepository.listRecent(limit);
            }
        }
        if (rows.isEmpty()) {
            throw ApiException.conflict("错题本为空，无需练习");
        }
        List<PaperService.SnapshotItem> items = rows.stream()
                .map(row -> new PaperService.SnapshotItem(row.questionVersionId(),
                        row.maxScore() == null ? PaperService.DEFAULT_SCORE : row.maxScore()))
                .toList();
        PaperRepository.SnapshotRow snapshot = paperService.buildPracticeSnapshot("错题练习 · " + now, items);
        String attemptId = Ids.of("at");
        attemptRepository.insertAttempt(new AttemptRepository.AttemptRow(attemptId, null, snapshot.id(),
                "wrong_practice", "in_progress", snapshot.questionCount(), null, snapshot.totalScore(), now,
                null, null, null, null));
        metaRepository.audit("attempt.practice_started", "attempt", attemptId,
                json.write(Map.of("snapshotId", snapshot.id(), "questionCount", snapshot.questionCount())), now);
        return sheet(attemptId);
    }

    // ------------------------------------------------------------- 保存与提交

    @Transactional
    public AttemptDtos.AnswerView saveAnswer(String attemptId, String snapshotQuestionId, JsonNode answer) {
        AttemptRepository.AttemptRow attempt = requireAttempt(attemptId);
        if (!"in_progress".equals(attempt.status())) {
            throw ApiException.conflict("本次作答已提交，不能继续修改答案");
        }
        PaperRepository.SnapshotQuestionRow question = paperRepository.findSnapshotQuestion(snapshotQuestionId)
                .orElseThrow(() -> ApiException.notFound("题目 " + snapshotQuestionId));
        if (!question.snapshotId().equals(attempt.paperSnapshotId())) {
            throw ApiException.validation("该题目不属于本次作答", "snapshotQuestionId");
        }
        String now = nowUtc();
        attemptRepository.upsertAnswer(new AttemptRepository.AnswerRow(Ids.of("aa"), attemptId, snapshotQuestionId,
                question.questionId(), question.questionVersionId(),
                json.write(answer == null ? NullNode.getInstance() : answer), null, question.score(),
                "ungraded", "pending_review", null, now));
        return attemptRepository.findAnswer(attemptId, snapshotQuestionId).map(this::toAnswerView)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "答案保存后未能读回"));
    }

    /** 提交：先补齐未作答行 → 确定性判分 → 汇总 → 错题登记 → 主观题排队 AI 复核。 */
    @Transactional
    public AttemptDtos.AttemptResult submit(String attemptId) {
        AttemptRepository.AttemptRow attempt = requireAttempt(attemptId);
        if (!"in_progress".equals(attempt.status())) {
            // 提交幂等：重复提交直接返回既有结果，不重复生成作答或错题
            return result(attemptId);
        }
        String now = nowUtc();
        attemptRepository.markSubmitted(attemptId, now, durationSeconds(attempt.startedAt(), now));

        List<PaperDtos.SnapshotQuestionView> questions = paperService.snapshotQuestions(attempt.paperSnapshotId());
        Map<String, AttemptRepository.AnswerRow> existing = new HashMap<>();
        for (AttemptRepository.AnswerRow row : attemptRepository.listAnswers(attemptId)) {
            existing.put(row.snapshotQuestionId(), row);
        }

        double totalScore = 0;
        double maxScore = 0;
        for (PaperDtos.SnapshotQuestionView question : questions) {
            maxScore += question.score();
            AttemptRepository.AnswerRow row = existing.get(question.id());
            String answerJson = row == null ? "null" : row.answerJson();
            JsonNode userAnswer = json.readTreeOrNull(answerJson);
            GradingService.Outcome outcome = gradingService.grade(question.questionType(), question.answer(),
                    question.score(), userAnswer);

            String answerId;
            if (row == null) {
                answerId = Ids.of("aa");
                attemptRepository.upsertAnswer(new AttemptRepository.AnswerRow(answerId, attemptId, question.id(),
                        question.questionId(), question.questionVersionId(), "null", outcome.score(), question.score(),
                        outcome.gradingMethod(), outcome.gradingStatus(), json.write(outcome.detail()), now));
            } else {
                answerId = row.id();
                attemptRepository.updateAnswerGrading(answerId, outcome.score(), outcome.gradingMethod(),
                        outcome.gradingStatus(), json.write(outcome.detail()));
            }
            if (outcome.score() != null) {
                totalScore += outcome.score();
            }

            if ("pending_review".equals(outcome.gradingStatus())) {
                enqueueAiGrading(attemptId, answerId, question);
            } else if (outcome.score() == null || outcome.score() < question.score() - 1e-6) {
                wrongScheduler.registerWrong(question.questionId(), question.questionVersionId(), outcome.score(),
                        question.score(), answerId, now);
            }
        }
        attemptRepository.markGraded(attemptId, round(totalScore), round(maxScore), nowUtc());
        metaRepository.audit("attempt.graded", "attempt", attemptId,
                json.write(Map.of("totalScore", round(totalScore), "maxScore", round(maxScore))), now);
        return result(attemptId);
    }

    /**
     * 人工确认或覆盖主观题分数。AI 给的只是建议分，必须有人工动作才生效（开发文档 §5.2）。
     */
    @Transactional
    public AttemptDtos.AttemptResult gradeManually(String attemptId, String snapshotQuestionId, Double score,
                                                   String note) {
        AttemptRepository.AttemptRow attempt = requireAttempt(attemptId);
        if ("in_progress".equals(attempt.status())) {
            throw ApiException.conflict("请先提交本次作答，再进行人工评分");
        }
        AttemptRepository.AnswerRow row = attemptRepository.findAnswer(attemptId, snapshotQuestionId)
                .orElseThrow(() -> ApiException.notFound("题目作答记录"));
        if (score == null || score < 0 || score > row.maxScore() + 1e-6) {
            throw ApiException.validation("分数必须在 0 到 " + row.maxScore() + " 之间", "score");
        }
        ObjectNode detail = json.object();
        detail.put("ruleVersion", com.aia.answer.common.AppInfo.GRADING_RULE_VERSION);
        detail.put("manual", true);
        JsonNode previous = json.readTreeOrNull(row.gradingDetailJson());
        if (previous != null) {
            detail.set("previous", previous);
        }
        if (note != null && !note.isBlank()) {
            detail.put("note", note.trim());
        }
        attemptRepository.updateAnswerGrading(row.id(), score, "manual", "graded", json.write(detail));
        totalsCalculator.recompute(attemptId);
        metaRepository.audit("attempt.manual_graded", "attempt", attemptId,
                json.write(Map.of("snapshotQuestionId", snapshotQuestionId, "score", score)), nowUtc());
        return result(attemptId);
    }

    /** 为本题所有待复核的主观题排队 AI 评分任务，返回任务 id 列表。 */
    @Transactional
    public List<String> requestAiGrading(String attemptId) {
        AttemptRepository.AttemptRow attempt = requireAttempt(attemptId);
        if ("in_progress".equals(attempt.status())) {
            throw ApiException.conflict("请先提交本次作答，再请求 AI 评分");
        }
        Map<String, String> versionBySnapshotQuestion = new HashMap<>();
        for (PaperDtos.SnapshotQuestionView question : paperService.snapshotQuestions(attempt.paperSnapshotId())) {
            versionBySnapshotQuestion.put(question.id(), question.questionVersionId());
        }
        List<String> taskIds = new ArrayList<>();
        for (AttemptRepository.AnswerRow row : attemptRepository.listAnswers(attemptId)) {
            if ("pending_review".equals(row.gradingStatus())) {
                taskIds.add(taskService.enqueueAiGrade(attemptId, row.id(), row.snapshotQuestionId(),
                        versionBySnapshotQuestion.get(row.snapshotQuestionId())));
            }
        }
        if (taskIds.isEmpty()) {
            throw ApiException.conflict("本次作答没有待复核的主观题");
        }
        return taskIds;
    }

    @Transactional
    public AttemptDtos.AttemptSheet abandon(String attemptId) {        AttemptRepository.AttemptRow attempt = requireAttempt(attemptId);
        if ("in_progress".equals(attempt.status())) {
            attemptRepository.markAbandoned(attemptId, nowUtc());
        }
        return sheet(attemptId);
    }

    /** AI 评分排队失败不影响主流程：作答与确定性判分已经落库。 */
    private void enqueueAiGrading(String attemptId, String answerId, PaperDtos.SnapshotQuestionView question) {
        try {
            taskService.enqueueAiGrade(attemptId, answerId, question.id(), question.questionVersionId());
        } catch (Exception e) {
            log.warn("AI 评分任务入队失败（不影响已保存的作答）：{}", e.getMessage());
        }
    }

    // ------------------------------------------------------------- 内部

    private AttemptRepository.AttemptRow requireAttempt(String attemptId) {
        return attemptRepository.findAttempt(attemptId).orElseThrow(() -> ApiException.notFound("作答记录 " + attemptId));
    }

    private String paperTitle(AttemptRepository.AttemptRow attempt) {
        if (attempt.paperSnapshotId() != null) {
            PaperRepository.SnapshotRow snapshot = paperRepository.findSnapshot(attempt.paperSnapshotId()).orElse(null);
            if (snapshot != null) {
                return snapshot.title();
            }
        }
        if (attempt.paperId() != null) {
            return paperRepository.findPaper(attempt.paperId()).map(PaperRepository.PaperRow::title).orElse("试卷");
        }
        return "错题练习";
    }

    private AttemptDtos.AttemptSummary toSummary(AttemptRepository.AttemptRow attempt) {
        return new AttemptDtos.AttemptSummary(attempt.id(), attempt.paperId(), attempt.paperSnapshotId(),
                paperTitle(attempt), attempt.mode(), attempt.status(), attempt.totalScore(), attempt.maxScore(),
                attempt.questionCount(), attempt.startedAt(), attempt.submittedAt(), attempt.gradedAt());
    }

    private AttemptDtos.AttemptSummary toSummary(AttemptRepository.AttemptSummaryRow row) {
        return new AttemptDtos.AttemptSummary(row.id(), row.paperId(), row.paperSnapshotId(), row.paperTitle(),
                row.mode(), row.status(), row.totalScore(), row.maxScore(), row.questionCount(), row.startedAt(),
                row.submittedAt(), row.gradedAt());
    }

    private AttemptDtos.AnswerView toAnswerView(AttemptRepository.AnswerRow row) {
        return new AttemptDtos.AnswerView(row.snapshotQuestionId(), row.questionId(), row.questionVersionId(),
                json.readTreeOrNull(row.answerJson()), row.score(), row.maxScore(), row.gradingMethod(),
                row.gradingStatus(), json.readTreeOrNull(row.gradingDetailJson()), row.savedAt());
    }

    private int durationSeconds(String startedAt, String finishedAt) {
        try {
            long seconds = Duration.between(Instant.parse(startedAt), Instant.parse(finishedAt)).getSeconds();
            return (int) Math.max(0, Math.min(Integer.MAX_VALUE, seconds));
        } catch (Exception e) {
            return 0;
        }
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String nowUtc() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
