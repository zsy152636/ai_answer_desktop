package com.aia.answer.service.setting.legacy;

import com.sun.jna.platform.win32.Crypt32Util;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 旧凭据读取器测试（仅迁移用，不发网络请求）。
 *
 * <p>只覆盖「读取」这一件事：文件后端的明文回退、读不到不失败、以及 Windows 上 DPAPI 文件的解密。
 * 运行期已没有任何代码路径依赖它。
 */
class LegacyCredentialReaderTest {

    private static final String REFERENCE = "credential://llm/p1";

    @Test
    @DisplayName("文件后端：按旧命名规则读回明文")
    void readsPlaintextFallbackFile(@TempDir Path dataDir) throws IOException {
        Path secrets = Files.createDirectories(dataDir.resolve("secrets"));
        Files.writeString(secrets.resolve(safe(REFERENCE) + ".secret"), "sk-legacy-file",
                StandardCharsets.UTF_8);

        LegacyCredentialReader reader = new LegacyCredentialReader(dataDir);

        assertThat(reader.read(REFERENCE)).contains("sk-legacy-file");
        assertThat(reader.exportSecret(REFERENCE)).contains("sk-legacy-file");
    }

    @Test
    @DisplayName("读不到一律返回空且不抛异常（目录不存在、引用为空、文件是垃圾内容）")
    void missingSecretIsEmptyNotFailure(@TempDir Path dataDir) throws IOException {
        LegacyCredentialReader reader = new LegacyCredentialReader(dataDir);

        assertThat(reader.read(REFERENCE)).isEmpty();
        assertThat(reader.read(null)).isEmpty();
        assertThat(reader.read("  ")).isEmpty();

        // 空文件视为「没有密钥」
        Path secrets = Files.createDirectories(dataDir.resolve("secrets"));
        Files.writeString(secrets.resolve(safe(REFERENCE) + ".secret"), "", StandardCharsets.UTF_8);
        assertThat(reader.read(REFERENCE)).isEmpty();

        // 无法解密的 .dpapi（大概率是换了 Windows 用户）不能让迁移失败
        Files.write(secrets.resolve(safe(REFERENCE) + ".dpapi"),
                "not-a-dpapi-payload".getBytes(StandardCharsets.UTF_8));
        assertThat(reader.read(REFERENCE)).isNotPresent();
    }

    @Test
    @DisplayName("Windows：DPAPI 文件优先于同名明文文件被读取")
    void prefersDpapiOnWindows(@TempDir Path dataDir) throws IOException {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"),
                "DPAPI 只在 Windows 上可用");

        Path secrets = Files.createDirectories(dataDir.resolve("secrets"));
        // 旧实现把密钥加密后落盘；这里用同一套 DPAPI 调用还原一个历史文件
        byte[] encrypted = Crypt32Util.cryptProtectData("sk-legacy-dpapi".getBytes(StandardCharsets.UTF_8));
        Files.write(secrets.resolve(safe(REFERENCE) + ".dpapi"), encrypted);
        Files.writeString(secrets.resolve(safe(REFERENCE) + ".secret"), "sk-stale-fallback",
                StandardCharsets.UTF_8);

        LegacyCredentialReader reader = new LegacyCredentialReader(dataDir);

        Optional<String> value = reader.read(REFERENCE);
        assertThat(value).contains("sk-legacy-dpapi");
    }

    /** 与旧 {@code SecretFiles.pathOf} 完全一致的命名规则。 */
    private static String safe(String reference) {
        return reference.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
