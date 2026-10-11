package com.aia.answer.service.setting;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * setting.json 磁盘读写测试。
 *
 * <p>刻意使用真实临时目录与真实文件：内存文件系统体现不出 ATOMIC_MOVE 的替换语义、
 * 临时文件残留与权限设置的真实行为，而这些正是本类要保证的。
 */
class SettingStoreTest {

    @Test
    @DisplayName("setting.json 不存在时返回空快照，不抛异常")
    void missingFileReturnsEmpty(@TempDir Path dataDir) throws Exception {
        SettingStore store = SettingTestSupport.store(dataDir);

        SettingSnapshot snapshot = store.read();

        assertThat(snapshot.present()).isFalse();
        assertThat(snapshot.parsed()).isNull();
        assertThat(snapshot.revision()).isNull();
        assertThat(snapshot.warnings()).isEmpty();
        assertThat(Files.exists(store.path())).isFalse();
        assertThat(store.path().getFileName().toString()).isEqualTo("setting.json");
        assertThat(store.path().getParent().getFileName().toString()).isEqualTo("config");
    }

    @Test
    @DisplayName("原子写之后可以读回，内容与写入一致且 revision 为 64 位 hex")
    void atomicWriteThenRead(@TempDir Path dataDir) throws Exception {
        SettingStore store = SettingTestSupport.store(dataDir);

        store.writeAtomic(SettingTestSupport.validConfig("p1", "qwen2.5:7b", SettingTestSupport.SAMPLE_KEY));
        SettingSnapshot snapshot = store.read();

        assertThat(snapshot.present()).isTrue();
        assertThat(snapshot.parsed().path("llm").path("defaultProfileId").asText()).isEqualTo("p1");
        assertThat(snapshot.parsed().path("llm").path("profiles").get(0).path("model").asText())
                .isEqualTo("qwen2.5:7b");
        // 明文密钥确实落在文件里（这是文件驱动的设计要求），但不是本类日志/异常的内容
        assertThat(snapshot.parsed().path("llm").path("profiles").get(0).path("apiKey").asText())
                .isEqualTo(SettingTestSupport.SAMPLE_KEY);
        assertThat(snapshot.revision()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(snapshot.loadedAt()).isNotNull();
    }

    @Test
    @DisplayName("revision 只随文件字节变化：内容相同则一致，内容不同则不同")
    void revisionFollowsContent(@TempDir Path dataDir) throws Exception {
        SettingStore store = SettingTestSupport.store(dataDir);

        store.writeAtomic(SettingTestSupport.validConfig("p1", "qwen2.5:7b", null));
        String first = store.read().revision();
        // 同样的内容重写一次：字节一致，revision 必须一致
        store.writeAtomic(SettingTestSupport.validConfig("p1", "qwen2.5:7b", null));
        assertThat(store.read().revision()).isEqualTo(first);

        store.writeAtomic(SettingTestSupport.validConfig("p1", "qwen2.5:14b", null));
        String second = store.read().revision();
        assertThat(second).isNotEqualTo(first);

        // 直接改写文件（模拟外部编辑器）同样改变 revision
        Files.writeString(store.path(), "{\"schemaVersion\":1,\"llm\":{\"profiles\":[]}}");
        assertThat(store.read().revision()).isNotEqualTo(second);
    }

    @Test
    @DisplayName("原子写不残留临时文件，且磁盘上不会出现半截 JSON")
    void noTemporaryFilesLeftBehind(@TempDir Path dataDir) throws Exception {
        SettingStore store = SettingTestSupport.store(dataDir);

        store.writeAtomic(SettingTestSupport.validConfig("p1", "qwen2.5:7b", null));
        store.writeAtomic(SettingTestSupport.validConfig("p2", "qwen2.5:14b", SettingTestSupport.SAMPLE_KEY));

        assertThat(SettingTestSupport.tempFiles(store.path().getParent())).isEmpty();
        JsonNode parsed = SettingTestSupport.parse(store.path());
        assertThat(parsed.path("llm").path("profiles").size()).isEqualTo(1);
        assertThat(parsed.path("llm").path("profiles").get(0).path("id").asText()).isEqualTo("p2");
        // 目录里应当只有 setting.json 这一个文件
        try (var stream = Files.list(store.path().getParent())) {
            assertThat(stream.map(path -> path.getFileName().toString()).toList())
                    .containsExactly("setting.json");
        }
    }

    @Test
    @DisplayName("0600 权限收紧在 Windows 上尽力而为：不抛异常，文件照样可读")
    void permissionsAreBestEffort(@TempDir Path dataDir) throws Exception {
        SettingStore store = SettingTestSupport.store(dataDir);

        assertThatCode(() -> store.writeAtomic(
                SettingTestSupport.validConfig("p1", "qwen2.5:7b", SettingTestSupport.SAMPLE_KEY)))
                .doesNotThrowAnyException();
        assertThat(store.read().present()).isTrue();
        assertThat(SettingStore.isTemporary("setting.json.tmp-123")).isTrue();
        assertThat(SettingStore.isTemporary("setting.json")).isFalse();
        assertThat(SettingStore.isSettingFile("setting.json")).isTrue();
    }

    @Test
    @DisplayName("JSON 语法错误抛出可读异常，且消息里不含原文（可能含密钥）")
    void syntaxErrorKeepsSecretOutOfMessage(@TempDir Path dataDir) throws Exception {
        SettingStore store = SettingTestSupport.store(dataDir);
        SettingTestSupport.writeRaw(store.path(), "{\"llm\":{\"profiles\":[{\"apiKey\":\""
                + SettingTestSupport.SAMPLE_KEY + "\",}]}");

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> store.read())
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("setting.json 语法错误")
                .hasMessageNotContaining(SettingTestSupport.SAMPLE_KEY);
    }
}
