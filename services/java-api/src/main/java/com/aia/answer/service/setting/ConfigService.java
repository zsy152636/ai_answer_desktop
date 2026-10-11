package com.aia.answer.service.setting;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 文件驱动配置的读缓存与热加载入口（{@code <UserData>/config/setting.json}）。
 *
 * <p>三条不可逾越的规则：
 * <ol>
 *   <li><b>非法配置保留 last-good</b>：{@link #reload()} 先完整解析 + 校验，只有通过才替换内存缓存；
 *       解析失败、Schema 违规或 {@code defaultProfileId} 悬空时，缓存保持上一份有效配置，
 *       并把可读原因放进 {@link SettingSnapshot#warnings()}。启动时 setting.json 非法也不阻断启动；</li>
 *   <li><b>保存即一致</b>：{@link #save(Consumer)} 在锁内完成「读 → 改 → 校验 → 原子写 → 重新加载」，
 *       校验不通过直接抛异常、不落盘；写失败时磁盘上仍是上一份有效配置（原子写的中间产物不会被替换）；</li>
 *   <li><b>密钥不落日志</b>：本类任何日志与异常消息只允许出现 profile 的 id / provider / model，
 *       绝不出现 {@code apiKey} 的值或其任何片段。</li>
 * </ol>
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    /** warnings 只保留最近这么多条，避免连续失败时无限增长。 */
    private static final int MAX_WARNINGS = 20;

    private final SettingStore store;
    private final SettingValidator validator;
    /**
     * 用 ObjectProvider 延迟取迁移器：迁移器本身要写 setting.json 并回读缓存，
     * 直接构造注入会与 ConfigService 形成循环依赖。
     */
    private final ObjectProvider<LegacyLlmConfigMigrator> legacyMigrator;
    /** 锁保护「读 → 改 → 校验 → 写 → 重载」整段；重入，便于 save 内部直接调 reload。 */
    private final ReentrantLock lock = new ReentrantLock();

    private final AtomicReference<SettingSnapshot> snapshot =
            new AtomicReference<>(SettingSnapshot.empty(Instant.now()));

    public ConfigService(SettingStore store, SettingValidator validator,
                         ObjectProvider<LegacyLlmConfigMigrator> legacyMigrator) {
        this.store = store;
        this.validator = validator;
        this.legacyMigrator = legacyMigrator;
        // 容器启动即加载一次：此时 setting.json 可能尚不存在（返回空快照），也顺带触发首次迁移
        initialLoad();
    }

    // ------------------------------------------------------------- 读取

    /** 当前内存快照（可能是「配置不存在」的空快照）。 */
    public SettingSnapshot snapshot() {
        return snapshot.get();
    }

    /** 当前配置修订号（文件字节 SHA-256）；配置不存在时为 {@code null}。 */
    public String revision() {
        return snapshot.get().revision();
    }

    /** 全部 LLM 档案（只读视图，元素仍为 JsonNode）。 */
    public List<JsonNode> profiles() {
        JsonNode parsed = snapshot.get().parsed();
        if (parsed == null) {
            return List.of();
        }
        JsonNode profiles = parsed.path("llm").path("profiles");
        if (!profiles.isArray()) {
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>(profiles.size());
        profiles.forEach(result::add);
        return List.copyOf(result);
    }

    /** 按 id 取档案。 */
    public Optional<JsonNode> profile(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return profiles().stream().filter(profile -> id.equals(profile.path("id").asText(null))).findFirst();
    }

    /** 默认档案 id；未指定时为 {@code null}。 */
    public String defaultProfileId() {
        JsonNode parsed = snapshot.get().parsed();
        if (parsed == null) {
            return null;
        }
        JsonNode value = parsed.path("llm").path("defaultProfileId");
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    /** 最近一次加载/保存留下的提示（不含任何密钥内容）。 */
    public List<String> warnings() {
        return snapshot.get().warnings();
    }

    // ------------------------------------------------------------- 加载

    /**
     * 从磁盘重新加载。
     *
     * <p>校验通过才替换缓存；任何失败都保留上一份有效配置，并把原因作为 warning 记录。
     * 本方法不抛异常：调用方是文件监听线程与启动路径，都不应该因配置问题而中断。
     */
    public void reload() {
        lock.lock();
        try {
            loadLocked();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 首次加载：先尝试一次性旧表迁移，再读盘。
     *
     * <p>迁移只在「setting.json 不存在且旧表有数据」时发生，绝不覆盖已有文件。
     */
    private void initialLoad() {
        LegacyLlmConfigMigrator migrator = legacyMigrator.getIfAvailable();
        if (migrator != null) {
            migrator.migrateIfNeeded();
        }
        lock.lock();
        try {
            loadLocked();
        } finally {
            lock.unlock();
        }
    }

    private void loadLocked() {
        SettingSnapshot loaded;
        try {
            loaded = store.read();
        } catch (IOException | RuntimeException e) {
            keepLastGood("配置未加载（保留上一份有效配置）：" + e.getMessage());
            return;
        }
        if (!loaded.present()) {
            snapshot.set(loaded);
            log.info("配置文件不存在或已删除，配置为空：{}", store.path());
            return;
        }
        SettingValidator.Result result = validator.validate(loaded.parsed());
        if (!result.valid()) {
            keepLastGood("配置校验未通过（保留上一份有效配置），共 " + result.errors().size() + " 条："
                    + describe(result.errors()));
            return;
        }
        snapshot.set(loaded);
        log.info("配置已加载：revision={}，{} 个 LLM 档案，默认档案={}",
                loaded.revision(), profiles().size(),
                defaultProfileId() == null ? "（未设置）" : defaultProfileId());
    }

    /** 保留上一份有效配置，并把原因并入现有 warnings，避免下一次成功加载把它抹掉。 */
    private void keepLastGood(String warning) {
        SettingSnapshot previous = snapshot.get();
        List<String> warnings = new ArrayList<>(previous.warnings());
        warnings.add(warning);
        // 反复失败（例如编辑器一直存着半截 JSON）时不能让 warnings 无限增长
        while (warnings.size() > MAX_WARNINGS) {
            warnings.remove(0);
        }
        snapshot.set(new SettingSnapshot(previous.parsed(), previous.revision(), previous.loadedAt(), warnings));
        log.warn(warning);
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

    // ------------------------------------------------------------- 保存

    /**
     * 「读 → 改 → 校验 → 原子写 → 重新加载」。
     *
     * <p>mutator 收到的是一份深拷贝，可以随意改动；返回后由本方法负责校验与落盘。
     * 校验失败抛 {@link ApiException}（{@code VALIDATION_ERROR}），磁盘保持原样。
     */
    public void save(Consumer<ObjectNode> mutator) {
        if (mutator == null) {
            throw new IllegalArgumentException("mutator 不能为空");
        }
        lock.lock();
        try {
            SettingSnapshot current = snapshot.get();
            ObjectNode target = current.present()
                    ? ((ObjectNode) current.parsed()).deepCopy()
                    : skeleton();
            mutator.accept(target);
            SettingValidator.Result result = validator.validate(target);
            if (!result.valid()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "配置校验未通过，未写入磁盘，共 " + result.errors().size() + " 条："
                                + describe(result.errors()));
            }
            try {
                store.writeAtomic(target);
            } catch (IOException e) {
                // 原子写失败：磁盘上仍是上一份有效配置
                throw new ApiException(ErrorCode.INTERNAL_ERROR, "配置写入失败，磁盘内容保持不变：" + e.getMessage());
            }
            // 锁内重入 reload：写后立即刷新内存缓存，避免调用方读到旧快照
            loadLocked();
        } finally {
            lock.unlock();
        }
    }

    /** 全新配置的最小骨架（仅结构与版本，不含任何密钥）。 */
    private ObjectNode skeleton() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("schemaVersion", SettingValidator.SCHEMA_VERSION);
        root.putObject("application").put("language", "zh-CN");
        ObjectNode llm = root.putObject("llm");
        llm.putNull("defaultProfileId");
        llm.putArray("profiles");
        return root;
    }
}
