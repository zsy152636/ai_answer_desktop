package com.aia.answer.repository;

import com.aia.answer.domain.DocumentDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 资料库持久化：文件元数据、资料、分块、索引版本状态，以及 FTS5 虚拟表的读写。
 *
 * <p>边界：本类只做 SQL，不含任何业务规则（分块、降级、补偿都在 service 层）。
 * FTS5 虚拟表 `document_fts` 只在能力探测通过时存在，因此所有 FTS 相关方法都由调用方
 * 先判断能力；表不存在时会抛 SQLException/RuntimeException，调用方负责降级。
 */
@Repository
public class DocumentRepository {

    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------- 文件元数据

    public void insertFile(FileRow row) {
        jdbc.update("INSERT INTO imported_file(id, sha256, size_bytes, mime_type, original_name, relative_path, "
                        + "uploaded_at, parse_status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                row.id(), row.sha256(), row.sizeBytes(), row.mimeType(), row.originalName(), row.relativePath(),
                row.uploadedAt(), row.parseStatus(), row.createdAt(), row.updatedAt());
    }

    public Optional<FileRow> findFileBySha(String sha256) {
        List<FileRow> rows = jdbc.query("SELECT * FROM imported_file WHERE sha256 = ?", FILE_MAPPER, sha256);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<FileRow> findFileById(String id) {
        List<FileRow> rows = jdbc.query("SELECT * FROM imported_file WHERE id = ?", FILE_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<FileRow> listFiles() {
        return jdbc.query("SELECT * FROM imported_file ORDER BY created_at", FILE_MAPPER);
    }

    public int countDocumentsOfFile(String fileId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE file_id = ?", Integer.class, fileId);
        return count == null ? 0 : count;
    }

    public void updateFileParseStatus(String id, String parseStatus, String updatedAt) {
        jdbc.update("UPDATE imported_file SET parse_status = ?, updated_at = ? WHERE id = ?",
                parseStatus, updatedAt, id);
    }

    public void deleteFile(String id) {
        jdbc.update("DELETE FROM imported_file WHERE id = ?", id);
    }

    // ------------------------------------------------------------- 资料

    public void insertDocument(DocumentRow row) {
        jdbc.update("INSERT INTO document(id, title, file_id, status, source_kind, chunk_count, char_count, "
                        + "index_version, parser_version, error_code, error_message, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.id(), row.title(), row.fileId(), row.status(), row.sourceKind(), row.chunkCount(),
                row.charCount(), row.indexVersion(), row.parserVersion(), row.errorCode(), row.errorMessage(),
                row.createdAt(), row.updatedAt());
    }

    public void markDocumentIndexed(String id, int chunkCount, int charCount, int indexVersion,
                                    String parserVersion, String updatedAt) {
        jdbc.update("UPDATE document SET status = 'indexed', chunk_count = ?, char_count = ?, index_version = ?, "
                        + "parser_version = ?, error_code = NULL, error_message = NULL, updated_at = ? WHERE id = ?",
                chunkCount, charCount, indexVersion, parserVersion, updatedAt, id);
    }

    public void markDocumentFailed(String id, String errorCode, String errorMessage, String updatedAt) {
        jdbc.update("UPDATE document SET status = 'failed', error_code = ?, error_message = ?, updated_at = ? "
                + "WHERE id = ?", errorCode, errorMessage, updatedAt, id);
    }

    public Optional<DocumentRow> findDocument(String id) {
        List<DocumentRow> rows = jdbc.query("SELECT * FROM document WHERE id = ?", DOCUMENT_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<DocumentRow> listDocumentsForRebuild() {
        return jdbc.query("SELECT * FROM document ORDER BY created_at", DOCUMENT_MAPPER);
    }

    public List<DocumentRow> listDocumentsByFile(String fileId) {
        return jdbc.query("SELECT * FROM document WHERE file_id = ?", DOCUMENT_MAPPER, fileId);
    }

    public long countDocuments(String q, String status, String sha256) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM document d JOIN imported_file f ON f.id = d.file_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendDocumentFilters(sql, args, q, status, sha256);
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    public List<DocumentRow> pageDocuments(String q, String status, String sha256, int size, int offset) {
        StringBuilder sql = new StringBuilder("SELECT d.* FROM document d JOIN imported_file f ON f.id = d.file_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendDocumentFilters(sql, args, q, status, sha256);
        sql.append(" ORDER BY d.created_at DESC, d.id LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return jdbc.query(sql.toString(), DOCUMENT_MAPPER, args.toArray());
    }

    private static void appendDocumentFilters(StringBuilder sql, List<Object> args, String q, String status,
                                              String sha256) {
        if (q != null && !q.isBlank()) {
            sql.append(" AND d.title LIKE ? ESCAPE '\\'");
            args.add("%" + escapeLike(q.trim()) + "%");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND d.status = ?");
            args.add(status);
        }
        if (sha256 != null && !sha256.isBlank()) {
            sql.append(" AND f.sha256 = ?");
            args.add(sha256);
        }
    }

    public void deleteDocument(String id) {
        jdbc.update("DELETE FROM document WHERE id = ?", id);
    }

    // ------------------------------------------------------------- 分块

    public void insertChunks(List<ChunkRow> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO document_chunk(id, document_id, page_no, chunk_index, content, content_hash, "
                        + "parser_version, index_version, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                chunks, chunks.size(), (ps, chunk) -> {
                    ps.setString(1, chunk.id());
                    ps.setString(2, chunk.documentId());
                    if (chunk.pageNo() == null) {
                        ps.setNull(3, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(3, chunk.pageNo());
                    }
                    ps.setInt(4, chunk.chunkIndex());
                    ps.setString(5, chunk.content());
                    ps.setString(6, chunk.contentHash());
                    ps.setString(7, chunk.parserVersion());
                    ps.setInt(8, chunk.indexVersion());
                    ps.setString(9, chunk.createdAt());
                });
    }

    public void deleteChunksOfDocument(String documentId) {
        jdbc.update("DELETE FROM document_chunk WHERE document_id = ?", documentId);
    }

    public void deleteChunksByVersion(long indexVersion) {
        jdbc.update("DELETE FROM document_chunk WHERE index_version = ?", indexVersion);
    }

    /** 切换成功后清理旧版本分块，避免索引无限膨胀。 */
    public void deleteChunksBelowVersion(long indexVersion) {
        jdbc.update("DELETE FROM document_chunk WHERE index_version < ?", indexVersion);
    }

    public long countChunks(long indexVersion) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM document_chunk WHERE index_version = ?",
                Long.class, indexVersion);
        return count == null ? 0 : count;
    }

    public List<ChunkViewRow> pageChunks(String documentId, long indexVersion, int size, int offset) {
        return jdbc.query("SELECT id, document_id, chunk_index, page_no, content, content_hash, parser_version, "
                        + "index_version, created_at FROM document_chunk WHERE document_id = ? AND index_version = ? "
                        + "ORDER BY chunk_index LIMIT ? OFFSET ?",
                CHUNK_MAPPER, documentId, indexVersion, size, offset);
    }

    public long countChunksOfDocument(String documentId, long indexVersion) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM document_chunk WHERE document_id = ? AND index_version = ?",
                Long.class, documentId, indexVersion);
        return count == null ? 0 : count;
    }

    public List<ChunkViewRow> findChunksByIds(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        return jdbc.query("SELECT id, document_id, chunk_index, page_no, content, content_hash, parser_version, "
                        + "index_version, created_at FROM document_chunk WHERE id IN (" + placeholders + ")",
                CHUNK_MAPPER, ids.toArray());
    }

    // ------------------------------------------------------------- 索引版本状态

    public Optional<IndexStateRow> findIndexState(String indexName) {
        List<IndexStateRow> rows = jdbc.query("SELECT * FROM index_state WHERE index_name = ?", INDEX_MAPPER, indexName);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void insertIndexState(String indexName, long activeVersion, String updatedAt) {
        jdbc.update("INSERT INTO index_state(index_name, active_version, building_version, updated_at) VALUES (?,?,NULL,?)",
                indexName, activeVersion, updatedAt);
    }

    public void updateIndexState(String indexName, long activeVersion, Long buildingVersion, String updatedAt) {
        jdbc.update("UPDATE index_state SET active_version = ?, building_version = ?, updated_at = ? WHERE index_name = ?",
                activeVersion, buildingVersion, updatedAt, indexName);
    }

    // ------------------------------------------------------------- FTS5

    public void insertFtsRows(List<FtsRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO document_fts(content, chunk_id) VALUES (?,?)",
                rows, rows.size(), (ps, row) -> {
                    ps.setString(1, row.content());
                    ps.setString(2, row.chunkId());
                });
    }

    public void deleteFtsByDocument(String documentId) {
        jdbc.update("DELETE FROM document_fts WHERE chunk_id IN (SELECT id FROM document_chunk WHERE document_id = ?)",
                documentId);
    }

    public void deleteFtsByChunkIds(List<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", chunkIds.stream().map(id -> "?").toList());
        jdbc.update("DELETE FROM document_fts WHERE chunk_id IN (" + placeholders + ")", chunkIds.toArray());
    }

    public void deleteFtsBelowVersion(long indexVersion) {
        jdbc.update("DELETE FROM document_fts WHERE chunk_id IN "
                + "(SELECT id FROM document_chunk WHERE index_version < ?)", indexVersion);
    }

    /** 删除指定版本的全部索引行（构建失败回滚时使用）。 */
    public void deleteFtsByVersion(long indexVersion) {
        jdbc.update("DELETE FROM document_fts WHERE chunk_id IN "
                + "(SELECT id FROM document_chunk WHERE index_version = ?)", indexVersion);
    }

    /** 新版本的第一条分块，用于索引完整性自检。 */
    public Optional<ChunkViewRow> pageChunksFirst(long indexVersion) {
        List<ChunkViewRow> rows = jdbc.query(
                "SELECT id, document_id, chunk_index, page_no, content, content_hash, parser_version, "
                        + "index_version, created_at FROM document_chunk WHERE index_version = ? "
                        + "ORDER BY document_id, chunk_index LIMIT 1",
                CHUNK_MAPPER, indexVersion);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public long countFtsRows() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM document_fts", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * FTS5 检索：按版本过滤，返回 BM25 排序结果。
     *
     * <p>{@code matchExpression} 由调用方构造并转义；这里不做任何拼接（防注入）。
     */
    public List<SearchRow> searchFts(String matchExpression, long indexVersion, String documentId, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT document_fts.chunk_id AS chunk_id, c.document_id AS document_id, c.chunk_index AS chunk_index, "
                        + "c.page_no AS page_no, d.title AS title, bm25(document_fts) AS bm25_score, "
                        + "snippet(document_fts, 0, '[[', ']]', '…', 24) AS snippet "
                        + "FROM document_fts "
                        + "JOIN document_chunk c ON c.id = document_fts.chunk_id "
                        + "JOIN document d ON d.id = c.document_id "
                        + "WHERE document_fts MATCH ? AND c.index_version = ?");
        List<Object> args = new ArrayList<>();
        args.add(matchExpression);
        args.add(indexVersion);
        if (documentId != null && !documentId.isBlank()) {
            sql.append(" AND c.document_id = ?");
            args.add(documentId);
        }
        sql.append(" ORDER BY bm25_score, c.document_id, c.chunk_index LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), SEARCH_MAPPER, args.toArray());
    }

    // ------------------------------------------------------------- 关键词降级

    public List<SearchRow> searchKeyword(String likePattern, long indexVersion, String documentId, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT c.id AS chunk_id, c.document_id AS document_id, c.chunk_index AS chunk_index, "
                        + "c.page_no AS page_no, d.title AS title, c.content AS content "
                        + "FROM document_chunk c JOIN document d ON d.id = c.document_id "
                        + "WHERE c.index_version = ? AND c.content LIKE ? ESCAPE '\\'");
        List<Object> args = new ArrayList<>();
        args.add(indexVersion);
        args.add(likePattern);
        if (documentId != null && !documentId.isBlank()) {
            sql.append(" AND c.document_id = ?");
            args.add(documentId);
        }
        sql.append(" ORDER BY c.document_id, c.chunk_index LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), SEARCH_MAPPER, args.toArray());
    }

    // ------------------------------------------------------------- 工具

    /** LIKE 通配符转义：反斜杠、百分号、下划线。 */
    public static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static final RowMapper<FileRow> FILE_MAPPER = (rs, rowNum) -> new FileRow(
            rs.getString("id"), rs.getString("sha256"), rs.getLong("size_bytes"), rs.getString("mime_type"),
            rs.getString("original_name"), rs.getString("relative_path"), rs.getString("uploaded_at"),
            rs.getString("parse_status"), rs.getString("created_at"), rs.getString("updated_at"));

    private static final RowMapper<DocumentRow> DOCUMENT_MAPPER = (rs, rowNum) -> new DocumentRow(
            rs.getString("id"), rs.getString("title"), rs.getString("file_id"), rs.getString("status"),
            rs.getString("source_kind"), rs.getInt("chunk_count"), rs.getInt("char_count"),
            (Integer) rs.getObject("index_version"), rs.getString("parser_version"), rs.getString("error_code"),
            rs.getString("error_message"), rs.getString("created_at"), rs.getString("updated_at"));

    private static final RowMapper<ChunkViewRow> CHUNK_MAPPER = (rs, rowNum) -> new ChunkViewRow(
            rs.getString("id"), rs.getString("document_id"), rs.getInt("chunk_index"),
            (Integer) rs.getObject("page_no"), rs.getString("content"), rs.getString("content_hash"),
            rs.getString("parser_version"), rs.getInt("index_version"), rs.getString("created_at"));

    private static final RowMapper<IndexStateRow> INDEX_MAPPER = (rs, rowNum) -> new IndexStateRow(
            rs.getString("index_name"), rs.getLong("active_version"), (Long) rs.getObject("building_version"),
            rs.getString("updated_at"));

    private static final RowMapper<SearchRow> SEARCH_MAPPER = (rs, rowNum) -> new SearchRow(
            rs.getString("chunk_id"), rs.getString("document_id"), rs.getString("title"),
            (Integer) rs.getObject("page_no"), rs.getInt("chunk_index"),
            hasColumn(rs, "snippet") ? rs.getString("snippet") : null,
            hasColumn(rs, "content") ? rs.getString("content") : null,
            hasColumn(rs, "bm25_score") ? rs.getDouble("bm25_score") : 0d);

    private static boolean hasColumn(java.sql.ResultSet rs, String name) {
        try {
            rs.findColumn(name);
            return true;
        } catch (java.sql.SQLException e) {
            return false;
        }
    }

    public record FileRow(String id, String sha256, long sizeBytes, String mimeType, String originalName,
                          String relativePath, String uploadedAt, String parseStatus, String createdAt,
                          String updatedAt) {
    }

    public record DocumentRow(String id, String title, String fileId, String status, String sourceKind,
                              int chunkCount, int charCount, Integer indexVersion, String parserVersion,
                              String errorCode, String errorMessage, String createdAt, String updatedAt) {
    }

    public record ChunkRow(String id, String documentId, Integer pageNo, int chunkIndex, String content,
                           String contentHash, String parserVersion, int indexVersion, String createdAt) {
    }

    public record ChunkViewRow(String id, String documentId, int chunkIndex, Integer pageNo, String content,
                               String contentHash, String parserVersion, int indexVersion, String createdAt) {
    }

    public record IndexStateRow(String indexName, long activeVersion, Long buildingVersion, String updatedAt) {
    }

    public record FtsRow(String content, String chunkId) {
    }

    /** 检索原始行：FTS5 路径带 snippet 与 bm25，关键词路径带 content（片段在 service 层截取）。 */
    public record SearchRow(String chunkId, String documentId, String title, Integer pageNo, int chunkIndex,
                            String snippet, String content, double bm25) {
    }

    /** 供 service 层组装 DocumentSummary 用：资料行 + 文件行。 */
    public record DocumentWithFile(DocumentRow document, FileRow file) {
    }

    public DocumentDtos.DocumentSummary toSummary(DocumentRow document, FileRow file) {
        return new DocumentDtos.DocumentSummary(document.id(), document.title(), document.status(),
                document.sourceKind(), file == null ? "" : file.originalName(), file == null ? 0L : file.sizeBytes(),
                file == null ? "" : file.sha256(), document.chunkCount(), document.charCount(),
                document.indexVersion(), document.parserVersion(), document.errorCode(), document.errorMessage(),
                document.createdAt(), document.updatedAt());
    }
}
