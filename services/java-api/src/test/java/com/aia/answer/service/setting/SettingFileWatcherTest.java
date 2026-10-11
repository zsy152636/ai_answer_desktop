package com.aia.answer.service.setting;

import com.aia.answer.common.Json;
import com.aia.answer.config.AppProperties;
import com.aia.answer.service.setting.watcher.SettingFileWatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置热加载测试：真实 WatchService + 真实临时文件（不发网络请求）。
 *
 * <p>用例只断言「一定时间内缓存被刷新 / 一定时间内没有被刷新」，不做精确时序断言，
 * 避免在负载高的机器上抖动。超时预算远大于 300ms 去抖窗口。
 */
class SettingFileWatcherTest {

    /** 等待上限：远大于去抖 300ms，给文件系统事件留足余量。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    @DisplayName("setting.json 被修改后，ConfigService 缓存被刷新")
    void reloadsAfterFileChanged(@TempDir Path dataDir) throws Exception {
        Fixture fixture = new Fixture(dataDir);
        try {
            fixture.store.writeAtomic(SettingTestSupport.validConfig("p1", "qwen2.5:7b", null));
            fixture.service.reload();
            String firstRevision = fixture.service.revision();
            assertThat(firstRevision).isNotNull();
            fixture.start();

            fixture.store.writeAtomic(SettingTestSupport.validConfig("p2", "qwen2.5:14b", null));

            assertThat(awaitUntil(() -> "p2".equals(fixture.service.defaultProfileId()))).isTrue();
            assertThat(fixture.service.revision()).isNotEqualTo(firstRevision);
            assertThat(fixture.service.profile("p2")).isPresent();
            assertThat(fixture.service.profile("p1")).isEmpty();
        } finally {
            fixture.close();
        }
    }

    @Test
    @DisplayName("非法内容被忽略：缓存保持旧配置，并记录 warning")
    void ignoresInvalidContent(@TempDir Path dataDir) throws Exception {
        Fixture fixture = new Fixture(dataDir);
        try {
            fixture.store.writeAtomic(SettingTestSupport.validConfig("p1", "qwen2.5:7b", null));
            fixture.service.reload();
            String goodRevision = fixture.service.revision();
            fixture.start();

            // 语法非法
            Files.writeString(fixture.store.path(), "{ not json ");
            assertThat(awaitUntil(() -> fixture.service.warnings().stream()
                    .anyMatch(warning -> warning.contains("语法错误")))).isTrue();
            assertThat(fixture.service.revision()).isEqualTo(goodRevision);
            assertThat(fixture.service.defaultProfileId()).isEqualTo("p1");

            // Schema 违规
            ObjectNode bad = SettingTestSupport.validConfig("p1", "qwen2.5:7b", null);
            ((ObjectNode) bad.path("llm").path("profiles").get(0)).put("provider", "anthropic");
            fixture.store.writeAtomic(bad);
            assertThat(awaitUntil(() -> fixture.service.warnings().stream()
                    .anyMatch(warning -> warning.contains("provider")))).isTrue();
            assertThat(fixture.service.revision()).isEqualTo(goodRevision);
            assertThat(fixture.service.profile("p1").orElseThrow().path("provider").asText())
                    .isEqualTo("ollama");
        } finally {
            fixture.close();
        }
    }

    @Test
    @DisplayName("start 与 stop 可重复调用，不抛异常（watch 不可用时也必须能降级）")
    void startAndStopAreIdempotent(@TempDir Path dataDir) throws Exception {
        Fixture fixture = new Fixture(dataDir);
        try {
            fixture.start();
            fixture.start();
            fixture.close();
            fixture.close();
            assertThat(fixture.service.snapshot().present()).isFalse();
        } finally {
            fixture.close();
        }
    }

    /** 轮询等待条件成立。 */
    private static boolean awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    /** 真实 store/service/watcher 装配。 */
    private static final class Fixture implements AutoCloseable {

        private final SettingStore store;
        private final ConfigService service;
        private final SettingFileWatcher watcher;

        private Fixture(Path dataDir) {
            AppProperties props = SettingTestSupport.properties(dataDir);
            Json json = SettingTestSupport.json();
            this.store = new SettingStore(props, json);
            LegacySecretExporter exporter = reference -> Optional.empty();
            LegacyLlmConfigMigrator migrator = new LegacyLlmConfigMigrator(null, null, store, json, exporter);
            this.service = new ConfigService(store, new SettingValidator(new ObjectMapper()), provider(migrator));
            this.watcher = new SettingFileWatcher(service, store);
        }

        private void start() {
            watcher.start();
        }

        @Override
        public void close() {
            watcher.stop();
        }
    }

    /** 极简 ObjectProvider：把迁移器交给 ConfigService（本用例不涉及旧表迁移）。 */
    private static <T> ObjectProvider<T> provider(T instance) {
        AtomicReference<T> holder = new AtomicReference<>(instance);
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return holder.get();
            }

            @Override
            public T getIfAvailable() {
                return holder.get();
            }

            @Override
            public T getIfUnique() {
                return holder.get();
            }

            @Override
            public T getObject() {
                return holder.get();
            }
        };
    }
}
