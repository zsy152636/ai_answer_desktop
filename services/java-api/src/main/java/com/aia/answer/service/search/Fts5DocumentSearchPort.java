package com.aia.answer.service.search;

import com.aia.answer.domain.DocumentDtos;
import com.aia.answer.repository.DocumentRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * FTS5 检索实现（trigram 分词）。
 *
 * <p>安全要点：用户输入**从不**直接拼进 MATCH 表达式。查询按空白切词后逐词加双引号成为短语，
 * 词内双引号按 FTS5 规则转义为两个双引号，词间用 {@code AND} 连接；因此 {@code *}、{@code -}、
 * {@code NOT}、{@code OR}、引号等 FTS5 语法字符一律退化为普通文本，不存在语法注入。
 *
 * <p>排序：{@code bm25()} 越小越相关，对外返回 {@code score = -bm25}（越大越相关）；
 * 相同的 bm25 由 {@code document_id, chunk_index} 兜底，保证结果稳定可复现。
 */
@Component
public class Fts5DocumentSearchPort implements DocumentSearchPort {

    /** trigram 分词器无法命中长度不足 3 的片段，低于此长度的查询直接走关键词降级。 */
    public static final int MIN_TERM_CHARS = 3;

    private final DocumentRepository repository;

    public Fts5DocumentSearchPort(DocumentRepository repository) {
        this.repository = repository;
    }

    @Override
    public String mode() {
        return "fts5";
    }

    /** 该查询能否用 FTS5 处理：至少一个词，且每个词都不短于 trigram 的下限。 */
    public static boolean usable(String query) {
        List<String> terms = terms(query);
        if (terms.isEmpty()) {
            return false;
        }
        for (String term : terms) {
            if (term.codePointCount(0, term.length()) < MIN_TERM_CHARS) {
                return false;
            }
        }
        return true;
    }

    /** 构造 FTS5 MATCH 表达式（短语 + AND），供测试直接断言转义结果。 */
    public static String toMatchExpression(String query) {
        List<String> quoted = new ArrayList<>();
        for (String term : terms(query)) {
            quoted.add("\"" + term.replace("\"", "\"\"") + "\"");
        }
        return String.join(" AND ", quoted);
    }

    private static List<String> terms(String query) {
        List<String> terms = new ArrayList<>();
        if (query == null) {
            return terms;
        }
        for (String raw : query.trim().split("\\s+")) {
            String cleaned = raw.replaceAll("[\\p{Cntrl}]", "").trim();
            if (!cleaned.isEmpty()) {
                terms.add(cleaned);
            }
        }
        return terms;
    }

    @Override
    public List<DocumentDtos.SearchHit> search(String query, int limit, String documentId, long indexVersion) {
        if (!usable(query)) {
            return List.of();
        }
        String matchExpression = toMatchExpression(query);
        List<DocumentRepository.SearchRow> rows =
                repository.searchFts(matchExpression, indexVersion, documentId, limit);
        List<DocumentDtos.SearchHit> hits = new ArrayList<>(rows.size());
        for (DocumentRepository.SearchRow row : rows) {
            hits.add(new DocumentDtos.SearchHit(row.documentId(), row.chunkId(), row.title(), row.pageNo(),
                    row.chunkIndex(), row.snippet() == null ? "" : row.snippet(), -row.bm25(), mode()));
        }
        return hits;
    }
}
