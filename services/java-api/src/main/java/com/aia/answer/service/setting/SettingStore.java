package com.aia.answer.service.setting;

import com.aia.answer.common.AtomicWrite;
import com.aia.answer.common.Json;
import com.aia.answer.config.AppProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * setting.json 的磁盘读写（文件驱动配置的唯一落地点）。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li><b>原子写</b>：先写同目录临时文件再 {@link java.nio.file.Files#move} 替换，
 *       保证任何时刻都不会被读到「半截 JSON」（复用 {@link AtomicWrite}，与端点文件同机制）；</li>
 *   <li><b>最小权限</b>：写后尽力把文件权限收成 {@code 0600}。Windows 的 ACL 模型不支持
 *       POSIX 位，因此失败（含 {@code UnsupportedOperationException}）只记 WARN，绝不致命；</li>
 *   <li><b>密钥不落日志</b>：本类只记录路径与修订号，任何日志/异常消息都不得包含文件内容
 *       （文件内容可能含 profile 的明文 apiKey）。解析错误只报行列号，不回显原文。</li>
 * </ol>
 */
@Component
public class SettingStore {

    private static final Logger log = LoggerFactory.getLogger(SettingStore.class);

    /** 配置文件固定文件名；监听器只关心这个名字。 */
    public static final String FILE_NAME = "setting.json";

    /**
     * 原子写临时文件特征（{@code setting.json.tmp-<nanoTime>}，见 {@link AtomicWrite}）。
     * 文件监听器必须忽略这类文件，否则每次保存都会多触发一轮重载。
     */
    private static final String TMP_MARKER = ".tmp";

    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final AppProperties props;
    private final ObjectMapper mapper;

    public SettingStore(AppProperties props, Json json) {
        this.props = props;
        this.mapper = json.mapper();
    }

    /** 配置文件路径（{@code <dataDir>/config/setting.json}）。 */
    public Path path() {
        return props.settingFile();
    }

    /**
     * 读取配置。文件不存在（或不是普通文件）时返回 {@link SettingSnapshot#empty}。
     *
     * @throws IOException 文件存在但读不了，或 JSON 语法非法（调用方据此保留上一份有效配置）
     */
    public SettingSnapshot read() throws IOException {
        Path file = path();
        if (!Files.isRegularFile(file)) {
            return SettingSnapshot.empty(Instant.now());
        }
        // 先取摘要再解析：revision 只由文件字节决定，与解析结果无关
        String revision = sha256(file);
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        JsonNode node;
        try {
            node = mapper.readTree(raw);
        } catch (JsonProcessingException e) {
            // 只报行列号，绝不回显原文（原文可能含密钥）
            throw new IOException("setting.json 语法错误：第 "
                    + (e.getLocation() == null ? "?" : e.getLocation().getLineNr()) + " 行第 "
                    + (e.getLocation() == null ? "?" : e.getLocation().getColumnNr()) + " 列", e);
        }
        if (node == null || !node.isObject()) {
            throw new IOException("setting.json 根节点必须是 JSON 对象");
        }
        return new SettingSnapshot(node, revision, Instant.now(), List.of());
    }

    /**
     * 原子写 + 尽力收紧权限。
     *
     * <p>写失败时磁盘上的上一份有效配置保持原样：临时文件与替换是两步，
     * 任一步失败都不会产生半个目标文件。
     */
    public void writeAtomic(JsonNode config) throws IOException {
        Path file = path();
        Path dir = file.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        String content = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        // AtomicWrite 自带「同目录临时文件 + ATOMIC_MOVE（不支持时降级 REPLACE_EXISTING）+ 清理」语义，
        // 与端点文件/恢复请求文件共用同一实现，不允许另写一套。
        AtomicWrite.writeString(file, content);
        restrictToOwner(file);
        log.info("配置文件已写入：{}（{} 个 LLM 档案）", file,
                config.path("llm").path("profiles").isArray() ? config.path("llm").path("profiles").size() : 0);
    }

    /** 文件字节的 SHA-256（小写 hex）。 */
    public static String sha256(Path file) throws IOException {
        return AtomicWrite.sha256(file);
    }

    /**
     * 尽力收紧为 0600（仅属主可读写）。
     *
     * <p>Windows 上 {@code PosixFileAttributeView} 不可用，这里会抛
     * {@code UnsupportedOperationException} 或返回 {@code null}，一律按「已尽力」处理：
     * 只记 WARN，不影响写入结果。
     */
    private void restrictToOwner(Path file) {
        try {
            var view = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
            if (view == null) {
                log.debug("当前文件系统不支持 POSIX 权限，跳过 0600 收紧：{}", file.getFileName());
                return;
            }
            view.setPermissions(OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException e) {
            // Windows：ACL 由用户目录继承策略决定，不因为收不了权限而让配置写入失败
            log.warn("配置文件权限收紧为 0600 失败（不影响配置生效）：{}（{}）", file.getFileName(), e.getMessage());
        }
    }

    /**
     * 是否是需要忽略的临时文件。
     *
     * <p>文件监听器据此过滤原子写的中间产物，避免「自己保存 → 自己重载」的无谓循环。
     */
    public static boolean isTemporary(String fileName) {
        if (fileName == null) {
            return true;
        }
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return !lower.equals(FILE_NAME) && lower.contains(TMP_MARKER);
    }

    /** 是否是配置文件本身（监听器唯一关心的名字）。 */
    public static boolean isSettingFile(String fileName) {
        return fileName != null && fileName.equalsIgnoreCase(FILE_NAME);
    }
}
