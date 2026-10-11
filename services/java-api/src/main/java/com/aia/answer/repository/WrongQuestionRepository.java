package com.aia.answer.repository;

import com.aia.answer.domain.WrongDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 错题闭环持久化。
 *
 * <p>去重键是 {@code question_version_id}（唯一约束）：题目改义产生新版本后，
 * 新版本会独立成一条错题记录，旧版本的统计不被污染的（开发文档 §5.3）。
 */
@Repository
public class WrongQuestionRepository {

    private final JdbcTemplate jdbc;

    public WrongQuestionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 答错时插入或累加。返回受影响的错题 id。 */
    public String upsertOnWrong(String id, String questionId, String questionVersionId, Double lastScore,
                                double maxScore, String lastWrongAt, String nextReviewAt, String now) {
        jdbc.update("INSERT INTO wrong_question(id, question_id, question_version_id, status, wrong_count, "
                        + "correct_streak, last_score, max_score, last_wrong_at, next_review_at, created_at, updated_at) "
                        + "VALUES (?,?,?, 'active', 1, 0, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(question_version_id) DO UPDATE SET "
                        + "wrong_count = wrong_question.wrong_count + 1, correct_streak = 0, status = 'active', "
                        + "last_score = excluded.last_score, max_score = excluded.max_score, "
                        + "last_wrong_at = excluded.last_wrong_at, next_review_at = excluded.next_review_at, "
                        + "updated_at = excluded.updated_at",
                id, questionId, questionVersionId, lastScore, maxScore, lastWrongAt, nextReviewAt, now, now);
        return findRowByVersion(questionVersionId).map(WrongQuestionRow::id).orElse(id);
    }

    public Optional<WrongQuestionRow> findById(String id) {
        List<WrongQuestionRow> rows = jdbc.query(VIEW_SQL + " WHERE w.id = ?", VIEW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<WrongQuestionRow> findRowByVersion(String questionVersionId) {
        List<WrongQuestionRow> rows = jdbc.query(VIEW_SQL + " WHERE w.question_version_id = ?", VIEW_MAPPER,
                questionVersionId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<WrongQuestionRow> listPage(WrongDtos.Query query, int offset, int size, String now) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(query, params, now);
        params.add(size);
        params.add(offset);
        return jdbc.query(VIEW_SQL + where + " ORDER BY " + orderClause(query == null ? null : query.sort())
                + " LIMIT ? OFFSET ?", VIEW_MAPPER, params.toArray());
    }

    public long count(WrongDtos.Query query, String now) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(query, params, now);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM wrong_question w " + where, Long.class, params.toArray());
        return total == null ? 0L : total;
    }

    public List<WrongQuestionRow> listRecent(int limit) {
        return jdbc.query(VIEW_SQL + " ORDER BY w.updated_at DESC LIMIT ?", VIEW_MAPPER, limit);
    }

    public List<WrongQuestionRow> listDue(String now, int limit) {
        return jdbc.query(VIEW_SQL + " WHERE w.next_review_at IS NOT NULL AND w.next_review_at <= ? "
                + "AND w.status IN ('active','reviewing') ORDER BY w.next_review_at LIMIT ?", VIEW_MAPPER, now, limit);
    }

    public List<WrongQuestionRow> listByIds(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ids.stream().map(v -> "?").toList());
        return jdbc.query(VIEW_SQL + " WHERE w.id IN (" + placeholders + ")", VIEW_MAPPER, ids.toArray());
    }

    public void applyReview(String id, String status, int correctStreak, String nextReviewAt, String now) {
        jdbc.update("UPDATE wrong_question SET status = ?, correct_streak = ?, next_review_at = ?, updated_at = ? "
                + "WHERE id = ?", status, correctStreak, nextReviewAt, now, id);
    }

    public void updateStatus(String id, String status, String nextReviewAt, String now) {
        jdbc.update("UPDATE wrong_question SET status = ?, next_review_at = ?, updated_at = ? WHERE id = ?",
                status, nextReviewAt, now, id);
    }

    public void insertReview(String id, String wrongQuestionId, String attemptAnswerId, String result, String note,
                             Integer intervalDaysBefore, Integer intervalDaysAfter, String nextReviewAt,
                             Integer durationSeconds, String reviewedAt) {
        jdbc.update("INSERT INTO wrong_question_review(id, wrong_question_id, attempt_answer_id, result, note, "
                        + "interval_days_before, interval_days_after, next_review_at, duration_seconds, reviewed_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, wrongQuestionId, attemptAnswerId, result, note, intervalDaysBefore, intervalDaysAfter,
                nextReviewAt, durationSeconds, reviewedAt);
    }

    public List<ReviewRow> listReviews(String wrongQuestionId, int limit) {
        return jdbc.query("SELECT id, wrong_question_id, attempt_answer_id, result, note, interval_days_before, "
                + "interval_days_after, next_review_at, duration_seconds, reviewed_at FROM wrong_question_review "
                + "WHERE wrong_question_id = ? ORDER BY reviewed_at DESC LIMIT ?", REVIEW_MAPPER, wrongQuestionId, limit);
    }

    public long countByStatus(String status) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM wrong_question WHERE status = ?", Long.class, status);
        return total == null ? 0L : total;
    }

    public long countDue(String now) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM wrong_question WHERE next_review_at IS NOT NULL "
                + "AND next_review_at <= ? AND status IN ('active','reviewing')", Long.class, now);
        return total == null ? 0L : total;
    }

    private String buildWhere(WrongDtos.Query query, List<Object> params, String now) {
        List<String> clauses = new ArrayList<>();
        if (query != null) {
            if (query.status() != null && !query.status().isBlank()) {
                clauses.add("w.status = ?");
                params.add(query.status());
            }
            if (Boolean.TRUE.equals(query.dueOnly())) {
                clauses.add("w.next_review_at IS NOT NULL AND w.next_review_at <= ? AND w.status IN ('active','reviewing')");
                params.add(now);
            }
            if (query.tag() != null && !query.tag().isBlank()) {
                clauses.add("qv.knowledge_tags_json LIKE ?");
                params.add("%\"" + query.tag().trim() + "\"%");
            }
            if (query.questionType() != null && !query.questionType().isBlank()) {
                clauses.add("q.question_type = ?");
                params.add(query.questionType());
            }
        }
        return clauses.isEmpty() ? "" : "WHERE " + String.join(" AND ", clauses);
    }

    private String orderClause(String sort) {
        if (sort == null || sort.isBlank()) {
            return "w.updated_at DESC";
        }
        return switch (sort) {
            case "-updatedAt" -> "w.updated_at DESC";
            case "updatedAt" -> "w.updated_at ASC";
            case "nextReviewAt" -> "w.next_review_at ASC";
            case "lastWrongAt" -> "w.last_wrong_at DESC";
            case "wrongCount" -> "w.wrong_count DESC";
            default -> throw com.aia.answer.common.ApiException.validation(
                    "sort 只支持 -updatedAt/updatedAt/nextReviewAt/lastWrongAt/wrongCount", "sort");
        };
    }

    private static final String VIEW_SQL =
            "SELECT w.id, w.question_id, w.question_version_id, w.status, w.wrong_count, w.correct_streak, "
                    + "w.last_score, w.max_score, w.last_wrong_at, w.next_review_at, w.created_at, w.updated_at, "
                    + "q.title, q.question_type, qv.stem, qv.knowledge_tags_json, qv.version_no AS wrong_version_no, "
                    + "COALESCE((SELECT version_no FROM question_version WHERE id = q.current_version_id), "
                    + "qv.version_no) AS current_version_no "
                    + "FROM wrong_question w "
                    + "JOIN question q ON q.id = w.question_id "
                    + "JOIN question_version qv ON qv.id = w.question_version_id ";

    private static final RowMapper<WrongQuestionRow> VIEW_MAPPER = (rs, rowNum) -> new WrongQuestionRow(
            rs.getString("id"), rs.getString("question_id"), rs.getString("question_version_id"),
            rs.getString("status"), rs.getInt("wrong_count"), rs.getInt("correct_streak"),
            (Double) rs.getObject("last_score"), (Double) rs.getObject("max_score"), rs.getString("last_wrong_at"),
            rs.getString("next_review_at"), rs.getString("created_at"), rs.getString("updated_at"),
            rs.getString("title"), rs.getString("question_type"), rs.getString("stem"),
            rs.getString("knowledge_tags_json"), rs.getInt("current_version_no"), rs.getInt("wrong_version_no"));

    private static final RowMapper<ReviewRow> REVIEW_MAPPER = (rs, rowNum) -> new ReviewRow(
            rs.getString("id"), rs.getString("wrong_question_id"), rs.getString("attempt_answer_id"),
            rs.getString("result"), rs.getString("note"), (Integer) rs.getObject("interval_days_before"),
            (Integer) rs.getObject("interval_days_after"), rs.getString("next_review_at"),
            (Integer) rs.getObject("duration_seconds"), rs.getString("reviewed_at"));

    public record WrongQuestionRow(String id, String questionId, String questionVersionId, String status,
                                   int wrongCount, int correctStreak, Double lastScore, Double maxScore,
                                   String lastWrongAt, String nextReviewAt, String createdAt, String updatedAt,
                                   String title, String questionType, String stem, String knowledgeTagsJson,
                                   int currentVersionNo, int wrongVersionNo) {
    }

    public record ReviewRow(String id, String wrongQuestionId, String attemptAnswerId, String result, String note,
                            Integer intervalDaysBefore, Integer intervalDaysAfter, String nextReviewAt,
                            Integer durationSeconds, String reviewedAt) {
    }
}
