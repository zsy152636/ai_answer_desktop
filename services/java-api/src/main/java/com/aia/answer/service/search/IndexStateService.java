package com.aia.answer.service.search;

import com.aia.answer.db.SqliteCapabilityRegistry;
import com.aia.answer.domain.DocumentDtos;
import com.aia.answer.repository.DocumentRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 索引版本状态（开发文档 §524）。
 *
 * <p>版本化重建：新版本全部分块与索引构建完成并通过完整性校验后才切换 {@code active_version}；
 * 失败时保留旧版本继续对外服务，因此检索永远读得到一个一致的分块集合。
 */
@Service
public class IndexStateService {

    /** 索引名固定，与 `index_state.index_name` 对应。 */
    public static final String INDEX_NAME = "document_fts";

    /** 初始索引版本；导入的第一批资料就落在版本 1 上。 */
    public static final long INITIAL_VERSION = 1L;

    private final DocumentRepository repository;
    private final SqliteCapabilityRegistry capabilities;

    public IndexStateService(DocumentRepository repository, SqliteCapabilityRegistry capabilities) {
        this.repository = repository;
        this.capabilities = capabilities;
    }

    public long ensureInitialized() {
        Optional<DocumentRepository.IndexStateRow> state = repository.findIndexState(INDEX_NAME);
        if (state.isPresent()) {
            return state.get().activeVersion();
        }
        repository.insertIndexState(INDEX_NAME, INITIAL_VERSION, nowUtc());
        return INITIAL_VERSION;
    }

    public long activeVersion() {
        return ensureInitialized();
    }

    public Long buildingVersion() {
        return repository.findIndexState(INDEX_NAME)
                .map(DocumentRepository.IndexStateRow::buildingVersion)
                .orElse(null);
    }

    /** 标记开始构建新版本（此时 active 不变，读路径不受影响）。 */
    public void beginBuild(long buildingVersion) {
        long active = ensureInitialized();
        repository.updateIndexState(INDEX_NAME, active, buildingVersion, nowUtc());
    }

    /** 切换 active 到新版本并清除构建标记。 */
    public void activate(long newVersion) {
        repository.updateIndexState(INDEX_NAME, newVersion, null, nowUtc());
    }

    /** 构建失败：保留旧 active，清除构建标记。 */
    public void abortBuild() {
        long active = ensureInitialized();
        repository.updateIndexState(INDEX_NAME, active, null, nowUtc());
    }

    public DocumentDtos.IndexState state() {
        long active = ensureInitialized();
        Optional<DocumentRepository.IndexStateRow> row = repository.findIndexState(INDEX_NAME);
        return new DocumentDtos.IndexState(INDEX_NAME, active,
                row.map(DocumentRepository.IndexStateRow::buildingVersion).orElse(null),
                row.map(DocumentRepository.IndexStateRow::updatedAt).orElse(nowUtc()),
                capabilities.fts5Trigram(),
                repository.countDocuments(null, null, null),
                repository.countChunks(active));
    }

    private static String nowUtc() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
