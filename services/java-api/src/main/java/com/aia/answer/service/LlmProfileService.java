package com.aia.answer.service;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.AppInfo;
import com.aia.answer.common.ErrorCode;
import com.aia.answer.common.Ids;
import com.aia.answer.common.Json;
import com.aia.answer.domain.LlmDtos;
import com.aia.answer.repository.MetaRepository;
import com.aia.answer.service.setting.ConfigService;
import com.aia.answer.service.setting.SettingSnapshot;
import com.aia.answer.service.setting.SettingStore;
import com.aia.answer.service.setting.SettingValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 配置服务（契约 §6，文件驱动 v1.1）。
 *
 * <p><b>配置源是文件，不是数据库</b>：档案、默认档案、修订号全部读写
 * {@code <UserData>/config/setting.json}（经 {@link ConfigService} 缓存 + {@link SettingStore} 原子写），
 * 运行期不再触碰 {@code llm_profile}/{@code llm_config_state}/{@code llm_import_preview} 三张旧表
 * （迁移读取由 {@code LegacyLlmConfigMigrator} 独占，见 {@link com.aia.answer.service.setting.legacy.LegacyCredentialReader}）。
 *
 * <p><b>密钥只在内存与 setting.json</b>：明文 {@code apiKey} 的唯一持久位置是该文件；
 * 本服务对外只返回 {@code apiKeyMasked}（有值时恒为 {@code "******"}）与 {@code apiKeyConfigured}。
 * 任何日志、异常消息与审计 detail 都不得包含密钥值或其片段；含密钥导出必须显式传参并留审计。
 *
 * <p><b>失败不落盘</b>：写入前先在深拷贝上跑一次 {@link SettingValidator}，把 schema path 放进
 * {@link ApiException#details()}；只有校验通过才调用 {@link ConfigService#save} 做
 * 「原子写 + 重新加载」。校验失败时磁盘与内存缓存都保持原样。
 */
@Service
public class LlmProfileService {

    private static final Logger log = LoggerFactory.getLogger(LlmProfileService.class);

    /** 契约 §6：配置源标识固定为 setting.json。 */
    private static final String CONFIG_SOURCE = SettingStore.FILE_NAME;
    /** 脱敏导出建议文件名。 */
    private static final String EXPORT_FILE_NAME = SettingStore.FILE_NAME;
    /** 含密钥导出建议文件名（契约 §6）。 */
    private static final String EXPORT_WITH_KEYS_FILE_NAME = "setting-with-keys.json";

    private static final String MASK = "******";

    /** 导入预览只存在于进程内存中，10 分钟过期（契约 §6；重启即失效）。 */
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(10);
    /** 内存预览上限，超出时淘汰最快过期的一条，避免无界增长。 */
    private static final int MAX_PREVIEWS = 32;

    /** 连接测试的短超时上限：测试不能跟着档案里的长超时挂住。 */
    private static final int TEST_TIMEOUT_SECONDS = 15;
    private static final int TEST_CONNECT_TIMEOUT_SECONDS = 5;
    private static final int TEST_TIMEOUT_CAP = 30;
    private static final int TEST_CONNECT_TIMEOUT_CAP = 10;

    /** 从基础层 warning 文本里摘出 schema path（形如 {@code $.llm.profiles[0].provider}）。 */
    private static final Pattern SCHEMA_PATH = Pattern.compile("\\$[A-Za-z0-9_.\\[\\]]*");

    private final ConfigService configService;
    private final SettingStore store;
    private final SettingValidator validator;
    private final MetaRepository metaRepository;
    private final AiClient aiClient;
    private final Json json;
    /** 导入预览有效期；生产固定 10 分钟，测试可注入极短值验证「过期 → STATE_CONFLICT」。 */
    private final Duration previewTtl;

    /** previewId → 待确认的完整配置（深拷贝）。只存在于内存，进程重启即清空。 */
    private final Map<String, PendingPreview> previews = new ConcurrentHashMap<>();

    /**
     * 显式标注 @Autowired：本类另有一个「可指定预览有效期」的包内构造器（仅测试用），
     * 存在多个构造器时 Spring 必须能明确挑出生产用的这一个。
     */
    @Autowired
    public LlmProfileService(ConfigService configService, SettingStore store, SettingValidator validator,
                             MetaRepository metaRepository, AiClient aiClient, Json json) {
        this(configService, store, validator, metaRepository, aiClient, json, PREVIEW_TTL);
    }

    /** 测试专用装配：可指定预览有效期。 */
    LlmProfileService(ConfigService configService, SettingStore store, SettingValidator validator,
                      MetaRepository metaRepository, AiClient aiClient, Json json, Duration previewTtl) {
        this.configService = configService;
        this.store = store;
        this.validator = validator;
        this.metaRepository = metaRepository;
        this.aiClient = aiClient;
        this.json = json;
        this.previewTtl = previewTtl;
    }

    /** 内存预览：配置深拷贝 + 过期时刻。 */
    private record PendingPreview(ObjectNode config, Instant expiresAt) {
    }

    // ------------------------------------------------------------- 读取

    /** 档案列表 + 配置来源信息（契约 §6）。 */
    public LlmDtos.ProfileListResponse list() {
        SettingSnapshot snapshot = configService.snapshot();
        String defaultId = configService.defaultProfileId();
        List<LlmDtos.ProfileView> profiles = configService.profiles().stream()
                .map(profile -> toView(profile, defaultId))
                .toList();
        return new LlmDtos.ProfileListResponse(profiles, defaultId, configService.revision(), CONFIG_SOURCE,
                store.path().toAbsolutePath().toString(), iso(snapshot.loadedAt()), warnings());
    }

    /**
     * 组装调用 Python AI 服务的 {@code LlmCallProfile}（契约 §7）。
     *
     * <p>密钥从 setting.json 读取，只在内存中随本次调用传递；{@code provider} 需要密钥
     * （非 ollama）而该档案未配置时返回 {@code CREDENTIAL_MISSING}。
     */
    public JsonNode buildCallProfile(String profileId) {
        String id = profileId;
        if (id == null || id.isBlank()) {
            id = configService.defaultProfileId();
            if (id == null) {
                throw new ApiException(ErrorCode.CREDENTIAL_MISSING, "尚未配置默认 LLM 档案");
            }
        }
        final String resolvedId = id;
        JsonNode profile = configService.profile(resolvedId)
                .orElseThrow(() -> ApiException.notFound("LLM 档案 " + resolvedId));
        ObjectNode node = callProfileNode(resolvedId, profile);
        String apiKey = textual(profile, "apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            if (requiresApiKey(textual(profile, "provider"))) {
                throw new ApiException(ErrorCode.CREDENTIAL_MISSING,
                        "档案 " + resolvedId + " 未配置 apiKey，请在 setting.json 中填写后重试");
            }
        } else {
            node.put("apiKey", apiKey);
        }
        return node;
    }

    /** 是否存在可用的默认档案（AI 能力开关）。 */
    public boolean hasUsableProfile() {
        String id = configService.defaultProfileId();
        return id != null && configService.profile(id).isPresent();
    }

    // ------------------------------------------------------------- 写入

    /** 新建档案：写 setting.json（原子替换），configRevision 随之变化。 */
    public LlmDtos.ProfileView create(LlmDtos.ProfileInput input) {
        String id = (input.id() == null || input.id().isBlank())
                ? Ids.ulid().toLowerCase(Locale.ROOT) : input.id();
        mutateAndSave(root -> {
            ObjectNode llm = ensureLlm(root);
            ArrayNode profiles = ensureProfiles(llm);
            ObjectNode profile = profiles.addObject();
            profile.put("id", id);
            applyProfileFields(profile, input, null);
            // 尚未指定默认档案时，第一个档案自动成为默认，避免「配好了却用不上」
            if (!llm.path("defaultProfileId").isTextual() || llm.path("defaultProfileId").asText("").isBlank()) {
                llm.put("defaultProfileId", id);
            }
        });
        audit("llm_profile.created", "llm_profile", id);
        log.info("LLM 档案已创建：{}（provider={}，model={}）", id, input.provider(), input.model());
        return view(id);
    }

    /** 保存档案：{@code apiKey} 三态见 {@link LlmDtos.ProfileInput}。 */
    public LlmDtos.ProfileView update(String id, LlmDtos.ProfileInput input) {
        JsonNode existing = requireProfile(id);
        mutateAndSave(root -> {
            ObjectNode profile = findProfile(root, id);
            if (profile == null) {
                // 极端并发：档案在读取后被删除；此处按「不存在」处理，不落盘
                throw ApiException.notFound("LLM 档案 " + id);
            }
            applyProfileFields(profile, input, existing);
        });
        audit("llm_profile.updated", "llm_profile", id);
        log.info("LLM 档案已保存：{}（provider={}，model={}）", id, input.provider(), input.model());
        return view(id);
    }

    /** 删除档案；正在使用的默认档案需先切换，否则 {@code STATE_CONFLICT}。 */
    public void delete(String id) {
        requireProfile(id);
        if (id.equals(configService.defaultProfileId())) {
            throw ApiException.conflict("不能删除正在使用的默认档案，请先切换默认档案或禁用 AI");
        }
        mutateAndSave(root -> {
            ArrayNode profiles = ensureProfiles(ensureLlm(root));
            for (int index = 0; index < profiles.size(); index++) {
                if (id.equals(textual(profiles.get(index), "id"))) {
                    profiles.remove(index);
                    return;
                }
            }
            throw ApiException.notFound("LLM 档案 " + id);
        });
        audit("llm_profile.deleted", "llm_profile", id);
        log.info("LLM 档案已删除（含其密钥条目）：{}", id);
    }

    /** {@code POST /{id}/credential}：只更新该档案的 apiKey（写文件，不回显密钥）。 */
    public LlmDtos.ProfileView setCredential(String id, String apiKey) {
        requireProfile(id);
        String secret = apiKey == null ? "" : apiKey.trim();
        if (secret.isEmpty()) {
            throw ApiException.validation("apiKey 不能为空；清除密钥请调用 DELETE /{id}/credential", "apiKey");
        }
        mutateAndSave(root -> {
            ObjectNode profile = requireProfile(root, id);
            profile.put("apiKey", secret);
        });
        // 审计 detail 只记录「已写入」这一事实与修订号，不含密钥及其片段
        auditDetail("llm_profile.credential_bound", "llm_profile", id,
                Map.of("configRevision", revision()));
        log.info("LLM 档案密钥已写入 setting.json：{}", id);
        return view(id);
    }

    /** {@code DELETE /{id}/credential}：清除该档案的 apiKey（等价于 PUT 时 {@code apiKey: ""}）。 */
    public LlmDtos.ProfileView clearCredential(String id) {
        requireProfile(id);
        mutateAndSave(root -> requireProfile(root, id).remove("apiKey"));
        audit("llm_profile.credential_removed", "llm_profile", id);
        log.info("LLM 档案密钥已清除：{}", id);
        return view(id);
    }

    /** 切换默认档案；置 null 需显式 {@code disableAi=true}。 */
    public LlmDtos.ProfileListResponse setDefault(String profileId, Boolean disableAi) {
        if (profileId == null || profileId.isBlank()) {
            if (!Boolean.TRUE.equals(disableAi)) {
                throw ApiException.validation("置空默认档案需要显式 disableAi=true", "disableAi");
            }
            mutateAndSave(root -> ensureLlm(root).putNull("defaultProfileId"));
            audit("llm_profile.default_cleared", "llm_config", "1");
            log.info("默认 LLM 档案已置空（AI 能力关闭）");
            return list();
        }
        requireProfile(profileId);
        mutateAndSave(root -> ensureLlm(root).put("defaultProfileId", profileId));
        audit("llm_profile.default_changed", "llm_profile", profileId);
        log.info("默认 LLM 档案已切换：{}", profileId);
        return list();
    }

    // ------------------------------------------------------------- 校验

    /**
     * 纯校验：不写文件、不发网络请求（契约 §6）。
     *
     * <p>接受完整 setting.json，也接受仅 {@code llm} 段（自动补齐 {@code schemaVersion}）；
     * 旧 {@code llm-config.json} 形状里的 {@code apiKeyRef} 一律视为「无密钥」并忽略。
     */
    public LlmDtos.ValidationResponse validate(JsonNode config) {
        return validateNormalized(normalizeConfig(config));
    }

    /** 手动重读 setting.json（契约 §6）：校验失败返回 {@code CONFIG_FILE_INVALID} 且保留 last-good。 */
    public LlmDtos.ReloadResponse reload() {
        boolean valid = true;
        String failure = null;
        List<ApiException.FieldViolation> details = List.of();
        try {
            SettingSnapshot disk = store.read();
            if (disk.present()) {
                SettingValidator.Result result = validator.validate(disk.parsed());
                if (!result.valid()) {
                    valid = false;
                    failure = "setting.json 校验未通过（缓存保留上一份有效配置），共 " + result.errors().size()
                            + " 条：" + describe(result.errors());
                    details = toViolations(result.errors());
                }
            }
        } catch (IOException e) {
            // 语法错误 / 读不了：同样保留 last-good，并把行列号放进消息（契约 §0.3 的 CONFIG_JSON_INVALID 也归入此处）
            valid = false;
            failure = "setting.json 无法解析（缓存保留上一份有效配置）：" + e.getMessage();
            details = List.of(new ApiException.FieldViolation("$", e.getMessage()));
        }
        // 无论合法与否都调用基础层 reload：合法则替换缓存，非法则保留上一份有效配置并把原因并入 warnings
        configService.reload();
        if (!valid) {
            throw new ApiException(ErrorCode.CONFIG_FILE_INVALID, failure, details);
        }
        return new LlmDtos.ReloadResponse(configService.revision(), warnings());
    }

    // ------------------------------------------------------------- 导入 / 导出

    /**
     * 导入预览：<b>只在内存中</b>（TTL 10 分钟），不写 setting.json、不落任何表。
     */
    public LlmDtos.ImportPreviewResponse previewImport(JsonNode config) {
        ObjectNode incoming = normalizeConfig(config);
        LlmDtos.ValidationResponse validation = validateNormalized(incoming);
        String previewId = Ids.of("prev");
        Instant expiresAt = Instant.now().plus(previewTtl);
        purgeExpiredPreviews();
        // 非法配置也保留预览：confirm 时会以 VALIDATION_ERROR 明确拒绝，便于前端定位问题
        previews.put(previewId, new PendingPreview(incoming == null ? json.object() : incoming, expiresAt));
        evictOverflow();
        return new LlmDtos.ImportPreviewResponse(previewId, validation.summary(),
                changes(incoming), validation.errors(), iso(expiresAt));
    }

    /** 导入确认：应用变更并原子写入 setting.json；previewId 过期或不存在返回 {@code STATE_CONFLICT}。 */
    public LlmDtos.ImportConfirmResponse confirmImport(String previewId) {
        PendingPreview pending = previewId == null ? null : previews.get(previewId);
        if (pending == null) {
            throw ApiException.conflict("导入预览不存在或已过期，请重新预览");
        }
        if (!Instant.now().isBefore(pending.expiresAt())) {
            previews.remove(previewId);
            throw ApiException.conflict("导入预览已过期（有效期 10 分钟），请重新预览");
        }
        LlmDtos.ValidationResponse validation = validateNormalized(pending.config());
        if (!validation.valid()) {
            throw validationErrorFromIssues(validation.errors());
        }
        ObjectNode incoming = pending.config();
        int[] counts = new int[3];
        mutateAndSave(root -> {
            counts[0] = 0;
            counts[1] = 0;
            counts[2] = 0;
            applyImport(root, incoming, counts);
        });
        // 预览一次性使用：重复确认会落到「不存在」分支，避免重复导入
        previews.remove(previewId);
        auditDetail("llm_profile.imported", "llm_config", "1",
                Map.of("created", counts[0], "updated", counts[1], "unchanged", counts[2],
                        "configRevision", revision()));
        log.info("LLM 配置已导入：新增 {}，更新 {}，未变 {}", counts[0], counts[1], counts[2]);
        return new LlmDtos.ImportConfirmResponse(counts[0], counts[1], counts[2], configService.revision());
    }

    /**
     * 完整配置导出（契约 §6）。
     *
     * <p>{@code includeSecrets=false}（默认）时每个 profile 的 {@code apiKey} 显式置 {@code null}；
     * 只有显式 {@code includeSecrets=true} 才带明文，并且两种情况都写审计
     * {@code llm_config.exported}（含 {@code includeSecrets} 布尔），审计里只有布尔与数量。
     */
    public LlmDtos.ExportResponse export(Boolean includeSecrets) {
        boolean withSecrets = Boolean.TRUE.equals(includeSecrets);
        ObjectNode document = currentDocumentCopy();
        if (!withSecrets) {
            maskSecrets(document);
        }
        int profileCount = document.path("llm").path("profiles").isArray()
                ? document.path("llm").path("profiles").size() : 0;
        auditDetail("llm_config.exported", "llm_config", "1",
                Map.of("includeSecrets", withSecrets, "profileCount", profileCount));
        log.info("LLM 配置已导出：includeSecrets={}，{} 个档案", withSecrets, profileCount);
        return new LlmDtos.ExportResponse(document, withSecrets, withSecrets,
                withSecrets ? EXPORT_WITH_KEYS_FILE_NAME : EXPORT_FILE_NAME);
    }

    // ------------------------------------------------------------- 连接测试

    /**
     * 连接测试（契约 §6）：{@code profile} 内联对象 或 {@code profileId}（从 setting.json 读取，含其 apiKey）。
     *
     * <p>短超时 + 最小请求；响应只透传分类结果，绝不回传密钥或请求头。
     */
    public LlmDtos.TestConnectionResponse testConnection(LlmDtos.TestConnectionRequest request) {
        ObjectNode profile;
        String apiKey = null;
        if (request != null && request.profileId() != null && !request.profileId().isBlank()) {
            String id = request.profileId();
            JsonNode fromFile = configService.profile(id)
                    .orElseThrow(() -> ApiException.notFound("LLM 档案 " + id));
            profile = callProfileNode(id, fromFile);
            apiKey = textual(fromFile, "apiKey");
        } else if (request != null && request.profile() != null && request.profile().isObject()) {
            profile = ((ObjectNode) request.profile()).deepCopy();
            // 密钥只在前端到本方法这一跳存在；绝不写文件、绝不回显
            if (profile.has("apiKey")) {
                JsonNode key = profile.get("apiKey");
                apiKey = key.isNull() ? null : key.asText("");
                profile.remove("apiKey");
            }
            // 回显字段不是密钥，直接剔除，避免被误当成候选密钥传下去
            profile.remove("apiKeyMasked");
            profile.remove("apiKeyConfigured");
            requireTestProfile(profile);
        } else {
            throw ApiException.validation("必须提供 profileId 或 profile", "profile");
        }
        profile.put("timeoutSeconds", clamp(profile.path("timeoutSeconds").asInt(TEST_TIMEOUT_SECONDS),
                TEST_TIMEOUT_SECONDS, TEST_TIMEOUT_CAP));
        profile.put("connectTimeoutSeconds",
                clamp(profile.path("connectTimeoutSeconds").asInt(TEST_CONNECT_TIMEOUT_SECONDS),
                        TEST_CONNECT_TIMEOUT_SECONDS, TEST_CONNECT_TIMEOUT_CAP));
        profile.put("allowLocalNetwork", profile.path("allowLocalNetwork").asBoolean(false));

        ObjectNode payload = json.object();
        payload.set("profile", profile);
        if (apiKey != null && !apiKey.isBlank()) {
            payload.put("apiKey", apiKey);
        }
        long start = System.nanoTime();
        try {
            JsonNode data = aiClient.testConnection(payload);
            return new LlmDtos.TestConnectionResponse(
                    data.path("ok").asBoolean(false),
                    data.path("category").asText("protocol"),
                    data.path("latencyMs").asLong(elapsed(start)),
                    data.path("httpStatus").isMissingNode() || data.path("httpStatus").isNull()
                            ? null : data.path("httpStatus").asInt(),
                    data.path("modelEcho").isMissingNode() || data.path("modelEcho").isNull()
                            ? null : data.path("modelEcho").asText(),
                    mask(data.path("message").asText(""), apiKey),
                    data.path("suggestion").asText(""));
        } catch (ApiException e) {
            return new LlmDtos.TestConnectionResponse(false, categoryOf(e.code()), elapsed(start), null, null,
                    mask(e.getMessage(), apiKey), suggestionOf(e.code()));
        }
    }

    // ------------------------------------------------------------- 内部：读写辅助

    /**
     * 「先在深拷贝上校验 → 再把同一个 mutator 交给 {@link ConfigService#save}」。
     *
     * <p>这样做的唯一目的是让校验失败时的 details 带上准确 schema path
     * （基础层 {@code save} 抛出的校验异常只有文本消息）；失败时既不落盘也不动内存缓存。
     */
    private void mutateAndSave(java.util.function.Consumer<ObjectNode> mutator) {
        ObjectNode candidate = currentDocumentCopy();
        mutator.accept(candidate);
        SettingValidator.Result result = validator.validate(candidate);
        if (!result.valid()) {
            throw validationError(result.errors());
        }
        configService.save(mutator);
    }

    /** 当前配置的深拷贝；配置不存在时给出一份与基础层一致的最小骨架。 */
    private ObjectNode currentDocumentCopy() {
        SettingSnapshot snapshot = configService.snapshot();
        if (snapshot.present() && snapshot.parsed().isObject()) {
            return ((ObjectNode) snapshot.parsed()).deepCopy();
        }
        ObjectNode root = json.object();
        root.put("schemaVersion", SettingValidator.SCHEMA_VERSION);
        root.putObject("application").put("language", "zh-CN");
        ObjectNode llm = root.putObject("llm");
        llm.putNull("defaultProfileId");
        llm.putArray("profiles");
        return root;
    }

    /** 把 ProfileInput 写进档案节点；{@code existing} 为 null 表示新建（用默认值兜底）。 */
    private void applyProfileFields(ObjectNode profile, LlmDtos.ProfileInput input, JsonNode existing) {
        profile.put("name", input.name().trim());
        profile.put("provider", input.provider().trim());
        profile.put("baseUrl", normalizeBaseUrl(input.baseUrl()));
        profile.put("model", input.model().trim());
        // temperature 是 profile 级字段（Schema 0–2）：null = 保持原值，明确给了才写
        if (input.temperature() != null) {
            profile.put("temperature", input.temperature());
        }
        profile.put("timeoutSeconds", intOrDefault(input.timeoutSeconds(),
                existing, "timeoutSeconds", 60));
        profile.put("connectTimeoutSeconds", intOrDefault(input.connectTimeoutSeconds(),
                existing, "connectTimeoutSeconds", 5));
        Boolean allowLocal = input.allowLocalNetwork() != null
                ? input.allowLocalNetwork()
                : (existing != null && existing.hasNonNull("allowLocalNetwork")
                        ? existing.path("allowLocalNetwork").asBoolean(false) : Boolean.FALSE);
        profile.put("allowLocalNetwork", allowLocal);
        if (input.parameters() != null) {
            profile.set("parameters", json.mapper().valueToTree(input.parameters()));
        } else if (existing == null || !existing.path("parameters").isObject()) {
            profile.putObject("parameters");
        }
        applyApiKey(profile, input.apiKey());
    }

    /**
     * {@code apiKey} 三态（契约 §6）：
     * 字段省略（{@code null} 引用）= 保持原值；{@code ""}/{@code null} 节点 = 清除；非空 = 替换（trim）。
     *
     * <p>清除靠 {@code remove("apiKey")} 而不是写 null：Schema 里 {@code apiKey} 是 {@code string}，
     * 写 null 会让整份配置校验失败。
     */
    private void applyApiKey(ObjectNode profile, JsonNode apiKey) {
        if (apiKey == null) {
            return;
        }
        if (apiKey.isNull()) {
            profile.remove("apiKey");
            return;
        }
        String value = apiKey.asText("").trim();
        if (value.isEmpty()) {
            profile.remove("apiKey");
        } else {
            profile.put("apiKey", value);
        }
    }

    private Integer intOrDefault(Integer value, JsonNode existing, String field, int fallback) {
        if (value != null && value > 0) {
            return value;
        }
        if (existing != null && existing.path(field).isInt()) {
            return existing.path(field).asInt();
        }
        return fallback;
    }

    private ObjectNode ensureLlm(ObjectNode root) {
        JsonNode llm = root.path("llm");
        if (llm.isObject()) {
            return (ObjectNode) llm;
        }
        return root.putObject("llm");
    }

    private ArrayNode ensureProfiles(ObjectNode llm) {
        JsonNode profiles = llm.path("profiles");
        if (profiles.isArray()) {
            return (ArrayNode) profiles;
        }
        return llm.putArray("profiles");
    }

    /** 按 id 找档案节点。 */
    private ObjectNode findProfile(ObjectNode root, String id) {
        JsonNode profiles = root.path("llm").path("profiles");
        if (!profiles.isArray()) {
            return null;
        }
        for (JsonNode profile : profiles) {
            if (profile.isObject() && id.equals(textual(profile, "id"))) {
                return (ObjectNode) profile;
            }
        }
        return null;
    }

    private ObjectNode requireProfile(ObjectNode root, String id) {
        ObjectNode profile = findProfile(root, id);
        if (profile == null) {
            throw ApiException.notFound("LLM 档案 " + id);
        }
        return profile;
    }

    private JsonNode requireProfile(String id) {
        return configService.profile(id).orElseThrow(() -> ApiException.notFound("LLM 档案 " + id));
    }

    private LlmDtos.ProfileView view(String id) {
        JsonNode profile = requireProfile(id);
        return toView(profile, configService.defaultProfileId());
    }

    private LlmDtos.ProfileView toView(JsonNode profile, String defaultId) {
        String id = textual(profile, "id");
        String apiKey = textual(profile, "apiKey");
        boolean configured = apiKey != null && !apiKey.isBlank();
        return new LlmDtos.ProfileView(
                id,
                textual(profile, "name"),
                textual(profile, "provider"),
                textual(profile, "baseUrl"),
                textual(profile, "model"),
                // 有值时恒为固定掩码，绝不回显任何密钥片段
                configured ? MASK : null,
                configured,
                numberOrNull(profile, "temperature"),
                positive(profile, "timeoutSeconds", 60),
                positive(profile, "connectTimeoutSeconds", 5),
                profile.path("allowLocalNetwork").asBoolean(false),
                parameters(profile.path("parameters")),
                id != null && id.equals(defaultId));
    }

    /** 调用 Python 用的档案节点（不含密钥；密钥由调用方决定是否附加）。 */
    private ObjectNode callProfileNode(String id, JsonNode profile) {
        ObjectNode node = json.object();
        node.put("id", id);
        node.put("provider", textual(profile, "provider"));
        node.put("baseUrl", textual(profile, "baseUrl"));
        node.put("model", textual(profile, "model"));
        node.put("timeoutSeconds", positive(profile, "timeoutSeconds", 60));
        node.put("connectTimeoutSeconds", positive(profile, "connectTimeoutSeconds", 5));
        // 必须把「本地/内网模型」声明一起传下去：Python 侧据此放行 loopback，
        // 否则本地模型会（正确地）被 SSRF 防护判定为 BASE_URL_BLOCKED。
        node.put("allowLocalNetwork", profile.path("allowLocalNetwork").asBoolean(false));
        JsonNode parameters = profile.path("parameters");
        ObjectNode callParameters = parameters.isObject() ? ((ObjectNode) parameters).deepCopy() : json.object();
        // Python 的 LlmCallProfile 只认 parameters.temperature（profile 级 temperature 属新的配置文件模型）：
        // 这里做一次适配，保证用户填的温度真的会作用到调用上；parameters 里已显式写了就不覆盖。
        Double temperature = numberOrNull(profile, "temperature");
        if (temperature != null && !callParameters.has("temperature")) {
            callParameters.put("temperature", temperature);
        }
        node.set("parameters", callParameters);
        return node;
    }

    // ------------------------------------------------------------- 内部：配置形状归一化

    /**
     * 把入参归一化成一份完整 setting.json，交给 {@link SettingValidator}：
     * <ul>
     *   <li>含 {@code llm} 段 = 完整 setting.json，原样校验（脱敏导出的 {@code apiKey: null} 会被剔除）；</li>
     *   <li>含 {@code profiles}/{@code defaultProfileId} = 仅 llm 段或旧 {@code llm-config.json}，
     *       补齐 {@code schemaVersion} 并保留其中的版本号（版本不受支持时仍会报错）；
     *       旧形状的 {@code apiKeyRef} 与 {@code exportedAt} 一律视为「无密钥」而忽略；</li>
     *   <li>其余形状原样深拷贝，由校验器给出「根节点/必填字段」类错误。</li>
     * </ul>
     */
    private ObjectNode normalizeConfig(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            return null;
        }
        ObjectNode source = (ObjectNode) raw;
        if (source.has("llm")) {
            ObjectNode copy = source.deepCopy();
            sanitizeProfiles(copy.path("llm").path("profiles"));
            return copy;
        }
        if (source.has("profiles") || source.has("defaultProfileId")) {
            ObjectNode root = json.object();
            root.put("schemaVersion", source.path("schemaVersion").asInt(SettingValidator.SCHEMA_VERSION));
            ObjectNode llm = root.putObject("llm");
            if (source.has("defaultProfileId")) {
                llm.set("defaultProfileId", source.get("defaultProfileId").deepCopy());
            }
            ArrayNode profiles = llm.putArray("profiles");
            for (JsonNode profile : source.path("profiles")) {
                if (!profile.isObject()) {
                    profiles.add(profile.deepCopy());
                    continue;
                }
                ObjectNode copy = ((ObjectNode) profile).deepCopy();
                // 旧形状的唯一密钥字段是「引用」，不是密钥本身：视为无密钥信息
                copy.remove("apiKeyRef");
                if (copy.has("apiKey") && copy.get("apiKey").isNull()) {
                    copy.remove("apiKey");
                }
                profiles.add(copy);
            }
            return root;
        }
        return source.deepCopy();
    }

    /** 脱敏导出的文档里 {@code apiKey} 是 null：schema 只允许字符串，校验前剔除。 */
    private void sanitizeProfiles(JsonNode profiles) {
        if (!profiles.isArray()) {
            return;
        }
        for (JsonNode profile : profiles) {
            if (profile.isObject() && profile.path("apiKey").isNull()) {
                ((ObjectNode) profile).remove("apiKey");
            }
        }
    }

    private LlmDtos.ValidationResponse validateNormalized(ObjectNode normalized) {
        if (normalized == null) {
            return new LlmDtos.ValidationResponse(false,
                    List.of(new LlmDtos.ValidationIssue("$", "配置根节点必须是 JSON 对象")),
                    new LlmDtos.ValidationSummary(0, List.of()));
        }
        SettingValidator.Result result = validator.validate(normalized);
        List<LlmDtos.ValidationIssue> issues = result.errors().stream()
                .map(error -> new LlmDtos.ValidationIssue(error.path(), error.message()))
                .toList();
        JsonNode profiles = normalized.path("llm").path("profiles");
        Set<String> providers = new LinkedHashSet<>();
        if (profiles.isArray()) {
            for (JsonNode profile : profiles) {
                String provider = textual(profile, "provider");
                if (provider != null && !provider.isBlank()) {
                    providers.add(provider);
                }
            }
        }
        LlmDtos.ValidationSummary summary = new LlmDtos.ValidationSummary(
                profiles.isArray() ? profiles.size() : 0, new ArrayList<>(new TreeSet<>(providers)));
        return new LlmDtos.ValidationResponse(result.valid(), issues, summary);
    }

    // ------------------------------------------------------------- 内部：导入差异与应用

    /** 预览差异：create / update / unchanged / conflict。 */
    private List<LlmDtos.ChangeItem> changes(ObjectNode incoming) {
        if (incoming == null) {
            return List.of();
        }
        List<LlmDtos.ChangeItem> items = new ArrayList<>();
        for (JsonNode candidate : incoming.path("llm").path("profiles")) {
            String id = textual(candidate, "id");
            Optional<JsonNode> existing = id == null ? Optional.empty() : configService.profile(id);
            String name = textual(candidate, "name");
            if (existing.isEmpty()) {
                items.add(new LlmDtos.ChangeItem("create", id, name, List.of()));
                continue;
            }
            JsonNode current = existing.get();
            List<String> fields = new ArrayList<>();
            if (!equalsText(current, "name", candidate, "name")) {
                fields.add("name");
            }
            if (!equalsText(current, "provider", candidate, "provider")) {
                fields.add("provider");
            }
            if (!equalsText(current, "baseUrl", candidate, "baseUrl")) {
                fields.add("baseUrl");
            }
            if (!equalsText(current, "model", candidate, "model")) {
                fields.add("model");
            }
            if (!java.util.Objects.equals(numberOrNull(current, "temperature"),
                    numberOrNull(candidate, "temperature"))) {
                fields.add("temperature");
            }
            if (positive(current, "timeoutSeconds", 60) != positive(candidate, "timeoutSeconds", 60)) {
                fields.add("timeoutSeconds");
            }
            if (positive(current, "connectTimeoutSeconds", 5) != positive(candidate, "connectTimeoutSeconds", 5)) {
                fields.add("connectTimeoutSeconds");
            }
            if (current.path("allowLocalNetwork").asBoolean(false)
                    != candidate.path("allowLocalNetwork").asBoolean(false)) {
                fields.add("allowLocalNetwork");
            }
            if (!sameParameters(current, candidate)) {
                fields.add("parameters");
            }
            boolean hasKey = textual(current, "apiKey") != null && !textual(current, "apiKey").isBlank();
            String incomingKey = textual(candidate, "apiKey");
            if (incomingKey != null && !incomingKey.equals(textual(current, "apiKey"))) {
                fields.add("apiKey");
            }
            if (fields.isEmpty()) {
                items.add(new LlmDtos.ChangeItem("unchanged", id, textual(current, "name"), List.of()));
            } else if (hasKey && incomingKey == null
                    && (fields.contains("provider") || fields.contains("baseUrl"))) {
                // 端点变了但导入内容没带密钥：密钥很可能不再适用，交给用户确认
                items.add(new LlmDtos.ChangeItem("conflict", id, textual(current, "name"), fields));
            } else {
                items.add(new LlmDtos.ChangeItem("update", id, textual(current, "name"), fields));
            }
        }
        return items;
    }

    /** 把导入内容应用到配置节点（在 mutator 内执行，越界由基础层与校验器兜住）。 */
    private void applyImport(ObjectNode root, ObjectNode incoming, int[] counts) {
        ObjectNode llm = ensureLlm(root);
        ArrayNode profiles = ensureProfiles(llm);
        for (JsonNode candidate : incoming.path("llm").path("profiles")) {
            if (!candidate.isObject()) {
                continue;
            }
            String id = textual(candidate, "id");
            ObjectNode existing = id == null ? null : findProfile(root, id);
            if (existing == null) {
                ObjectNode created = profiles.addObject();
                created.put("id", id);
                applyImportFields(created, (ObjectNode) candidate, null);
                counts[0]++;
                continue;
            }
            ObjectNode before = existing.deepCopy();
            applyImportFields(existing, (ObjectNode) candidate, before);
            if (before.equals(existing)) {
                counts[2]++;
            } else {
                counts[1]++;
            }
        }
        JsonNode defaultId = incoming.path("llm").path("defaultProfileId");
        if (defaultId.isTextual() && !defaultId.asText().isBlank()) {
            llm.put("defaultProfileId", defaultId.asText());
        } else if (defaultId.isNull() && incoming.path("llm").has("defaultProfileId")) {
            llm.putNull("defaultProfileId");
        }
    }

    /**
     * 导入单条档案：与 PUT 的差别只在 {@code apiKey} —— 导入文件里
     * 「缺失或 null」表示「无密钥信息」，此时<b>保留</b>现有密钥（绝不因为一份脱敏导出而清掉密钥）；
     * {@code ""} 才是显式清除。
     */
    private void applyImportFields(ObjectNode profile, ObjectNode candidate, ObjectNode existing) {
        profile.put("name", text(candidate, "name", existing, ""));
        profile.put("provider", text(candidate, "provider", existing, ""));
        profile.put("baseUrl", normalizeBaseUrl(text(candidate, "baseUrl", existing, "")));
        profile.put("model", text(candidate, "model", existing, ""));
        if (candidate.path("temperature").isNumber()) {
            profile.put("temperature", candidate.path("temperature").asDouble());
        }
        profile.put("timeoutSeconds", firstPositive(candidate, existing, "timeoutSeconds", 60));
        profile.put("connectTimeoutSeconds", firstPositive(candidate, existing, "connectTimeoutSeconds", 5));
        profile.put("allowLocalNetwork", candidate.has("allowLocalNetwork")
                ? candidate.path("allowLocalNetwork").asBoolean(false)
                : (existing != null && existing.path("allowLocalNetwork").asBoolean(false)));
        if (candidate.path("parameters").isObject()) {
            profile.set("parameters", candidate.path("parameters").deepCopy());
        } else if (existing == null || !existing.path("parameters").isObject()) {
            profile.putObject("parameters");
        }
        if (candidate.has("apiKey")) {
            JsonNode key = candidate.get("apiKey");
            if (key.isTextual()) {
                String value = key.asText("").trim();
                if (value.isEmpty()) {
                    profile.remove("apiKey");
                } else {
                    profile.put("apiKey", value);
                }
            }
        }
    }

    private int firstPositive(ObjectNode candidate, ObjectNode existing, String field, int fallback) {
        if (candidate.path(field).isInt() && candidate.path(field).asInt() > 0) {
            return candidate.path(field).asInt();
        }
        if (existing != null && existing.path(field).isInt() && existing.path(field).asInt() > 0) {
            return existing.path(field).asInt();
        }
        return fallback;
    }

    // ------------------------------------------------------------- 内部：预览内存表

    private void purgeExpiredPreviews() {
        Instant now = Instant.now();
        previews.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
    }

    private void evictOverflow() {
        while (previews.size() > MAX_PREVIEWS) {
            previews.entrySet().stream()
                    .min(Comparator.comparing((Map.Entry<String, PendingPreview> entry)
                            -> entry.getValue().expiresAt()))
                    .map(Map.Entry::getKey)
                    .ifPresent(previews::remove);
        }
    }

    // ------------------------------------------------------------- 内部：错误与提示

    /** 校验失败：details 里逐条给出 schema path，消息里再带第一条，便于直接定位。 */
    private ApiException validationError(List<SettingValidator.Error> errors) {
        List<ApiException.FieldViolation> details = toViolations(errors);
        String first = errors.isEmpty() ? "" : errors.get(0).path() + " " + errors.get(0).message();
        return new ApiException(ErrorCode.VALIDATION_ERROR,
                "配置校验未通过，未写入 setting.json，共 " + errors.size() + " 条：" + first, details);
    }

    /** 同一语义的另一入口：issues 已由 {@link #validateNormalized} 归一化过。 */
    private ApiException validationErrorFromIssues(List<LlmDtos.ValidationIssue> issues) {
        List<ApiException.FieldViolation> details = issues.stream()
                .map(issue -> new ApiException.FieldViolation(issue.path(), issue.message()))
                .toList();
        String first = issues.isEmpty() ? "" : issues.get(0).path() + " " + issues.get(0).message();
        return new ApiException(ErrorCode.VALIDATION_ERROR,
                "配置校验未通过，未写入 setting.json，共 " + issues.size() + " 条：" + first, details);
    }

    private List<ApiException.FieldViolation> toViolations(List<SettingValidator.Error> errors) {
        return errors.stream()
                .map(error -> new ApiException.FieldViolation(error.path(), error.message()))
                .toList();
    }

    private String describe(List<SettingValidator.Error> errors) {
        StringBuilder sb = new StringBuilder();
        for (SettingValidator.Error error : errors) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(error.path()).append(' ').append(error.message());
        }
        return sb.toString();
    }

    private List<LlmDtos.ConfigWarning> warnings() {
        return configService.warnings().stream().map(this::toWarning).toList();
    }

    /** 基础层只给出可读文本：这里补上错误码与 schema path，形状对齐契约 §6。 */
    private LlmDtos.ConfigWarning toWarning(String message) {
        String code;
        if (message.contains("语法错误")) {
            code = ErrorCode.CONFIG_JSON_INVALID.name();
        } else if (message.contains("校验") || message.contains("未加载") || message.contains("未指向")) {
            code = ErrorCode.CONFIG_FILE_INVALID.name();
        } else {
            code = "CONFIG_WARNING";
        }
        Matcher matcher = SCHEMA_PATH.matcher(message);
        String path = matcher.find() ? matcher.group() : null;
        return new LlmDtos.ConfigWarning(code, message, path);
    }

    private void requireTestProfile(ObjectNode profile) {
        for (String field : List.of("provider", "baseUrl", "model")) {
            String value = textual(profile, field);
            if (value == null || value.isBlank()) {
                throw ApiException.validation("连接测试需要 " + field, field);
            }
        }
        String baseUrl = textual(profile, "baseUrl");
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw ApiException.validation("baseUrl 必须以 http:// 或 https:// 开头", "baseUrl");
        }
    }

    private String categoryOf(ErrorCode code) {
        return switch (code) {
            case LLM_AUTH_FAILED -> "auth";
            case LLM_CONNECT_TIMEOUT -> "timeout";
            case BASE_URL_BLOCKED -> "blocked";
            case LLM_UNAVAILABLE -> "unavailable";
            default -> "protocol";
        };
    }

    private String suggestionOf(ErrorCode code) {
        return switch (code) {
            case LLM_AUTH_FAILED -> "请检查密钥是否有效，或该模型是否需要额外的权限设置";
            case LLM_CONNECT_TIMEOUT -> "请确认端点地址、网络连通性与超时设置";
            case BASE_URL_BLOCKED -> "远程地址默认禁止访问私网与云元数据地址；本地模型请显式开启「本地/内网模型」";
            case LLM_UNAVAILABLE -> "请先启动 Python AI 服务，或确认 AI 服务端口未被占用";
            case CREDENTIAL_MISSING -> "请在 <UserData>/config/setting.json 中为该档案填写 apiKey";
            default -> "请检查 provider 与端点协议是否匹配";
        };
    }

    /** 兜底脱敏：万一上游把密钥写进了消息，也不允许从本接口回显出去。 */
    private String mask(String text, String apiKey) {
        if (text == null || apiKey == null || apiKey.isBlank() || !text.contains(apiKey)) {
            return text;
        }
        return text.replace(apiKey, MASK);
    }

    // ------------------------------------------------------------- 内部：杂项

    private void maskSecrets(ObjectNode document) {
        JsonNode profiles = document.path("llm").path("profiles");
        if (!profiles.isArray()) {
            return;
        }
        for (JsonNode profile : profiles) {
            if (profile.isObject()) {
                ((ObjectNode) profile).putNull("apiKey");
            }
        }
    }

    private void audit(String eventType, String entityType, String entityId) {
        auditDetail(eventType, entityType, entityId, Map.of("configRevision", String.valueOf(revision())));
    }

    private void auditDetail(String eventType, String entityType, String entityId, Map<String, ?> detail) {
        // detail 由调用方构造：只允许放 id / 数量 / 修订号 / includeSecrets 这类非敏感事实
        metaRepository.audit(eventType, entityType, entityId, json.write(detail), nowUtc());
    }

    private String revision() {
        String revision = configService.revision();
        return revision == null ? "" : revision;
    }

    private Map<String, Object> parameters(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return json.convertMap(json.write(node), Object.class);
    }

    private boolean equalsText(JsonNode left, String leftField, JsonNode right, String rightField) {
        return java.util.Objects.equals(textual(left, leftField), textual(right, rightField));
    }

    /** 参数比较：缺失/null 一律等价于空对象，否则「没写 parameters」会被误判成变更。 */
    private boolean sameParameters(JsonNode current, JsonNode candidate) {
        JsonNode left = current.path("parameters");
        JsonNode right = candidate.path("parameters");
        return (left.isObject() ? left : json.object()).equals(right.isObject() ? right : json.object());
    }

    private String text(JsonNode candidate, String field, JsonNode existing, String fallback) {
        String value = textual(candidate, field);
        if (value != null) {
            return value;
        }
        if (existing != null) {
            String existingValue = textual(existing, field);
            if (existingValue != null) {
                return existingValue;
            }
        }
        return fallback;
    }

    /** 只接受文本节点：{@code NullNode.asText()} 会返回字符串 "null"，不能用来判断「有没有密钥」。 */
    private String textual(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private int positive(JsonNode node, String field, int fallback) {
        JsonNode value = node.path(field);
        return value.isInt() && value.asInt() > 0 ? value.asInt() : fallback;
    }

    /** 数值字段（如 profile 级 {@code temperature}）；缺失或非数值一律为 null。 */
    private Double numberOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isNumber() ? value.asDouble() : null;
    }

    private boolean requiresApiKey(String provider) {
        // 与 Python 侧 SUPPORTED_PROVIDERS 的语义一致：ollama 为本地模型，不需要密钥
        return !"ollama".equals(provider);
    }

    private int clamp(int value, int fallback, int cap) {
        int effective = value > 0 ? value : fallback;
        return Math.min(effective, cap);
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String iso(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }

    private static String nowUtc() {
        return iso(Instant.now());
    }

    /** 保留：供 health/meta 展示固定契约版本。 */
    public String contractVersion() {
        return AppInfo.CONTRACT_VERSION;
    }
}
