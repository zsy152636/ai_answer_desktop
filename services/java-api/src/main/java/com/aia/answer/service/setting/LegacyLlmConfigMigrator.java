package com.aia.answer.service.setting;

import com.aia.answer.common.Json;
import com.aia.answer.repository.LlmProfileRepository;
import com.aia.answer.repository.MetaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 首次启动把旧表配置迁移成 setting.json（用户已确认「保留旧表 + 自动迁移」）。
 *
 * <p>触发条件（三者同时满足，缺一不可）：
 * <ol>
 *   <li>setting.json <b>不存在</b>——绝不覆盖用户已有的配置文件；</li>
 *   <li>{@code llm_profile} 表有数据——空表说明用户从没用过旧配置，不该凭空造文件；</li>
 *   <li>本次进程尚未尝试过——迁移幂等，重复启动不再执行。</li>
 * </ol>
 *
 * <p>迁移完成后写 {@code app_meta.llm_config_migrated_at} 标记 + 审计事件 {@code llm_config.migrated}。
 * 旧表与旧凭据一律不删，保留回退路径。
 *
 * <p>密钥边界：已绑定密钥只经 {@link LegacySecretExporter} 读一次并写进 setting.json 的
 * {@code apiKey}；读不到的档案只在 warnings 里记「N 个 profile 的密钥需手工重填」，
 * <b>日志、异常与审计事件中一律不得出现密钥或其片段，也不得出现凭据引用本身</b>。
 */
@Component
public class LegacyLlmConfigMigrator {

    private static final Logger log = LoggerFactory.getLogger(LegacyLlmConfigMigrator.class);

    /** app_meta 中的迁移完成标记（供后续切片判断是否需要展示迁移提示）。 */
    public static final String MIGRATED_AT_KEY = "llm_config_migrated_at";

    private final LlmProfileRepository llmProfileRepository;
    private final MetaRepository metaRepository;
    private final SettingStore store;
    private final Json json;

    /** 密钥导出点：可替换。后续切片删除凭据包时，这里退化为「读不到密钥」。 */
    private final LegacySecretExporter secretExporter;

    /** 迁移是「每进程一次」的动作，用 volatile + 同步方法保证多线程只跑一轮。 */
    private volatile boolean attempted;

    /**
     * @param secretExporter 旧凭据导出点，允许缺失（缺失时按「密钥读不到」处理，
     *                       迁移照常完成并在 warnings 里提示手工重填）
     */
    @Autowired
    public LegacyLlmConfigMigrator(LlmProfileRepository llmProfileRepository, MetaRepository metaRepository,
                                   SettingStore store, Json json,
                                   ObjectProvider<LegacySecretExporter> secretExporter) {
        this.llmProfileRepository = llmProfileRepository;
        this.metaRepository = metaRepository;
        this.store = store;
        this.json = json;
        this.secretExporter = secretExporter.getIfAvailable();
    }

    /** 供测试直接装配的构造器（显式给出导出点）。<b>不是</b> Spring 的候选构造器。 */
    LegacyLlmConfigMigrator(LlmProfileRepository llmProfileRepository, MetaRepository metaRepository,
                            SettingStore store, Json json, LegacySecretExporter secretExporter) {
        this.llmProfileRepository = llmProfileRepository;
        this.metaRepository = metaRepository;
        this.store = store;
        this.json = json;
        this.secretExporter = secretExporter;
    }

    /**
     * 按需迁移。
     *
     * @return 本次是否真的写出了 setting.json
     */
    public synchronized boolean migrateIfNeeded() {
        Path target = store.path();
        if (Files.exists(target)) {
            // 已有配置文件：无条件让路，迁移不覆盖任何用户配置
            return false;
        }
        if (attempted) {
            return false;
        }
        attempted = true;

        List<LlmProfileRepository.ProfileRow> rows;
        try {
            rows = llmProfileRepository.list();
        } catch (RuntimeException e) {
            log.warn("旧 LLM 配置表不可读，跳过迁移（本次不生成 setting.json）：{}", e.getMessage());
            return false;
        }
        if (rows.isEmpty()) {
            log.info("旧 LLM 配置表为空，无需迁移");
            return false;
        }

        List<String> warnings = new ArrayList<>();
        ObjectNode config = build(rows, warnings);
        String now = nowUtc();
        try {
            store.writeAtomic(config);
        } catch (IOException e) {
            log.error("旧配置迁移写入失败，setting.json 未生成（旧表数据完好）：{}", e.getMessage());
            return false;
        }
        metaRepository.put(MIGRATED_AT_KEY, now, now);
        metaRepository.audit("llm_config.migrated", "llm_config", "1",
                json.write(java.util.Map.of("profiles", rows.size(), "warningCount", warnings.size())), now);
        // 日志只允许出现档案数量，不出现任何档案名以外的密钥信息
        log.info("旧配置已迁移为 {}：{} 个档案，{} 条提示", target.getFileName(), rows.size(), warnings.size());
        for (String warning : warnings) {
            log.warn("迁移提示：{}", warning);
        }
        return true;
    }

    /** 由旧表构造 setting.json 内容。 */
    private ObjectNode build(List<LlmProfileRepository.ProfileRow> rows, List<String> warnings) {
        ObjectNode root = json.object();
        root.put("schemaVersion", SettingValidator.SCHEMA_VERSION);
        root.putObject("application").put("language", "zh-CN");

        ObjectNode llm = root.putObject("llm");
        String defaultId = rows.stream()
                .filter(LlmProfileRepository.ProfileRow::isDefault)
                .map(LlmProfileRepository.ProfileRow::id)
                .findFirst()
                .orElse(null);
        if (defaultId == null) {
            llm.putNull("defaultProfileId");
        } else {
            llm.put("defaultProfileId", defaultId);
        }

        ArrayNode profiles = llm.putArray("profiles");
        int missingSecrets = 0;
        for (LlmProfileRepository.ProfileRow row : rows) {
            ObjectNode profile = profiles.addObject();
            profile.put("id", row.id());
            profile.put("name", row.name());
            profile.put("provider", row.provider());
            profile.put("baseUrl", row.baseUrl());
            profile.put("model", row.model());
            profile.put("timeoutSeconds", row.timeoutSeconds());
            profile.put("connectTimeoutSeconds", row.connectTimeoutSeconds());
            profile.put("allowLocalNetwork", row.allowLocalNetwork());
            JsonNode parameters = json.readTreeOrNull(row.parametersJson());
            profile.set("parameters", parameters != null && parameters.isObject() ? parameters : json.object());
            // temperature 是 profile 级字段，旧表没有这一列：只有写在 parameters 里时才搬上来
            if (parameters != null && parameters.hasNonNull("temperature")) {
                profile.set("temperature", parameters.get("temperature"));
            }
            // 密钥：能读到就搬进 apiKey，读不到只记数量提示（绝不记引用或密钥片段）
            String reference = row.apiKeyRef();
            Optional<String> secret = (reference == null || reference.isBlank() || secretExporter == null)
                    ? Optional.empty()
                    : secretExporter.exportSecret(reference);
            if (secret.isPresent() && !secret.get().isBlank()) {
                profile.put("apiKey", secret.get());
            } else if (reference != null && !reference.isBlank()) {
                missingSecrets++;
            }
        }
        if (missingSecrets > 0) {
            warnings.add(missingSecrets + " 个 profile 的密钥需手工重填");
        }
        return root;
    }

    private static String nowUtc() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
