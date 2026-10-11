package com.aia.answer.db;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 能力探测结果的进程内缓存。
 *
 * <p>能力（FTS5 + trigram）在一次进程生命周期内不会变化，但探测本身要建一张临时虚拟表，
 * 因此不能每次检索都做。首次使用时探测一次并缓存；{@link #refresh()} 供测试或环境变化时手动失效。
 */
@Component
public class SqliteCapabilityRegistry {

    private final DataSource dataSource;
    private final AtomicReference<Set<String>> cached = new AtomicReference<>();

    public SqliteCapabilityRegistry(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Set<String> capabilities() {
        Set<String> current = cached.get();
        if (current != null) {
            return current;
        }
        Set<String> probed = SqliteCapabilities.probe(dataSource);
        cached.compareAndSet(null, probed);
        return cached.get();
    }

    /** 检索索引是否可用（FTS5 + trigram）；不可用时业务层走关键词降级。 */
    public boolean fts5Trigram() {
        return capabilities().contains(SqliteCapabilities.FTS5_TRIGRAM);
    }

    public void refresh() {
        cached.set(null);
    }
}
