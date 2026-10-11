package com.aia.answer.domain;

import java.util.List;

/**
 * 资料库与本地检索 DTO（契约 api-contract.md §11）。
 *
 * <p>字段名与契约逐字段对齐（JSON camelCase）；检索命中必须带 `documentId`/`chunkId`/`pageNo`，
 * 这是「检索结果可追溯来源」的落点（开发文档 §525、阶段 6 完成定义）。
 */
public final class DocumentDtos {

    private DocumentDtos() {
    }

    /** `GET /api/v1/documents` 查询参数。 */
    public record Query(Integer page, Integer size, String q, String status, String sha256) {
    }

    public record DocumentSummary(String id, String title, String status, String sourceKind,
                                  String originalFileName, long sizeBytes, String sha256,
                                  int chunkCount, int charCount, Integer indexVersion,
                                  String parserVersion, String errorCode, String errorMessage,
                                  String createdAt, String updatedAt) {
    }

    public record ChunkView(String id, String documentId, int chunkIndex, Integer pageNo, String content,
                            String contentHash, String parserVersion, int indexVersion, String createdAt) {
    }

    public record SearchHit(String documentId, String chunkId, String title, Integer pageNo, int chunkIndex,
                            String snippet, double score, String matchMode) {
    }

    public record SearchResponse(String query, String mode, long tookMs, long total, List<SearchHit> results) {
    }

    public record IndexState(String indexName, long activeVersion, Long buildingVersion, String updatedAt,
                             boolean fts5Available, long documentCount, long chunkCount) {
    }

    /** `POST /api/v1/documents/import` 请求体；`onDuplicate` ∈ reject | reuse | new。 */
    public record ImportRequest(String sourcePath, String sha256, String title, String onDuplicate) {
    }

    public record DocumentDeleteResponse(boolean deleted, boolean fileRemoved) {
    }

    public record IndexRebuildResponse(IndexState indexState, int chunks, List<String> failures) {
    }

    public record FileConsistencyReport(List<OrphanFile> orphanFiles, List<MissingFile> missingFiles,
                                        int tempFiles, int retentionHours) {
    }

    public record OrphanFile(String fileId, String relativePath, long sizeBytes, String modifiedAt) {
    }

    public record MissingFile(String documentId, String title, String relativePath) {
    }

    public record FileCleanupResponse(int removedFiles, int markedFailed, boolean dryRun) {
    }

    /** AI 检索依据：Java 侧保留完整信息，便于把模型给出的 refId 映射回真实分块。 */
    public record AiReference(String refId, String documentId, String chunkId, String title, Integer pageNo,
                              String content) {
    }
}
