package com.aia.answer.service.search;

import com.aia.answer.common.ApiException;
import com.aia.answer.db.SqliteCapabilityRegistry;
import com.aia.answer.domain.DocumentDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索入口：在 FTS5 与关键词两条实现之间选择，并给出可解释的模式标识（契约 §11.4）。
 *
 * <p>选择顺序：
 * <ol>
 *   <li>能力可用（FTS5 + trigram）且查询每个词都不短于 3 字符 → 走 FTS5（trigram 的硬下限）；</li>
 *   <li>FTS5 返回空、抛错（例如迁移被跳过导致 `document_fts` 不存在）、或上面的前置条件不满足 →
 *       走关键词 LIKE 检索；</li>
 *   <li>响应里的 {@code mode} 如实反映**本次实际生效**的实现，便于界面解释「为什么是关键词降级」。</li>
 * </ol>
 */
@Service
public class DocumentSearchService {

    private static final Logger log = LoggerFactory.getLogger(DocumentSearchService.class);
    private static final int MAX_QUERY_CHARS = 200;
    private static final int MAX_LIMIT = 50;

    private final DocumentSearchPort fts5;
    private final DocumentSearchPort keyword;
    private final IndexStateService indexState;
    private final SqliteCapabilityRegistry capabilities;

    private volatile boolean ftsFailureLogged = false;

    public DocumentSearchService(Fts5DocumentSearchPort fts5, KeywordDocumentSearchPort keyword,
                                IndexStateService indexState, SqliteCapabilityRegistry capabilities) {
        this.fts5 = fts5;
        this.keyword = keyword;
        this.indexState = indexState;
        this.capabilities = capabilities;
    }

    public DocumentDtos.SearchResponse search(String rawQuery, Integer limitParam, String documentId) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            throw ApiException.validation("检索关键词不能为空", "q");
        }
        if (query.codePointCount(0, query.length()) > MAX_QUERY_CHARS) {
            throw ApiException.validation("检索关键词过长（最多 " + MAX_QUERY_CHARS + " 字符）", "q");
        }
        int limit = limitParam == null ? 10 : limitParam;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.validation("limit 必须在 1-" + MAX_LIMIT + " 之间", "limit");
        }
        long started = System.nanoTime();
        long version = indexState.activeVersion();

        List<DocumentDtos.SearchHit> hits = List.of();
        String mode = keyword.mode();
        if (capabilities.fts5Trigram() && Fts5DocumentSearchPort.usable(query)) {
            hits = searchFtsQuietly(query, limit, documentId, version);
            if (!hits.isEmpty()) {
                mode = fts5.mode();
            }
        }
        if (hits.isEmpty()) {
            hits = keyword.search(query, limit, documentId, version);
            mode = keyword.mode();
        }
        long tookMs = (System.nanoTime() - started) / 1_000_000;
        return new DocumentDtos.SearchResponse(query, mode, tookMs, hits.size(), hits);
    }

    /** FTS5 查询失败（表缺失/语句异常）时降级而不是把 500 抛给用户；降级事实写日志一次。 */
    private List<DocumentDtos.SearchHit> searchFtsQuietly(String query, int limit, String documentId,
                                                          long indexVersion) {
        try {
            return fts5.search(query, limit, documentId, indexVersion);
        } catch (RuntimeException e) {
            if (!ftsFailureLogged) {
                ftsFailureLogged = true;
                log.warn("FTS5 检索不可用，已降级为关键词检索：{}", e.getMessage());
            }
            return List.of();
        }
    }

    /** 仅用于「索引完整性校验」：确认新版本在检索层面确实可命中。 */
    public boolean canRetrieve(long indexVersion, String probeQuery) {
        if (!capabilities.fts5Trigram() || !Fts5DocumentSearchPort.usable(probeQuery)) {
            return true;
        }
        try {
            return !fts5.search(probeQuery, 1, null, indexVersion).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
