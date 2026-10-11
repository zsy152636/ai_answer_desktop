package com.aia.answer.service.setting;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.ErrorCode;
import com.aia.answer.config.AppProperties;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置缓存与保存语义测试：核心是「非法配置保留 last-good」与「保存不产生半截 JSON」。
 *
 * <p>用真实临时目录 + 真实文件，不启动 Spring 容器：这些语义只与文件读写和锁有关。
 * 迁移器用真实实现但指向空库（{@link LegacyLlmConfigMigrator} 在无旧数据时不写文件）。
 */
class ConfigServiceTest {

    @Test
    @DisplayName("reload 遇到非法文件时保留上一份有效配置并记录 warning")
    void reloadKeepsLastGoodOnInvalidFile(@TempDir Path dataDir) throws Exception {
        AppProperties props = SettingTestSupport.properties(dataDir);
        SettingStore store = new SettingStore(props, SettingTestSupport.json());
        ConfigService service = service(store);
        store.writeAtomic(SettingTestSupport.validConfig("p1", "qwen2.5:7b", SettingTestSupport.SAMPLE_KEY));
        service.reload();
        String goodRevision = service.revision();
        assertThat(goodRevision).isNotNull();
        assertThat(service.profile("p1")).isPresent();

        // 1) 语法非法
        Files.writeString(store.path(), "{ this is not json ");
        service.reload();
        assertThat(service.revision()).isEqualTo(goodRevision);
        assertThat(service.profile("p1")).isPresent();
        assertThat(service.warnings()).anySatisfy(warning -> assertThat(warning).contains("语法错误"));

        // 2) Schema 违规（provider 不在白名单）
        ObjectNode bad = SettingTestSupport.validConfig("p1", "qwen2.5:7b", null);
        ((ObjectNode) bad.path("llm").path("profiles").get(0)).put("provider", "anthropic");
        store.writeAtomic(bad);
        service.reload();
        assertThat(service.revision()).isEqualTo(goodRevision);
        assertThat(service.profile("p1")).isPresent();

        // 3) 跨字段违规（defaultProfileId 悬空）
        ObjectNode dangling = SettingTestSupport.validConfig("p1", "qwen2.5:7b", null);
        ((ObjectNode) dangling.path("llm")).put("defaultProfileId", "p-missing");
        store.writeAtomic(dangling);
        service.reload();
        assertThat(service.revision()).isEqualTo(goodRevision);
        assertThat(service.defaultProfileId()).isEqualTo("p1");
        assertThat(service.warnings()).anySatisfy(warning ->
                assertThat(warning).contains("defaultProfileId 未指向任何档案"));

        // 4) 合法文件立刻替换缓存
        store.writeAtomic(SettingTestSupport.validConfig("p2", "qwen2.5:14b", null));
        service.reload();
        assertThat(service.revision()).isNotEqualTo(goodRevision);
        assertThat(service.defaultProfileId()).isEqualTo("p2");
        assertThat(service.profiles()).hasSize(1);
    }

    @Test
    @DisplayName("文件删除后配置回到空快照（这是合法状态，不是错误）")
    void reloadToEmptyWhenFileRemoved(@TempDir Path dataDir) throws Exception {
        SettingStore store = SettingTestSupport.store(dataDir);
        ConfigService service = service(store);
        store.writeAtomic(SettingTestSupport.validConfig("p1", "qwen2.5:7b", null));
        service.reload();
        assertThat(service.revision()).isNotNull();

        Files.delete(store.path());
        service.reload();

        assertThat(service.snapshot().present()).isFalse();
        assertThat(service.revision()).isNull();
        assertThat(service.profiles()).isEmpty();
        assertThat(service.defaultProfileId()).isNull();
    }

    @Test
    @DisplayName("save 后 revision 变化、内容正确，且 defaultProfileId 写入 llm 块")
    void saveUpdatesContentAndRevision(@TempDir Path dataDir) throws Exception {
        SettingStore store = SettingTestSupport.store(dataDir);
        ConfigService service = service(store);
        assertThat(service.revision()).isNull();

        service.save(root -> {
            SettingTestSupport.addProfile(root, "p1");
            ((ObjectNode) root.path("llm")).put("defaultProfileId", "p1");
        });

        String firstRevision = service.revision();
        assertThat(firstRevision).hasSize(64);
        assertThat(service.defaultProfileId()).isEqualTo("p1");
        assertThat(service.profile("p1")).isPresent();
        assertThat(service.profile("p1").orElseThrow().path("provider").asText())
                .isEqualTo("openai-compatible");
        assertThat(SettingTestSupport.parse(store.path()).path("llm").path("defaultProfileId").asText())
                .isEqualTo("p1");

        service.save(root -> ((ObjectNode) root.path("llm").path("profiles").get(0)).put("model", "gpt-4o"));
        assertThat(service.revision()).isNotEqualTo(firstRevision);
        assertThat(service.profile("p1").orElseThrow().path("model").asText()).isEqualTo("gpt-4o");
    }

    @Test
    @DisplayName("save 校验不通过时抛异常且磁盘与缓存都不变")
    void saveRejectsInvalidContent(@TempDir Path dataDir) throws Exception {
        SettingStore store = SettingTestSupport.store(dataDir);
        ConfigService service = service(store);
        service.save(root -> {
            SettingTestSupport.addProfile(root, "p1");
            ((ObjectNode) root.path("llm")).put("defaultProfileId", "p1");
        });
        String revision = service.revision();
        String onDisk = Files.readString(store.path());

        assertThatThrownBy(() -> service.save(root -> ((ObjectNode) root.path("llm").path("profiles").get(0))
                .put("provider", "unknown-vendor")))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        assertThat(service.revision()).isEqualTo(revision);
        assertThat(Files.readString(store.path())).isEqualTo(onDisk);
    }

    @Test
    @DisplayName("save 的跨字段校验：defaultProfileId 指向不存在的 id 被拒")
    void saveRejectsDanglingDefaultProfile(@TempDir Path dataDir) throws Exception {
        SettingStore store = SettingTestSupport.store(dataDir);
        ConfigService service = service(store);

        assertThatThrownBy(() -> service.save(root -> {
            SettingTestSupport.addProfile(root, "p1");
            ((ObjectNode) root.path("llm")).put("defaultProfileId", "p-missing");
        }))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("defaultProfileId 未指向任何档案");

        assertThat(Files.exists(store.path())).isFalse();
        assertThat(service.revision()).isNull();
    }

    @Test
    @DisplayName("并发保存不产生半截 JSON：所有档案都在，文件始终可解析")
    void concurrentSavesNeverProducePartialJson(@TempDir Path dataDir) throws Exception {
        SettingStore store = SettingTestSupport.store(dataDir);
        ConfigService service = service(store);
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            String id = "p" + i;
            futures.add(pool.submit(() -> {
                start.await();
                service.save(root -> SettingTestSupport.addProfile(root, id));
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // 每个线程各自追加一个档案：锁内的「读 → 改 → 写」保证不会互相覆盖
        assertThat(service.profiles()).hasSize(writers);
        assertThat(service.profiles().stream().map(profile -> profile.path("id").asText()).toList())
                .containsExactlyInAnyOrder("p0", "p1", "p2", "p3", "p4", "p5", "p6", "p7");
        assertThat(SettingTestSupport.parse(store.path()).path("llm").path("profiles").size())
                .isEqualTo(writers);
        assertThat(SettingTestSupport.tempFiles(store.path().getParent())).isEmpty();
    }

    @Test
    @DisplayName("保存的配置里明文密钥原样写入磁盘，且日志/warning 中不出现密钥")
    void saveKeepsSecretOnDiskOnly(@TempDir Path dataDir) throws Exception {
        SettingStore store = SettingTestSupport.store(dataDir);
        ConfigService service = service(store);

        service.save(root -> {
            SettingTestSupport.addProfile(root, "p1");
            ((ObjectNode) root.path("llm").path("profiles").get(0)).put("apiKey", SettingTestSupport.SAMPLE_KEY);
            ((ObjectNode) root.path("llm")).put("defaultProfileId", "p1");
        });

        assertThat(service.profile("p1").orElseThrow().path("apiKey").asText())
                .isEqualTo(SettingTestSupport.SAMPLE_KEY);
        assertThat(service.warnings()).allSatisfy(warning ->
                assertThat(warning).doesNotContain(SettingTestSupport.SAMPLE_KEY));
    }

    // ------------------------------------------------------------ 装配

    private ConfigService service(SettingStore store) {
        LegacyLlmConfigMigrator migrator = new LegacyLlmConfigMigrator(null, null, store,
                SettingTestSupport.json(), reference -> java.util.Optional.empty());
        return new ConfigService(store, SettingTestSupport.validator(), provider(migrator));
    }

    /** 极简 ObjectProvider：只为把迁移器交给 ConfigService。 */
    private static <T> ObjectProvider<T> provider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }
}
