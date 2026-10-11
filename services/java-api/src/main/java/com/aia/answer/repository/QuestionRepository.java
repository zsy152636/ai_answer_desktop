package com.aia.answer.repository;

import com.aia.answer.domain.QuestionDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 题库与题目版本持久化。
 *
 * <p>题目身份（question）与内容（question_version）严格分离：任何影响题意、选项、
 * 标准答案或评分规则的修改都插入新版本，绝不覆盖历史（开发文档 §5.1）。
 */
@Repository
public class QuestionRepository {

    private final JdbcTemplate jdbc;

    public QuestionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------- 写入

    public void insertQuestion(String id, String title, String questionType, String status,
                              int difficulty, String createdAt) {
        jdbc.update("INSERT INTO question(id, title, question_type, status, difficulty, current_version_id, "
                        + "created_at, updated_at) VALUES (?,?,?,?,?,NULL,?,?)",
                id, title, questionType, status, difficulty, createdAt, createdAt);
    }

    public void insertVersion(VersionRow version) {
        jdbc.update("INSERT INTO question_version(id, question_id, version_no, stem, answer_json, explanation, "
                        + "rubric_json, knowledge_tags_json, content_hash, change_note, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                version.id(), version.questionId(), version.versionNo(), version.stem(), version.answerJson(),
                version.explanation(), version.rubricJson(), version.knowledgeTagsJson(), version.contentHash(),
                version.changeNote(), version.createdAt());
    }

    public void insertOptions(List<OptionRow> options) {
        if (options.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO question_option(id, question_version_id, option_key, content, is_correct, order_no) "
                        + "VALUES (?,?,?,?,?,?)",
                options, options.size(), (ps, option) -> {
                    ps.setString(1, option.id());
                    ps.setString(2, option.questionVersionId());
                    ps.setString(3, option.key());
                    ps.setString(4, option.content());
                    ps.setInt(5, option.correct() ? 1 : 0);
                    ps.setInt(6, option.orderNo());
                });
    }

    public void updateQuestionMeta(String id, String title, int difficulty, String updatedAt) {
        jdbc.update("UPDATE question SET title = ?, difficulty = ?, updated_at = ? WHERE id = ?",
                title, difficulty, updatedAt, id);
    }

    public void setCurrentVersion(String id, String versionId, String updatedAt) {
        jdbc.update("UPDATE question SET current_version_id = ?, updated_at = ? WHERE id = ?",
                versionId, updatedAt, id);
    }

    public void updateStatus(String id, String status, String updatedAt) {
        jdbc.update("UPDATE question SET status = ?, updated_at = ? WHERE id = ?", status, updatedAt, id);
    }

    public void deleteQuestion(String id) {
        jdbc.update("DELETE FROM question WHERE id = ?", id);
    }

    /**
     * 物理删除题目及其全部版本与选项。
     *
     * <p>调用方必须先确认题目未被试卷、作答或错题引用（外键为 RESTRICT，违反时数据库会直接拒绝）。
     */
    public void deleteQuestionFully(String id) {
        jdbc.update("DELETE FROM question_option WHERE question_version_id IN "
                + "(SELECT id FROM question_version WHERE question_id = ?)", id);
        jdbc.update("DELETE FROM question_version WHERE question_id = ?", id);
        jdbc.update("DELETE FROM question WHERE id = ?", id);
    }

    // ------------------------------------------------------------- 读取

    public Optional<QuestionRow> findQuestion(String id) {
        List<QuestionRow> rows = jdbc.query("SELECT id, title, question_type, status, difficulty, current_version_id, "
                + "created_at, updated_at FROM question WHERE id = ?", QUESTION_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<VersionRow> findVersion(String versionId) {
        List<VersionRow> rows = jdbc.query("SELECT id, question_id, version_no, stem, answer_json, explanation, "
                + "rubric_json, knowledge_tags_json, content_hash, change_note, created_at "
                + "FROM question_version WHERE id = ?", VERSION_MAPPER, versionId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<VersionRow> findVersionByNo(String questionId, int versionNo) {
        List<VersionRow> rows = jdbc.query("SELECT id, question_id, version_no, stem, answer_json, explanation, "
                + "rubric_json, knowledge_tags_json, content_hash, change_note, created_at "
                + "FROM question_version WHERE question_id = ? AND version_no = ?", VERSION_MAPPER, questionId, versionNo);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<VersionRow> listVersions(String questionId) {
        return jdbc.query("SELECT id, question_id, version_no, stem, answer_json, explanation, rubric_json, "
                + "knowledge_tags_json, content_hash, change_note, created_at FROM question_version "
                + "WHERE question_id = ? ORDER BY version_no DESC", VERSION_MAPPER, questionId);
    }

    public int nextVersionNo(String questionId) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0) FROM question_version WHERE question_id = ?",
                Integer.class, questionId);
        return (max == null ? 0 : max) + 1;
    }

    public List<OptionRow> listOptions(String questionVersionId) {
        return jdbc.query("SELECT id, question_version_id, option_key, content, is_correct, order_no "
                + "FROM question_option WHERE question_version_id = ? ORDER BY order_no", OPTION_MAPPER, questionVersionId);
    }

    public List<OptionRow> listOptionsByVersions(List<String> versionIds) {
        if (versionIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", versionIds.stream().map(v -> "?").toList());
        return jdbc.query("SELECT id, question_version_id, option_key, content, is_correct, order_no "
                + "FROM question_option WHERE question_version_id IN (" + placeholders + ") ORDER BY order_no",
                OPTION_MAPPER, versionIds.toArray());
    }

    public List<QuestionRow> listPage(QuestionDtos.Query query, int offset, int size) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(query, params);
        String order = orderClause(query == null ? null : query.sort());
        params.add(size);
        params.add(offset);
        return jdbc.query("SELECT id, title, question_type, status, difficulty, current_version_id, created_at, updated_at "
                + "FROM question " + where + " ORDER BY " + order + " LIMIT ? OFFSET ?", QUESTION_MAPPER, params.toArray());
    }

    public long count(QuestionDtos.Query query) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(query, params);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM question " + where, Long.class, params.toArray());
        return total == null ? 0L : total;
    }

    public long countAll() {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM question", Long.class);
        return total == null ? 0L : total;
    }

    public long countByStatus(String status) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM question WHERE status = ?", Long.class, status);
        return total == null ? 0L : total;
    }

    /** 是否被试卷、作答或错题引用（决定能否物理删除）。 */
    public boolean isReferenced(String questionId) {
        Long refs = jdbc.queryForObject(
                "SELECT (SELECT COUNT(*) FROM paper_question WHERE question_id = ?) "
                        + "+ (SELECT COUNT(*) FROM paper_snapshot_question WHERE question_id = ?) "
                        + "+ (SELECT COUNT(*) FROM attempt_answer WHERE question_id = ?) "
                        + "+ (SELECT COUNT(*) FROM wrong_question WHERE question_id = ?)",
                Long.class, questionId, questionId, questionId, questionId);
        return refs != null && refs > 0;
    }

    public boolean hasPublishedVersion(String questionId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM paper_snapshot_question pq JOIN paper_snapshot ps ON ps.id = pq.snapshot_id "
                        + "WHERE pq.question_id = ?", Long.class, questionId);
        return count != null && count > 0;
    }

    public List<String> listIdsByType(String questionType) {
        return jdbc.query("SELECT id FROM question WHERE question_type = ?", (rs, rowNum) -> rs.getString(1), questionType);
    }

    private String buildWhere(QuestionDtos.Query query, List<Object> params) {
        List<String> clauses = new ArrayList<>();
        if (query != null) {
            if (query.q() != null && !query.q().isBlank()) {
                clauses.add("(title LIKE ? OR current_version_id IN (SELECT id FROM question_version WHERE stem LIKE ?))");
                String like = "%" + query.q().trim() + "%";
                params.add(like);
                params.add(like);
            }
            if (query.questionType() != null && !query.questionType().isBlank()) {
                clauses.add("question_type = ?");
                params.add(query.questionType());
            }
            if (query.status() != null && !query.status().isBlank()) {
                clauses.add("status = ?");
                params.add(query.status());
            }
            if (query.tag() != null && !query.tag().isBlank()) {
                clauses.add("current_version_id IN (SELECT id FROM question_version WHERE knowledge_tags_json LIKE ?)");
                params.add("%\"" + query.tag().trim() + "\"%");
            }
        }
        return clauses.isEmpty() ? "" : "WHERE " + String.join(" AND ", clauses);
    }

    private String orderClause(String sort) {
        if (sort == null || sort.isBlank()) {
            return "updated_at DESC";
        }
        return switch (sort) {
            case "updatedAt" -> "updated_at ASC";
            case "-updatedAt" -> "updated_at DESC";
            case "createdAt" -> "created_at ASC";
            case "-createdAt" -> "created_at DESC";
            default -> throw com.aia.answer.common.ApiException.validation("sort 只支持 updatedAt/-updatedAt/createdAt/-createdAt", "sort");
        };
    }

    private static final RowMapper<QuestionRow> QUESTION_MAPPER = (rs, rowNum) -> new QuestionRow(
            rs.getString("id"), rs.getString("title"), rs.getString("question_type"), rs.getString("status"),
            rs.getInt("difficulty"), rs.getString("current_version_id"), rs.getString("created_at"),
            rs.getString("updated_at"));

    private static final RowMapper<VersionRow> VERSION_MAPPER = (rs, rowNum) -> new VersionRow(
            rs.getString("id"), rs.getString("question_id"), rs.getInt("version_no"), rs.getString("stem"),
            rs.getString("answer_json"), rs.getString("explanation"), rs.getString("rubric_json"),
            rs.getString("knowledge_tags_json"), rs.getString("content_hash"), rs.getString("change_note"),
            rs.getString("created_at"));

    private static final RowMapper<OptionRow> OPTION_MAPPER = (rs, rowNum) -> new OptionRow(
            rs.getString("id"), rs.getString("question_version_id"), rs.getString("option_key"),
            rs.getString("content"), rs.getInt("is_correct") != 0, rs.getInt("order_no"));

    public record QuestionRow(String id, String title, String questionType, String status, int difficulty,
                              String currentVersionId, String createdAt, String updatedAt) {
    }

    public record VersionRow(String id, String questionId, int versionNo, String stem, String answerJson,
                             String explanation, String rubricJson, String knowledgeTagsJson, String contentHash,
                             String changeNote, String createdAt) {
    }

    public record OptionRow(String id, String questionVersionId, String key, String content, boolean correct,
                            int orderNo) {
    }
}
