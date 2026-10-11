package com.aia.answer.service;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.ErrorCode;
import com.aia.answer.common.Ids;
import com.aia.answer.common.Json;
import com.aia.answer.common.PageResult;
import com.aia.answer.domain.QuestionDtos;
import com.aia.answer.repository.MetaRepository;
import com.aia.answer.repository.QuestionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 题库服务：题目身份与版本、发布与归档（契约 §2，开发文档 §5.1）。
 *
 * <p>版本规则：任何影响题意、选项、标准答案、解析、标签或评分规则的变化都会创建新版本；
 * 只有标题与难度这类元数据可以原地更新。内容是否变化由稳定序列化后的 SHA-256 判定，
 * 而不是靠前端传标志位。
 */
@Service
public class QuestionService {

    private final QuestionRepository repository;
    private final GradingService gradingService;
    private final MetaRepository metaRepository;
    private final Json json;

    public QuestionService(QuestionRepository repository, GradingService gradingService,
                           MetaRepository metaRepository, Json json) {
        this.repository = repository;
        this.gradingService = gradingService;
        this.metaRepository = metaRepository;
        this.json = json;
    }

    // ------------------------------------------------------------- 查询

    public PageResult<QuestionDtos.QuestionSummary> list(QuestionDtos.Query query) {
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        PageResult.validate(page, size);
        long total = repository.count(query);
        List<QuestionRepository.QuestionRow> rows = repository.listPage(query, (page - 1) * size, size);
        List<QuestionDtos.QuestionSummary> items = new ArrayList<>(rows.size());
        for (QuestionRepository.QuestionRow row : rows) {
            items.add(toSummary(row));
        }
        return PageResult.of(items, page, size, total);
    }

    public QuestionDtos.QuestionView get(String id, Integer versionNo) {
        QuestionRepository.QuestionRow row = requireQuestion(id);
        QuestionRepository.VersionRow version = versionNo == null
                ? currentVersion(row)
                : repository.findVersionByNo(id, versionNo)
                        .orElseThrow(() -> ApiException.notFound("题目版本 v" + versionNo));
        return toView(row, version);
    }

    public List<QuestionDtos.VersionBrief> versions(String id) {
        requireQuestion(id);
        return repository.listVersions(id).stream()
                .map(v -> new QuestionDtos.VersionBrief(v.id(), v.versionNo(), v.createdAt(), v.changeNote()))
                .toList();
    }

    /** 供试卷快照与判分使用的版本内容。 */
    public VersionContent loadContent(String questionVersionId) {
        QuestionRepository.VersionRow version = repository.findVersion(questionVersionId)
                .orElseThrow(() -> ApiException.notFound("题目版本"));
        QuestionRepository.QuestionRow question = repository.findQuestion(version.questionId())
                .orElseThrow(() -> ApiException.notFound("题目"));
        return new VersionContent(question.id(), version.id(), version.versionNo(), question.questionType(),
                question.title(), question.status(), version.stem(), loadOptions(version.id()),
                json.readTree(version.answerJson(), "answer"), version.explanation(),
                json.readTreeOrNull(version.rubricJson()), json.convertList(version.knowledgeTagsJson(), String.class),
                version.contentHash());
    }

    // ------------------------------------------------------------- 写入

    @Transactional
    public QuestionDtos.QuestionView create(QuestionDtos.SaveRequest request) {
        List<QuestionDtos.OptionView> options = validateAndBuildOptions(request);
        JsonNode canonicalAnswer = gradingService.canonicalAnswer(request.questionType(), request.answer(),
                options.stream().map(QuestionDtos.OptionView::key).toList());
        List<String> tags = cleanTags(request.knowledgeTags());

        String questionId = Ids.of("q");
        String versionId = Ids.of("qv");
        String now = nowUtc();
        repository.insertQuestion(questionId, request.title().trim(), request.questionType(), "draft",
                difficultyOf(request), now);
        repository.insertVersion(new QuestionRepository.VersionRow(
                versionId, questionId, 1, request.stem().trim(), json.write(canonicalAnswer),
                request.explanation(), request.rubric() == null ? null : json.write(request.rubric()),
                json.write(tags), contentHash(request.questionType(), request.stem(), options, canonicalAnswer,
                request.explanation(), request.rubric(), tags),
                blankToNull(request.changeNote()), now));
        repository.insertOptions(toOptionRows(versionId, options, canonicalAnswer, request.questionType()));
        repository.setCurrentVersion(questionId, versionId, now);
        metaRepository.audit("question.created", "question", questionId,
                json.write(java.util.Map.of("versionNo", 1)), now);
        return get(questionId, null);
    }

    @Transactional
    public QuestionDtos.QuestionView update(String id, QuestionDtos.SaveRequest request) {
        QuestionRepository.QuestionRow question = requireQuestion(id);
        QuestionRepository.VersionRow current = currentVersion(question);
        if (request.expectedVersionNo() != null && request.expectedVersionNo() != current.versionNo()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT,
                    "题目已被修改（当前版本 v" + current.versionNo() + "），请刷新后重试");
        }
        if (request.questionType() != null && !request.questionType().equals(question.questionType())) {
            throw ApiException.validation("已创建题目的题型不可更改，请新建题目", "questionType");
        }

        List<QuestionDtos.OptionView> options = validateAndBuildOptions(request);
        JsonNode canonicalAnswer = gradingService.canonicalAnswer(question.questionType(), request.answer(),
                options.stream().map(QuestionDtos.OptionView::key).toList());
        List<String> tags = cleanTags(request.knowledgeTags());
        String newHash = contentHash(question.questionType(), request.stem(), options, canonicalAnswer,
                request.explanation(), request.rubric(), tags);
        String now = nowUtc();

        repository.updateQuestionMeta(id, request.title().trim(), difficultyOf(request), now);
        if (newHash.equals(current.contentHash())) {
            return get(id, null);
        }

        int nextNo = repository.nextVersionNo(id);
        String versionId = Ids.of("qv");
        repository.insertVersion(new QuestionRepository.VersionRow(
                versionId, id, nextNo, request.stem().trim(), json.write(canonicalAnswer), request.explanation(),
                request.rubric() == null ? null : json.write(request.rubric()), json.write(tags), newHash,
                blankToNull(request.changeNote()), now));
        repository.insertOptions(toOptionRows(versionId, options, canonicalAnswer, question.questionType()));
        repository.setCurrentVersion(id, versionId, now);
        // 内容已变化，新版本需要重新发布
        repository.updateStatus(id, "draft", now);
        metaRepository.audit("question.version_created", "question", id,
                json.write(java.util.Map.of("versionNo", nextNo, "previousVersionNo", current.versionNo())), now);
        return get(id, null);
    }

    @Transactional
    public QuestionDtos.QuestionView publish(String id) {
        QuestionRepository.QuestionRow question = requireQuestion(id);
        if ("published".equals(question.status())) {
            return get(id, null);
        }
        if ("archived".equals(question.status())) {
            throw ApiException.conflict("已归档题目不能发布，请先取消归档");
        }
        String now = nowUtc();
        repository.updateStatus(id, "published", now);
        metaRepository.audit("question.published", "question", id, "{}", now);
        return get(id, null);
    }

    @Transactional
    public QuestionDtos.QuestionView archive(String id) {
        QuestionRepository.QuestionRow question = requireQuestion(id);
        if ("archived".equals(question.status())) {
            return get(id, null);
        }
        String now = nowUtc();
        repository.updateStatus(id, "archived", now);
        metaRepository.audit("question.archived", "question", id, "{}", now);
        return get(id, null);
    }

    /** 只有从未发布、也未被任何试卷/作答/错题引用的草稿才能物理删除。 */
    @Transactional
    public void delete(String id) {
        QuestionRepository.QuestionRow question = requireQuestion(id);
        if (!"draft".equals(question.status())) {
            throw ApiException.conflict("只有草稿状态的题目可以删除，请改为归档");
        }
        if (repository.isReferenced(id)) {
            throw ApiException.conflict("题目已被试卷、作答或错题引用，无法删除，请改为归档");
        }
        repository.deleteQuestionFully(id);
        metaRepository.audit("question.deleted", "question", id, "{}", nowUtc());
    }

    // ------------------------------------------------------------- 内部

    private QuestionRepository.QuestionRow requireQuestion(String id) {
        return repository.findQuestion(id).orElseThrow(() -> ApiException.notFound("题目 " + id));
    }

    private QuestionRepository.VersionRow currentVersion(QuestionRepository.QuestionRow question) {
        if (question.currentVersionId() == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "题目缺少当前版本引用：" + question.id());
        }
        return repository.findVersion(question.currentVersionId())
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "题目当前版本不存在"));
    }

    private List<QuestionDtos.OptionView> loadOptions(String versionId) {
        return repository.listOptions(versionId).stream()
                .map(o -> new QuestionDtos.OptionView(o.key(), o.content(), o.correct()))
                .toList();
    }

    private QuestionDtos.QuestionSummary toSummary(QuestionRepository.QuestionRow row) {
        String tags = "[]";
        int versionNo = 0;
        if (row.currentVersionId() != null) {
            QuestionRepository.VersionRow version = repository.findVersion(row.currentVersionId()).orElse(null);
            if (version != null) {
                tags = version.knowledgeTagsJson();
                versionNo = version.versionNo();
            }
        }
        return new QuestionDtos.QuestionSummary(row.id(), row.title(), row.questionType(), row.status(),
                row.difficulty(), versionNo, json.convertList(tags, String.class), row.updatedAt());
    }

    private QuestionDtos.QuestionView toView(QuestionRepository.QuestionRow row, QuestionRepository.VersionRow version) {
        QuestionDtos.QuestionVersionView versionView = new QuestionDtos.QuestionVersionView(
                version.id(), version.versionNo(), version.stem(), loadOptions(version.id()),
                json.readTree(version.answerJson(), "answer"), version.explanation(),
                json.readTreeOrNull(version.rubricJson()), json.convertList(version.knowledgeTagsJson(), String.class),
                version.changeNote(), version.createdAt());
        List<QuestionDtos.VersionBrief> briefs = repository.listVersions(row.id()).stream()
                .map(v -> new QuestionDtos.VersionBrief(v.id(), v.versionNo(), v.createdAt(), v.changeNote()))
                .toList();
        return new QuestionDtos.QuestionView(row.id(), row.title(), row.questionType(), row.status(), row.difficulty(),
                versionView, briefs, row.createdAt(), row.updatedAt());
    }

    /** 选项校验：数量、键唯一、内容非空；正确性由答案推导，忽略请求里的 correct 标志。 */
    private List<QuestionDtos.OptionView> validateAndBuildOptions(QuestionDtos.SaveRequest request) {
        boolean choice = "single_choice".equals(request.questionType())
                || "multiple_choice".equals(request.questionType());
        List<QuestionDtos.OptionView> raw = request.options() == null ? List.of() : request.options();
        if (!choice) {
            return List.of();
        }
        if (raw.size() < 2 || raw.size() > 10) {
            throw ApiException.validation("选择题必须提供 2 到 10 个选项", "options");
        }
        Set<String> keys = new LinkedHashSet<>();
        List<QuestionDtos.OptionView> result = new ArrayList<>(raw.size());
        for (QuestionDtos.OptionView option : raw) {
            String key = option.key() == null ? "" : option.key().trim().toUpperCase(Locale.ROOT);
            if (key.isEmpty()) {
                throw ApiException.validation("选项标识不能为空", "options.key");
            }
            if (key.length() > 8) {
                throw ApiException.validation("选项标识最长 8 个字符", "options.key");
            }
            if (!keys.add(key)) {
                throw ApiException.validation("选项标识重复：" + key, "options.key");
            }
            if (option.content() == null || option.content().isBlank()) {
                throw ApiException.validation("选项内容不能为空：" + key, "options.content");
            }
            result.add(new QuestionDtos.OptionView(key, option.content().trim(), false));
        }
        return result;
    }

    private List<QuestionRepository.OptionRow> toOptionRows(String versionId, List<QuestionDtos.OptionView> options,
                                                            JsonNode canonicalAnswer, String questionType) {
        Set<String> correctKeys = switch (questionType) {
            case "single_choice" -> Set.of(canonicalAnswer.path("value").asText());
            case "multiple_choice" -> gradingService.keySet(canonicalAnswer.get("value"));
            default -> Set.of();
        };
        List<QuestionRepository.OptionRow> rows = new ArrayList<>(options.size());
        int order = 0;
        for (QuestionDtos.OptionView option : options) {
            order++;
            rows.add(new QuestionRepository.OptionRow(Ids.of("qo"), versionId, option.key(), option.content(),
                    correctKeys.contains(option.key()), order));
        }
        return rows;
    }

    private int difficultyOf(QuestionDtos.SaveRequest request) {
        return request.difficulty() == null ? 2 : request.difficulty();
    }

    private List<String> cleanTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag != null && !tag.isBlank()) {
                cleaned.add(tag.trim());
            }
        }
        if (cleaned.size() > 20) {
            throw ApiException.validation("知识点标签最多 20 个", "knowledgeTags");
        }
        return List.copyOf(cleaned);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 稳定内容哈希：字段顺序固定，避免 JSON 键顺序差异导致误判为内容变化。 */
    private String contentHash(String questionType, String stem, List<QuestionDtos.OptionView> options,
                               JsonNode answer, String explanation, JsonNode rubric, List<String> tags) {
        StringBuilder sb = new StringBuilder();
        sb.append(questionType).append('\u0001').append(stem == null ? "" : stem.trim()).append('\u0001');
        for (QuestionDtos.OptionView option : options) {
            sb.append(option.key()).append('=').append(option.content()).append(';');
        }
        sb.append('\u0001').append(json.write(answer)).append('\u0001')
                .append(explanation == null ? "" : explanation.trim()).append('\u0001')
                .append(rubric == null ? "" : json.write(rubric)).append('\u0001')
                .append(String.join(",", tags));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nowUtc() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    /** 版本内容快照，供试卷发布与判分使用。 */
    public record VersionContent(String questionId, String questionVersionId, int versionNo, String questionType,
                                 String title, String questionStatus, String stem, List<QuestionDtos.OptionView> options,
                                 JsonNode answer, String explanation, JsonNode rubric, List<String> knowledgeTags,
                                 String contentHash) {
    }
}
