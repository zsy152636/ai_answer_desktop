package com.aia.answer.service.search;

import com.aia.answer.domain.DocumentDtos;
import com.aia.answer.repository.DocumentRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 关键词检索实现（FTS5 不可用、查询过短或 FTS5 无命中时的降级路径）。
 *
 * <p>可解释排序：先按「首次命中位置越靠前越相关」，再看命中次数；分数公式固定且与数据无关，
 * 因此同一份数据上的排序结果稳定可复现：
 * <pre>score = 1000 / (1 + 首次命中位置) + min(50, 命中次数 × 5)</pre>
 * 片段在 service 层按固定窗口截取，命中词用 {@code [[ ]]} 标记。
 */
@Component
public class KeywordDocumentSearchPort implements DocumentSearchPort {

    private static final int SNIPPET_WINDOW = 160;
    private static final int SNIPPET_LEAD = 40;

    private final DocumentRepository repository;

    public KeywordDocumentSearchPort(DocumentRepository repository) {
        this.repository = repository;
    }

    @Override
    public String mode() {
        return "keyword";
    }

    @Override
    public List<DocumentDtos.SearchHit> search(String query, int limit, String documentId, long indexVersion) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        String pattern = "%" + DocumentRepository.escapeLike(trimmed) + "%";
        List<DocumentRepository.SearchRow> rows =
                repository.searchKeyword(pattern, indexVersion, documentId, limit);
        List<DocumentDtos.SearchHit> hits = new ArrayList<>(rows.size());
        for (DocumentRepository.SearchRow row : rows) {
            String content = row.content() == null ? "" : row.content();
            String lowerContent = content.toLowerCase(Locale.ROOT);
            String lowerQuery = trimmed.toLowerCase(Locale.ROOT);
            int first = lowerContent.indexOf(lowerQuery);
            int occurrences = countOccurrences(lowerContent, lowerQuery);
            double score = 1000.0 / (1.0 + Math.max(0, first)) + Math.min(50.0, occurrences * 5.0);
            hits.add(new DocumentDtos.SearchHit(row.documentId(), row.chunkId(), row.title(), row.pageNo(),
                    row.chunkIndex(), snippet(content, first, trimmed.length()), score, mode()));
        }
        return hits;
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    /** 以命中位置为中心截取固定窗口，命中词用 [[ ]] 标记（客户端按纯文本渲染）。 */
    static String snippet(String content, int firstIndex, int queryLength) {
        if (content.isEmpty()) {
            return "";
        }
        int start = firstIndex < 0 ? 0 : Math.max(0, firstIndex - SNIPPET_LEAD);
        int end = Math.min(content.length(), start + SNIPPET_WINDOW);
        String window = content.substring(start, end);
        String prefix = start > 0 ? "…" : "";
        String suffix = end < content.length() ? "…" : "";
        if (firstIndex < 0 || queryLength <= 0) {
            return prefix + window + suffix;
        }
        int localStart = firstIndex - start;
        int localEnd = Math.min(window.length(), localStart + queryLength);
        return prefix + window.substring(0, localStart) + "[[" + window.substring(localStart, localEnd) + "]]"
                + window.substring(localEnd) + suffix;
    }
}
