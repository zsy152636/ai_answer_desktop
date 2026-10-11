package com.aia.answer.repository;

import com.aia.answer.domain.PaperDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 试卷、草稿组成与发布快照持久化。
 *
 * <p>发布快照一经写入即不可修改：历史作答永远依赖快照而不是当前题目内容（开发文档 §5.1）。
 */
@Repository
public class PaperRepository {

    private final JdbcTemplate jdbc;

    public PaperRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------- 试卷

    public void insertPaper(String id, String title, String description, String status, String createdAt) {
        jdbc.update("INSERT INTO paper(id, title, description, status, current_snapshot_id, published_at, created_at, "
                        + "updated_at) VALUES (?,?,?,?,NULL,NULL,?,?)",
                id, title, description, status, createdAt, createdAt);
    }

    public void updatePaper(String id, String title, String description, String updatedAt) {
        jdbc.update("UPDATE paper SET title = ?, description = ?, updated_at = ? WHERE id = ?",
                title, description, updatedAt, id);
    }

    public void updateStatus(String id, String status, String updatedAt) {
        jdbc.update("UPDATE paper SET status = ?, updated_at = ? WHERE id = ?", status, updatedAt, id);
    }

    public Optional<PaperRow> findPaper(String id) {
        List<PaperRow> rows = jdbc.query("SELECT id, title, description, status, current_snapshot_id, published_at, "
                + "created_at, updated_at FROM paper WHERE id = ?", PAPER_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<PaperSummaryRow> listPage(PaperDtos.Query query, int offset, int size) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(query, params);
        params.add(size);
        params.add(offset);
        return jdbc.query(SUMMARY_SQL + where + " ORDER BY p.updated_at DESC LIMIT ? OFFSET ?",
                SUMMARY_MAPPER, params.toArray());
    }

    public long count(PaperDtos.Query query) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(query, params);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM paper p " + where, Long.class, params.toArray());
        return total == null ? 0L : total;
    }

    public long countAll() {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM paper", Long.class);
        return total == null ? 0L : total;
    }

    public long countByStatus(String status) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM paper WHERE status = ?", Long.class, status);
        return total == null ? 0L : total;
    }

    // ------------------------------------------------------------- 草稿组成

    public void insertPaperQuestions(List<PaperQuestionRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO paper_question(id, paper_id, question_id, question_version_id, order_no, score) "
                        + "VALUES (?,?,?,?,?,?)",
                rows, rows.size(), (ps, row) -> {
                    ps.setString(1, row.id());
                    ps.setString(2, row.paperId());
                    ps.setString(3, row.questionId());
                    ps.setString(4, row.questionVersionId());
                    ps.setInt(5, row.orderNo());
                    ps.setDouble(6, row.score());
                });
    }

    public void deletePaperQuestions(String paperId) {
        jdbc.update("DELETE FROM paper_question WHERE paper_id = ?", paperId);
    }

    public List<PaperQuestionDetail> listPaperQuestions(String paperId) {
        return jdbc.query("SELECT pq.id, pq.paper_id, pq.question_id, pq.question_version_id, pq.order_no, pq.score, "
                + "q.title, q.question_type, qv.version_no FROM paper_question pq "
                + "JOIN question q ON q.id = pq.question_id "
                + "JOIN question_version qv ON qv.id = pq.question_version_id "
                + "WHERE pq.paper_id = ? ORDER BY pq.order_no", PAPER_QUESTION_DETAIL_MAPPER, paperId);
    }

    // ------------------------------------------------------------- 发布快照

    public int nextSnapshotVersionNo(String paperId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0) FROM paper_snapshot WHERE paper_id = ?", Integer.class, paperId);
        return (max == null ? 0 : max) + 1;
    }

    public void insertSnapshot(SnapshotRow snapshot) {
        jdbc.update("INSERT INTO paper_snapshot(id, paper_id, version_no, title, description, question_count, "
                        + "total_score, published_at, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                snapshot.id(), snapshot.paperId(), snapshot.versionNo(), snapshot.title(), snapshot.description(),
                snapshot.questionCount(), snapshot.totalScore(), snapshot.publishedAt(), snapshot.createdAt());
    }

    public void insertSnapshotQuestions(List<SnapshotQuestionRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO paper_snapshot_question(id, snapshot_id, question_id, question_version_id, "
                        + "order_no, score, snapshot_json) VALUES (?,?,?,?,?,?,?)",
                rows, rows.size(), (ps, row) -> {
                    ps.setString(1, row.id());
                    ps.setString(2, row.snapshotId());
                    ps.setString(3, row.questionId());
                    ps.setString(4, row.questionVersionId());
                    ps.setInt(5, row.orderNo());
                    ps.setDouble(6, row.score());
                    ps.setString(7, row.snapshotJson());
                });
    }

    public void setCurrentSnapshot(String paperId, String snapshotId, String publishedAt) {
        jdbc.update("UPDATE paper SET current_snapshot_id = ?, status = 'published', published_at = ?, updated_at = ? "
                + "WHERE id = ?", snapshotId, publishedAt, publishedAt, paperId);
    }

    public Optional<SnapshotRow> findSnapshot(String snapshotId) {
        List<SnapshotRow> rows = jdbc.query("SELECT id, paper_id, version_no, title, description, question_count, "
                + "total_score, published_at, created_at FROM paper_snapshot WHERE id = ?", SNAPSHOT_MAPPER, snapshotId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<SnapshotRow> listSnapshots(String paperId) {
        return jdbc.query("SELECT id, paper_id, version_no, title, description, question_count, total_score, "
                + "published_at, created_at FROM paper_snapshot WHERE paper_id = ? ORDER BY version_no DESC",
                SNAPSHOT_MAPPER, paperId);
    }

    public List<SnapshotQuestionRow> listSnapshotQuestions(String snapshotId) {
        return jdbc.query("SELECT id, snapshot_id, question_id, question_version_id, order_no, score, snapshot_json "
                + "FROM paper_snapshot_question WHERE snapshot_id = ? ORDER BY order_no",
                SNAPSHOT_QUESTION_MAPPER, snapshotId);
    }

    public Optional<SnapshotQuestionRow> findSnapshotQuestion(String id) {
        List<SnapshotQuestionRow> rows = jdbc.query("SELECT id, snapshot_id, question_id, question_version_id, order_no, "
                + "score, snapshot_json FROM paper_snapshot_question WHERE id = ?", SNAPSHOT_QUESTION_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<SnapshotQuestionRow> findSnapshotQuestionsByVersion(String questionVersionId) {
        return jdbc.query("SELECT id, snapshot_id, question_id, question_version_id, order_no, score, snapshot_json "
                + "FROM paper_snapshot_question WHERE question_version_id = ?", SNAPSHOT_QUESTION_MAPPER, questionVersionId);
    }

    private String buildWhere(PaperDtos.Query query, List<Object> params) {
        List<String> clauses = new ArrayList<>();
        if (query != null) {
            if (query.q() != null && !query.q().isBlank()) {
                clauses.add("p.title LIKE ?");
                params.add("%" + query.q().trim() + "%");
            }
            if (query.status() != null && !query.status().isBlank()) {
                clauses.add("p.status = ?");
                params.add(query.status());
            }
        }
        return clauses.isEmpty() ? "" : "WHERE " + String.join(" AND ", clauses);
    }

    private static final String SUMMARY_SQL =
            "SELECT p.id, p.title, p.description, p.status, p.current_snapshot_id, p.published_at, p.created_at, "
                    + "p.updated_at, COALESCE(ps.question_count, "
                    + "(SELECT COUNT(*) FROM paper_question pq WHERE pq.paper_id = p.id)) AS question_count, "
                    + "COALESCE(ps.total_score, "
                    + "(SELECT COALESCE(SUM(score),0) FROM paper_question pq WHERE pq.paper_id = p.id)) AS total_score "
                    + "FROM paper p LEFT JOIN paper_snapshot ps ON ps.id = p.current_snapshot_id ";

    private static final RowMapper<PaperRow> PAPER_MAPPER = (rs, rowNum) -> new PaperRow(
            rs.getString("id"), rs.getString("title"), rs.getString("description"), rs.getString("status"),
            rs.getString("current_snapshot_id"), rs.getString("published_at"), rs.getString("created_at"),
            rs.getString("updated_at"));

    private static final RowMapper<PaperSummaryRow> SUMMARY_MAPPER = (rs, rowNum) -> new PaperSummaryRow(
            rs.getString("id"), rs.getString("title"), rs.getString("description"), rs.getString("status"),
            rs.getInt("question_count"), rs.getDouble("total_score"), rs.getString("published_at"),
            rs.getString("created_at"), rs.getString("updated_at"), rs.getString("current_snapshot_id"));

    private static final RowMapper<PaperQuestionDetail> PAPER_QUESTION_DETAIL_MAPPER = (rs, rowNum) ->
            new PaperQuestionDetail(rs.getString("id"), rs.getString("paper_id"), rs.getString("question_id"),
                    rs.getString("question_version_id"), rs.getInt("order_no"), rs.getDouble("score"),
                    rs.getString("title"), rs.getString("question_type"), rs.getInt("version_no"));

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, rowNum) -> new SnapshotRow(
            rs.getString("id"), rs.getString("paper_id"), rs.getInt("version_no"), rs.getString("title"),
            rs.getString("description"), rs.getInt("question_count"), rs.getDouble("total_score"),
            rs.getString("published_at"), rs.getString("created_at"));

    private static final RowMapper<SnapshotQuestionRow> SNAPSHOT_QUESTION_MAPPER = (rs, rowNum) -> new SnapshotQuestionRow(
            rs.getString("id"), rs.getString("snapshot_id"), rs.getString("question_id"),
            rs.getString("question_version_id"), rs.getInt("order_no"), rs.getDouble("score"),
            rs.getString("snapshot_json"));

    public record PaperRow(String id, String title, String description, String status, String currentSnapshotId,
                           String publishedAt, String createdAt, String updatedAt) {
    }

    public record PaperSummaryRow(String id, String title, String description, String status, int questionCount,
                                  double totalScore, String publishedAt, String createdAt, String updatedAt,
                                  String latestSnapshotId) {
    }

    public record PaperQuestionRow(String id, String paperId, String questionId, String questionVersionId,
                                   int orderNo, double score) {
    }

    public record PaperQuestionDetail(String id, String paperId, String questionId, String questionVersionId,
                                      int orderNo, double score, String title, String questionType, int versionNo) {
    }

    public record SnapshotRow(String id, String paperId, int versionNo, String title, String description,
                              int questionCount, double totalScore, String publishedAt, String createdAt) {
    }

    public record SnapshotQuestionRow(String id, String snapshotId, String questionId, String questionVersionId,
                                      int orderNo, double score, String snapshotJson) {
    }
}
