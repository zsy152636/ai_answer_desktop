package com.aia.answer.service.setting.watcher;

import com.aia.answer.service.setting.ConfigService;
import com.aia.answer.service.setting.SettingStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

/**
 * setting.json 热加载（开发文档「文件驱动 + 热加载」）。
 *
 * <p>职责与边界：
 * <ul>
 *   <li>只监听 {@code <dataDir>/config} 目录，只关心 {@code setting.json} 的 CREATE / MODIFY / DELETE；</li>
 *   <li>忽略原子写的临时文件（{@code setting.json.tmp-*}），否则每次保存都会多触发一轮重载；</li>
 *   <li><b>去抖 300ms</b>：编辑器保存常产生多个事件，攒够静默期才回调一次
 *       {@link ConfigService#reload()}（该方法自身保证非法内容保留 last-good）；</li>
 *   <li>监视线程是 daemon，{@code @PreDestroy} 里关停；</li>
 *   <li>watch 起不来（不支持 WatchService 的文件系统、目录权限不足等）只记 WARN 并降级成
 *       「不热加载」，绝不让应用启动失败。</li>
 * </ul>
 *
 * <p>安全边界：本类只记录文件路径与重载结果，绝不读取/打印文件内容
 * （内容可能含 profile 的明文 apiKey）。
 */
@Component
public class SettingFileWatcher {

    private static final Logger log = LoggerFactory.getLogger(SettingFileWatcher.class);

    /** 去抖窗口：最后一个事件之后静默这么久才真正重载。 */
    static final long DEBOUNCE_MILLIS = 300L;

    /** 单轮等待时长：决定去抖精度与关停响应速度。 */
    private static final long POLL_MILLIS = 100L;

    private final ConfigService configService;
    private final SettingStore store;

    private volatile WatchService watchService;
    private volatile Thread worker;

    public SettingFileWatcher(ConfigService configService, SettingStore store) {
        this.configService = configService;
        this.store = store;
    }

    /** 启动监听。失败绝不抛出，只降级为「不热加载」。 */
    @PostConstruct
    public void start() {
        if (worker != null) {
            return;
        }
        Path dir = store.path().getParent();
        if (dir == null) {
            log.warn("配置目录无法解析，热加载已禁用");
            return;
        }
        try {
            Files.createDirectories(dir);
            WatchService service = FileSystems.getDefault().newWatchService();
            dir.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            this.watchService = service;
            Thread thread = new Thread(() -> loop(service), "setting-file-watcher");
            thread.setDaemon(true);
            this.worker = thread;
            thread.start();
            log.info("配置热加载已启用：监听 {}（去抖 {}ms）", dir, DEBOUNCE_MILLIS);
        } catch (IOException | RuntimeException e) {
            // 不支持 WatchService 的文件系统（或目录不可注册）：降级为不热加载
            log.warn("无法启用配置热加载（降级为不热加载，不影响启动）：{}", e.getMessage());
            closeQuietly();
        }
    }

    /** 事件循环：攒够去抖静默期后重载一次。 */
    private void loop(WatchService service) {
        boolean pending = false;
        long silentSince = 0L;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WatchKey key = service.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (key != null) {
                    boolean relevant = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Object context = event.context();
                        String name = context == null ? null : context.toString();
                        if (SettingStore.isTemporary(name)) {
                            continue;
                        }
                        if (SettingStore.isSettingFile(name)) {
                            relevant = true;
                        }
                    }
                    key.reset();
                    if (relevant) {
                        pending = true;
                        silentSince = System.nanoTime();
                    }
                }
                if (pending && System.nanoTime() - silentSince >= TimeUnit.MILLISECONDS.toNanos(DEBOUNCE_MILLIS)) {
                    pending = false;
                    configService.reload();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ClosedWatchServiceException e) {
                return;
            } catch (RuntimeException e) {
                // 单轮异常不能让热加载线程静默死掉
                log.warn("配置热加载本轮处理失败（已忽略，将继续监听）：{}", e.getMessage());
            }
        }
    }

    /** 关停监听线程与 WatchService；重复调用安全。 */
    @PreDestroy
    public void stop() {
        Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
        }
        closeQuietly();
    }

    private void closeQuietly() {
        WatchService service = watchService;
        watchService = null;
        if (service != null) {
            try {
                service.close();
            } catch (IOException e) {
                log.debug("关闭配置监听失败（已忽略）：{}", e.getMessage());
            }
        }
    }
}
