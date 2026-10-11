package com.aia.answer.service;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.ErrorCode;
import com.aia.answer.common.Ids;
import com.aia.answer.common.Json;
import com.aia.answer.common.PageResult;
import com.aia.answer.domain.SystemDtos;
import com.aia.answer.repository.AttemptRepository;
import com.aia.answer.repository.MetaRepository;
import com.aia.answer.repository.PaperRepository;
import com.aia.answer.repository.TaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 异步任务服务（开发文档 §8）。
 *
 * <p>任务表即队列：SQLite 事务里写 payload 摘要 + 幂等键 + queued 状态，执行者用条件 UPDATE
 * 原子认领，运行期持有租约，进程崩溃后过期租约可被回收。模型调用等外部请求绝不持有数据库长事务。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final String TASK_AI_GRADE = "ai_grade";
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final TaskRepository taskRepository;
    private final AttemptRepository attemptRepository;
    private final PaperRepository paperRepository;
    private final LlmProfileService llmProfileService;
    private final AiClient aiClient;
    private final AttemptTotalsCalculator totalsCalculator;
    private final MetaRepository metaRepository;
    private final Json json;
    private final SecureRandom random = new SecureRandom();

    public TaskService(TaskRepository taskRepository, AttemptRepository attemptRepository,
                       PaperRepository paperRepository, LlmProfileService llmProfileService, AiClient aiClient,
                       AttemptTotalsCalculator totalsCalculator, MetaRepository metaRepository, Json json) {
        this.taskRepository = taskRepository;
        this.attemptRepository = attemptRepository;
        this.paperRepository = paperRepository;
        this.llmProfileService = llmProfileService;
        this.aiClient = aiClient;
        this.totalsCalculator = totalsCalculator;
        this.metaRepository = metaRepository;
        this.json = json;
    }

    // ------------------------------------------------------------- 入队

    /** 主观题 AI 评分任务。幂等键 = ai_grade:{answerId}，重复提交不会产生第二个任务。 */
    @Transactional
    public String enqueueAiGrade(String attemptId, String answerId, String snapshotQuestionId,
                                 String questionVersionId) {
        String idempotencyKey = TASK_AI_GRADE + ":" + answerId;
        var existing = taskRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get().id();
        }
        ObjectNode payload = json.object();
        payload.put("attemptId", attemptId);
        payload.put("answerId", answerId);
        payload.put("snapshotQuestionId", snapshotQuestionId);
        payload.put("questionVersionId", questionVersionId);
        String now = nowUtc();
        String id = Ids.of("task");
        taskRepository.insert(new TaskRepository.TaskRow(id, TASK_AI_GRADE, "queued", json.write(payload), null,
                idempotencyKey, 0, 3, null, null, null, now, null, null, now, now));
        return id;
    }

    // ------------------------------------------------------------- 查询与人工操作

    public PageResult<SystemDtos.TaskView> list(String status, String taskType, Integer pageParam, Integer sizeParam) {
        int page = pageParam == null ? 1 : pageParam;
        int size = sizeParam == null ? 20 : sizeParam;
        PageResult.validate(page, size);
        long total = taskRepository.count(status, taskType);
        List<SystemDtos.TaskView> items = taskRepository.listPage(status, taskType, (page - 1) * size, size)
                .stream().map(this::toView).toList();
        return PageResult.of(items, page, size, total);
    }

    public SystemDtos.TaskView get(String id) {
        return toView(requireTask(id));
    }

    @Transactional
    public SystemDtos.TaskView retry(String id) {
        TaskRepository.TaskRow task = requireTask(id);
        if (!"failed".equals(task.status()) && !"cancelled".equals(task.status())) {
            throw ApiException.conflict("只有失败或已取消的任务可以重试，当前状态：" + task.status());
        }
        taskRepository.retry(id, nowUtc());
        return get(id);
    }

    @Transactional
    public SystemDtos.TaskView cancel(String id) {
        TaskRepository.TaskRow task = requireTask(id);
        if ("succeeded".equals(task.status()) || "failed".equals(task.status())) {
            throw ApiException.conflict("任务已结束，不能取消，当前状态：" + task.status());
        }
        taskRepository.cancel(id, nowUtc());
        return get(id);
    }

    /** 启动时回收过期租约：上次崩溃时正在运行的任务重新排队。 */
    @Transactional
    public int recoverExpiredLeases() {
        String now = nowUtc();
        int recovered = taskRepository.recoverExpiredLeases(now, now);
        if (recovered > 0) {
            log.warn("回收了 {} 个过期租约任务", recovered);
        }
        return recovered;
    }

    // ------------------------------------------------------------- 执行

    @Scheduled(fixedDelay = 3000, initialDelay = 5000)
    public void poll() {
        try {
            String now = nowUtc();
            taskRepository.claimNext("java-api-" + ProcessHandle.current().pid(), now, leaseUntil())
                    .ifPresent(this::execute);
        } catch (Exception e) {
            log.warn("任务轮询异常：{}", e.getMessage());
        }
    }

    private void execute(TaskRepository.TaskRow task) {
        try {
            switch (task.taskType()) {
                case TASK_AI_GRADE -> executeAiGrade(task);
                default -> taskRepository.fail(task.id(), "UNSUPPORTED_TASK_TYPE",
                        "未知任务类型：" + task.taskType(), nowUtc());
            }
        } catch (ApiException e) {
            handleFailure(task, e.code().name(), e.getMessage(), e.code().retryable());
        } catch (Exception e) {
            log.error("任务执行失败 taskId={} type={}", task.id(), task.taskType(), e);
            handleFailure(task, "INTERNAL_ERROR", "任务执行异常：" + e.getClass().getSimpleName(), true);
        }
    }

    private void executeAiGrade(TaskRepository.TaskRow task) {
        JsonNode payload = json.readTree(task.payloadJson(), "payload");
        String attemptId = payload.path("attemptId").asText();
        String answerId = payload.path("answerId").asText();
        String snapshotQuestionId = payload.path("snapshotQuestionId").asText();

        AttemptRepository.AnswerRow answer = attemptRepository.findAnswerById(answerId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "作答记录已不存在"));
        PaperRepository.SnapshotQuestionRow snapshotQuestion = paperRepository.findSnapshotQuestion(snapshotQuestionId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "题目快照已不存在"));
        PaperDtosHolder holder = new PaperDtosHolder(snapshotQuestion);

        if (!llmProfileService.hasUsableProfile()) {
            // 永久错误：没有配置默认档案，重试没有意义
            throw new ApiException(ErrorCode.CREDENTIAL_MISSING, "尚未配置默认 LLM 档案，无法进行 AI 评分");
        }

        JsonNode profile = llmProfileService.buildCallProfile(null);
        ObjectNode aiPayload = json.object();
        ObjectNode question = aiPayload.putObject("question");
        question.put("stem", holder.stem());
        question.put("questionType", holder.questionType());
        question.put("referenceAnswer", holder.referenceAnswer());
        if (holder.rubric() != null) {
            question.set("rubric", holder.rubric());
        }
        // Python 侧 userAnswer 是字符串（主观题作答就是文本）；
        // 这里把可能带包装的作答 JSON 还原成纯文本，避免 provider 收到 {\"value\":\"…\"} 这种噪声。
        aiPayload.put("userAnswer", userAnswerText(json.readTreeOrNull(answer.answerJson())));
        aiPayload.set("profile", profile);
        aiPayload.put("maxScore", answer.maxScore());

        JsonNode data = aiClient.gradeSubjective(aiPayload);
        double suggested = data.path("suggestedScore").asDouble(0);
        double capped = Math.max(0, Math.min(answer.maxScore(), suggested));

        ObjectNode detail = json.object();
        detail.put("ruleVersion", com.aia.answer.common.AppInfo.GRADING_RULE_VERSION);
        detail.put("model", data.path("model").asText(profile.path("model").asText("")));
        detail.put("confidence", data.path("confidence").asDouble(0));
        detail.put("needsReview", true);
        detail.put("suggestedScore", capped);
        if (data.has("criteria")) {
            detail.set("criteria", data.get("criteria"));
        }
        if (data.has("latencyMs")) {
            detail.put("latencyMs", data.path("latencyMs").asLong());
        }
        detail.put("note", "AI 建议分，需人工复核后生效");

        attemptRepository.updateAnswerGrading(answerId, capped, "ai_rubric", "pending_review", json.write(detail));
        totalsCalculator.recompute(attemptId);
        taskRepository.succeed(task.id(), json.write(detail), nowUtc());
        metaRepository.audit("attempt.ai_graded", "attempt", attemptId,
                json.write(java.util.Map.of("answerId", answerId, "suggestedScore", capped)), nowUtc());
    }

    /** 把作答 JSON 还原为纯文本：带 value 包装时取 value，其余情况取文本或序列化结果。 */
    private String userAnswerText(JsonNode answerNode) {
        if (answerNode == null || answerNode.isNull()) {
            return "";
        }
        JsonNode value = answerNode;
        if (answerNode.isObject() && answerNode.has("value")) {
            value = answerNode.get("value");
        }
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isArray()) {
            List<String> parts = new java.util.ArrayList<>();
            value.forEach(item -> parts.add(item.isTextual() ? item.asText() : item.toString()));
            return String.join("；", parts);
        }
        return value.toString();
    }

    /** 解析快照 JSON 得到题干与 rubric，供提示词构造。 */
    private final class PaperDtosHolder {
        private final JsonNode node;

        private PaperDtosHolder(PaperRepository.SnapshotQuestionRow row) {
            this.node = json.readTree(row.snapshotJson(), "snapshot_json");
        }

        private String stem() {
            return node.path("stem").asText("");
        }

        private String questionType() {
            return node.path("questionType").asText("");
        }

        private String referenceAnswer() {
            JsonNode answer = node.path("answer");
            if (answer.hasNonNull("referenceAnswer")) {
                return answer.path("referenceAnswer").asText("");
            }
            return answer.toString();
        }

        private JsonNode rubric() {
            return node.path("rubric").isMissingNode() ? null : node.path("rubric");
        }
    }

    private void handleFailure(TaskRepository.TaskRow task, String code, String message, boolean retryable) {
        String now = nowUtc();
        String safeMessage = message == null ? "" : message;
        if (retryable && task.attemptCount() < task.maxAttempts()) {
            String nextRunAt = DateTimeFormatter.ISO_INSTANT.format(
                    Instant.now().plus(backoff(task.attemptCount())).truncatedTo(ChronoUnit.SECONDS));
            taskRepository.scheduleRetry(task.id(), code, safeMessage, nextRunAt, now);
            log.info("任务 {} 第 {} 次失败，{} 后重试：{}", task.id(), task.attemptCount(), backoff(task.attemptCount()), safeMessage);
        } else {
            taskRepository.fail(task.id(), code, safeMessage, now);
            log.warn("任务 {} 最终失败（{}）：{}", task.id(), code, safeMessage);
        }
    }

    /** 指数退避 + 抖动，避免同类任务在同一时刻反复重试。 */
    private Duration backoff(int attemptCount) {
        long seconds = BASE_BACKOFF.getSeconds() * (1L << Math.min(6, Math.max(0, attemptCount - 1)));
        long capped = Math.min(seconds, MAX_BACKOFF.getSeconds());
        long jitter = (long) (random.nextDouble() * Math.max(1, capped / 4));
        return Duration.ofSeconds(capped + jitter);
    }

    private SystemDtos.TaskView toView(TaskRepository.TaskRow row) {
        return new SystemDtos.TaskView(row.id(), row.taskType(), row.status(),
                json.readTreeOrNull(row.payloadJson()), json.readTreeOrNull(row.resultJson()), row.attemptCount(),
                row.maxAttempts(), row.leaseOwner(), row.leaseUntil(), row.heartbeatAt(), row.nextRunAt(),
                row.lastErrorCode(), row.lastErrorMessage(), row.createdAt(), row.updatedAt());
    }

    private TaskRepository.TaskRow requireTask(String id) {
        return taskRepository.findById(id).orElseThrow(() -> ApiException.notFound("任务 " + id));
    }

    private String leaseUntil() {
        return DateTimeFormatter.ISO_INSTANT.format(
                Instant.now().plus(LEASE).truncatedTo(ChronoUnit.SECONDS));
    }

    private static String nowUtc() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
