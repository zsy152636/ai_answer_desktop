package com.aia.answer.service.setting.legacy;

import com.aia.answer.config.AppProperties;
import com.aia.answer.service.setting.LegacySecretExporter;
import com.sun.jna.platform.win32.Crypt32Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/**
 * 旧凭据的只读读取器：<b>仅用于 v1.0 → v1.1 一次性迁移</b>。
 *
 * <p>v1.0 把明文密钥交给「OS 凭据存储」（Windows DPAPI 优先，文件后端兜底），
 * 数据库里只留 {@code credential://llm/<id>} 形式的引用。v1.1 改为文件驱动配置后，
 * 运行期不再需要任何 OS 凭据依赖（ADR-014 取代 ADR-004），但首次启动仍必须把旧密钥
 * 一次性搬进 {@code setting.json} —— 否则用户的密钥会凭空消失。本类就是那次搬迁的唯一读取点。
 *
 * <p><b>不得被任何运行期配置路径引用</b>：它只被 {@code LegacyLlmConfigMigrator} 通过
 * {@link LegacySecretExporter} 使用一次（迁移完成后 {@code setting.json} 已存在，
 * 迁移不会再执行）。删除本类不会影响任何运行期功能，只会让历史密钥无法自动迁移。
 *
 * <p>读取顺序（与 v1.0 后端优先级一致）：
 * <ol>
 *   <li>{@code <data>/secrets/<safe(ref)>.dpapi}：DPAPI 加密文件，只有同一 Windows 用户能解；</li>
 *   <li>{@code <data>/secrets/<safe(ref)>.secret}：文件后端兜底的明文文件。</li>
 * </ol>
 *
 * <p>安全边界：返回值只允许写进 setting.json；本类的日志与异常消息只出现文件名与引用类型，
 * <b>绝不出现密钥、密钥片段或凭据引用本身</b>；读到任何异常一律返回空（迁移继续，用户手工重填）。
 *
 * <p>依赖说明：{@code jna-platform} 仍然保留在 pom.xml，因为 DPAPI 解密正是通过
 * {@link Crypt32Util} 完成的 —— 除了这段一次性迁移读取，运行期已不使用 JNA。
 */
@Component
public class LegacyCredentialReader implements LegacySecretExporter {

    private static final Logger log = LoggerFactory.getLogger(LegacyCredentialReader.class);

    /** v1.0 DPAPI 后端的文件后缀。 */
    private static final String DPAPI_SUFFIX = ".dpapi";
    /** v1.0 文件后端的文件后缀。 */
    private static final String FILE_SUFFIX = ".secret";

    private final Path secretsDir;

    @Autowired
    public LegacyCredentialReader(AppProperties props) {
        this(props.resolveDataDir());
    }

    /** 供迁移测试直接指定数据目录。 */
    public LegacyCredentialReader(Path dataDir) {
        this.secretsDir = dataDir.resolve("secrets");
    }

    @Override
    public Optional<String> exportSecret(String reference) {
        return read(reference);
    }

    /** 按旧凭据引用读取明文密钥；读不到（文件不存在、无法解密、环境不支持）一律返回空。 */
    public Optional<String> read(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        String safe = safeName(reference);
        Optional<String> dpapi = readDpapi(secretsDir.resolve(safe + DPAPI_SUFFIX));
        if (dpapi.isPresent()) {
            return dpapi;
        }
        return readPlain(secretsDir.resolve(safe + FILE_SUFFIX));
    }

    private Optional<String> readDpapi(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        // 非 Windows 或缺少本地库时 Crypt32Util 会抛错；迁移必须容错，因此捕获 Throwable
        if (!isWindows()) {
            return Optional.empty();
        }
        byte[] decrypted = null;
        try {
            byte[] encrypted = Files.readAllBytes(file);
            decrypted = Crypt32Util.cryptUnprotectData(encrypted);
            return Optional.of(new String(decrypted, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException | LinkageError e) {
            // 可能换了 Windows 用户、文件被替换、或本机 DPAPI 不可用：只记文件名，不记引用与内容
            log.warn("读取旧 DPAPI 凭据失败（迁移将提示手工重填）：{}（{}）", file.getFileName(), e.getMessage());
            return Optional.empty();
        } finally {
            if (decrypted != null) {
                Arrays.fill(decrypted, (byte) 0);
            }
        }
    }

    private Optional<String> readPlain(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String value = Files.readString(file, StandardCharsets.UTF_8);
            return value.isBlank() ? Optional.empty() : Optional.of(value);
        } catch (IOException e) {
            log.warn("读取旧文件凭据失败（迁移将提示手工重填）：{}（{}）", file.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 旧实现的文件名规则（原 {@code SecretFiles.pathOf}）：非 {@code [A-Za-z0-9._-]} 一律换成下划线。
     * 迁移必须按同一规则定位历史文件，否则读不到旧密钥。
     */
    private static String safeName(String reference) {
        return reference.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
