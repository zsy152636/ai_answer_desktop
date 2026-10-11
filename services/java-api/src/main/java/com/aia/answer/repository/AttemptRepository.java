package com.aia.answer.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 作答与逐题答案持久化。
 *
 * <p>「先持久化作答，再执行评分」是硬规则（开发文档 §5.2）：{@link #upsertAnswer} 只写答案，
 * 判分结果通过 {@link #updateAnswerGrading} 单独更新，二者不共享事务。
 */
@Repository
public class AttemptRepository {

    private final JdbcTemplate jdbc;

    public AttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAttempt(AttemptRow row) {
        jdbc.update("INSERT INTO attempt(id, paper_id, paper_snapshot_id, mode, status, question_count, total_score, "
                        + "max_score, started_at, submitted_at, graded_at, abandoned_at, duration_seconds) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.id(), row.paperId(), row.paperSnapshotId(), row.mode(), row.status(), row.questionCount(),
                row.totalScore(), row.maxScore(), row.startedAt(), row.submittedAt(), row.gradedAt(),
                row.abandonedAt(), row.durationSeconds());
    }

    public Optional<AttemptRow> findAttempt(String id) {
        List<AttemptRow> rows = jdbc.query("SELECT id, paper_id, paper_snapshot_id, mode, status, question_count, "
                + "total_score, max_score, started_at, submitted_at, graded_at, abandoned_at, duration_seconds "
                + "FROM attempt WHERE id = ?", ATTEMPT_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void markSubmitted(String id, String submittedAt, int durationSeconds) {
        jdbc.update("UPDATE attempt SET status = 'submitted', submitted_at = ?, duration_seconds = ? WHERE id = ?",
                submittedAt, durationSeconds, id);
    }

    public void markGraded(String id, double totalScore, double maxScore, String gradedAt) {
        jdbc.update("UPDATE attempt SET status = 'graded', total_score = ?, max_score = ?, graded_at = ? WHERE id = ?",
                totalScore, maxScore, gradedAt, id);
    }

    public void markAbandoned(String id, String abandonedAt) {
        jdbc.update("UPDATE attempt SET status = 'abandoned', abandoned_at = ? WHERE id = ?", abandonedAt, id);
    }

    public List<AttemptSummaryRow> listPage(String status, String paperId, int offset, int size) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(status, paperId, params);
        params.add(size);
        params.add(offset);
        return jdbc.query(SUMMARY_SQL + where + " ORDER BY a.started_at DESC LIMIT ? OFFSET ?",
                SUMMARY_MAPPER, params.toArray());
    }

    public long count(String status, String paperId) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(status, paperId, params);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM attempt a " + where, Long.class, params.toArray());
        return total == null ? 0L : total;
    }

    public List<AttemptSummaryRow> listRecent(int limit) {
        return jdbc.query(SUMMARY_SQL + " ORDER BY a.started_at DESC LIMIT ?", SUMMARY_MAPPER, limit);
    }

    public long countByStatus(String status) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM attempt WHERE status = ?", Long.class, status);
        return total == null ? 0L : total;
    }

    // ------------------------------------------------------------- 逐题答案

    /** 幂等保存单题答案：同一 (attempt, snapshotQuestion) 重复保存只更新内容与时间。 */
    public void upsertAnswer(AnswerRow row) {
        jdbc.update("INSERT INTO attempt_answer(id, attempt_id, snapshot_question_id, question_id, question_version_id, "
                        + "answer_json, score, max_score, grading_method, grading_status, grading_detail_json, saved_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(attempt_id, snapshot_question_id) DO UPDATE SET "
                        + "answer_json = excluded.answer_json, saved_at = excluded.saved_at, "
                        + "score = NULL, grading_method = 'ungraded', grading_status = 'pending_review', "
                        + "grading_detail_json = NULL",
                row.id(), row.attemptId(), row.snapshotQuestionId(), row.questionId(), row.questionVersionId(),
                row.answerJson(), row.score(), row.maxScore(), row.gradingMethod(), row.gradingStatus(),
                row.gradingDetailJson(), row.savedAt());
    }

    public Optional<AnswerRow> findAnswer(String attemptId, String snapshotQuestionId) {
        List<AnswerRow> rows = jdbc.query("SELECT id, attempt_id, snapshot_question_id, question_id, question_version_id, "
                + "answer_json, score, max_score, grading_method, grading_status, grading_detail_json, saved_at "
                + "FROM attempt_answer WHERE attempt_id = ? AND snapshot_question_id = ?", ANSWER_MAPPER,
                attemptId, snapshotQuestionId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<AnswerRow> findAnswerById(String answerId) {
        List<AnswerRow> rows = jdbc.query("SELECT id, attempt_id, snapshot_question_id, question_id, question_version_id, "
                + "answer_json, score, max_score, grading_method, grading_status, grading_detail_json, saved_at "
                + "FROM attempt_answer WHERE id = ?", ANSWER_MAPPER, answerId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<AnswerRow> listAnswers(String attemptId) {
        return jdbc.query("SELECT id, attempt_id, snapshot_question_id, question_id, question_version_id, answer_json, "
                + "score, max_score, grading_method, grading_status, grading_detail_json, saved_at "
                + "FROM attempt_answer WHERE attempt_id = ? ORDER BY saved_at", ANSWER_MAPPER, attemptId);
    }

    public int countAnswers(String attemptId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM attempt_answer WHERE attempt_id = ?",
                Integer.class, attemptId);
        return count == null ? 0 : count;
    }

    public void updateAnswerGrading(String answerId, Double score, String gradingMethod, String gradingStatus,
                                    String gradingDetailJson) {
        jdbc.update("UPDATE attempt_answer SET score = ?, grading_method = ?, grading_status = ?, "
                + "grading_detail_json = ? WHERE id = ?", score, gradingMethod, gradingStatus, gradingDetailJson, answerId);
    }

    private String buildWhere(String status, String paperId, List<Object> params) {
        List<String> clauses = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            clauses.add("a.status = ?");
            params.add(status);
        }
        if (paperId != null && !paperId.isBlank()) {
            clauses.add("a.paper_id = ?");
            params.add(paperId);
        }
        return clauses.isEmpty() ? "" : "WHERE " + String.join(" AND ", clauses);
    }

    private static final String SUMMARY_SQL =
            "SELECT a.id, a.paper_id, a.paper_snapshot_id, a.mode, a.status, a.question_count, a.total_score, "
                    + "a.max_score, a.started_at, a.submitted_at, a.graded_at, "
                    + "COALESCE(ps.title, p.title, '错题练习') AS paper_title "
                    + "FROM attempt a LEFT JOIN paper p ON p.id = a.paper_id "
                    + "LEFT JOIN paper_snapshot ps ON ps.id = a.paper_snapshot_id ";

    private static final RowMapper<AttemptRow> ATTEMPT_MAPPER = (rs, rowNum) -> new AttemptRow(
            rs.getString("id"), rs.getString("paper_id"), rs.getString("paper_snapshot_id"), rs.getString("mode"),
            rs.getString("status"), rs.getInt("question_count"), (Double) rs.getObject("total_score"),
            (Double) rs.getObject("max_score"), rs.getString("started_at"), rs.getString("submitted_at"),
            rs.getString("graded_at"), rs.getString("abandoned_at"), (Integer) rs.getObject("duration_seconds"));

    private static final RowMapper<AttemptSummaryRow> SUMMARY_MAPPER = (rs, rowNum) -> new AttemptSummaryRow(
            rs.getString("id"), rs.getString("paper_id"), rs.getString("paper_snapshot_id"), rs.getString("paper_title"),
            rs.getString("mode"), rs.getString("status"), (Double) rs.getObject("total_score"),
            (Double) rs.getObject("max_score"), rs.getInt("question_count"), rs.getString("started_at"),
            rs.getString("submitted_at"), rs.getString("graded_at"));

    private static final RowMapper<AnswerRow> ANSWER_MAPPER = (rs, rowNum) -> new AnswerRow(
            rs.getString("id"), rs.getString("attempt_id"), rs.getString("snapshot_question_id"),
            rs.getString("question_id"), rs.getString("question_version_id"), rs.getString("answer_json"),
            (Double) rs.getObject("score"), rs.getDouble("max_score"), rs.getString("grading_method"),
            rs.getString("grading_status"), rs.getString("grading_detail_json"), rs.getString("saved_at"));

    public record AttemptRow(String id, String paperId, String paperSnapshotId, String mode, String status,
                             int questionCount, Double totalScore, Double maxScore, String startedAt,
                             String submittedAt, String gradedAt, String abandonedAt, Integer durationSeconds) {
    }

    public record AttemptSummaryRow(String id, String paperId, String paperSnapshotId, String paperTitle, String mode,
                                    String status, Double totalScore, Double maxScore, int questionCount,
                                    String startedAt, String submittedAt, String gradedAt) {
    }

    public record AnswerRow(String id, String attemptId, String snapshotQuestionId, String questionId,
                            String questionVersionId, String answerJson, Double score, double maxScore,
                            String gradingMethod, String gradingStatus, String gradingDetailJson, String savedAt) {
    }
}
