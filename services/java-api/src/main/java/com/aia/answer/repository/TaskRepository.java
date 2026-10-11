package com.aia.answer.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 异步任务持久化（开发文档 §8）。
 *
 * <p>认领采用「条件 UPDATE + 受影响行数」而不是 SELECT 后无条件 UPDATE，
 * 保证同一任务不会被两个执行者同时拿走。
 */
@Repository
public class TaskRepository {

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TaskRow row) {
        jdbc.update("INSERT INTO async_task(id, task_type, status, payload_json, result_json, idempotency_key, "
                        + "attempt_count, max_attempts, lease_owner, lease_until, heartbeat_at, next_run_at, "
                        + "last_error_code, last_error_message, created_at, updated_at) "
                        + "VALUES (?,?,?,?,NULL,?,0,?,NULL,NULL,NULL,?,NULL,NULL,?,?)",
                row.id(), row.taskType(), row.status(), row.payloadJson(), row.idempotencyKey(), row.maxAttempts(),
                row.nextRunAt(), row.createdAt(), row.updatedAt());
    }

    public Optional<TaskRow> findById(String id) {
        List<TaskRow> rows = jdbc.query(SELECT_SQL + " WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<TaskRow> findByIdempotencyKey(String key) {
        List<TaskRow> rows = jdbc.query(SELECT_SQL + " WHERE idempotency_key = ?", MAPPER, key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<TaskRow> listPage(String status, String taskType, int offset, int size) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(status, taskType, params);
        params.add(size);
        params.add(offset);
        return jdbc.query(SELECT_SQL + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?", MAPPER, params.toArray());
    }

    public long count(String status, String taskType) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(status, taskType, params);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM async_task " + where, Long.class, params.toArray());
        return total == null ? 0L : total;
    }

    /**
     * 原子认领下一个可执行任务。
     *
     * <p>先选出候选 id，再用带 {@code status = 'queued'} 条件的 UPDATE 抢占；受影响行数为 0
     * 说明候选已被别人拿走，重试下一轮。
     */
    public Optional<TaskRow> claimNext(String leaseOwner, String now, String leaseUntil) {
        for (int attempt = 0; attempt < 5; attempt++) {
            List<String> candidates = jdbc.query(
                    "SELECT id FROM async_task WHERE status IN ('queued','retry_wait') "
                            + "AND (next_run_at IS NULL OR next_run_at <= ?) ORDER BY created_at LIMIT 1",
                    (rs, rowNum) -> rs.getString(1), now);
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            String id = candidates.get(0);
            int updated = jdbc.update("UPDATE async_task SET status = 'running', lease_owner = ?, lease_until = ?, "
                            + "heartbeat_at = ?, attempt_count = attempt_count + 1, updated_at = ? "
                            + "WHERE id = ? AND status IN ('queued','retry_wait')",
                    leaseOwner, leaseUntil, now, now, id);
            if (updated == 1) {
                return findById(id);
            }
        }
        return Optional.empty();
    }

    public void heartbeat(String id, String leaseUntil, String now) {
        jdbc.update("UPDATE async_task SET heartbeat_at = ?, lease_until = ?, updated_at = ? WHERE id = ?",
                now, leaseUntil, now, id);
    }

    /** 回收过期租约（进程崩溃后任务可被重新调度）。 */
    public int recoverExpiredLeases(String now, String nextRunAt) {
        return jdbc.update("UPDATE async_task SET status = 'retry_wait', lease_owner = NULL, lease_until = NULL, "
                        + "next_run_at = ?, updated_at = ? WHERE status = 'running' AND lease_until IS NOT NULL "
                        + "AND lease_until < ?",
                nextRunAt, now, now);
    }

    public void succeed(String id, String resultJson, String now) {
        jdbc.update("UPDATE async_task SET status = 'succeeded', result_json = ?, lease_owner = NULL, "
                + "lease_until = NULL, updated_at = ? WHERE id = ?", resultJson, now, id);
    }

    public void scheduleRetry(String id, String errorCode, String errorMessage, String nextRunAt, String now) {
        jdbc.update("UPDATE async_task SET status = 'retry_wait', last_error_code = ?, last_error_message = ?, "
                        + "next_run_at = ?, lease_owner = NULL, lease_until = NULL, updated_at = ? WHERE id = ?",
                errorCode, errorMessage, nextRunAt, now, id);
    }

    public void fail(String id, String errorCode, String errorMessage, String now) {
        jdbc.update("UPDATE async_task SET status = 'failed', last_error_code = ?, last_error_message = ?, "
                        + "lease_owner = NULL, lease_until = NULL, updated_at = ? WHERE id = ?",
                errorCode, errorMessage, now, id);
    }

    public void retry(String id, String now) {
        jdbc.update("UPDATE async_task SET status = 'queued', attempt_count = 0, next_run_at = ?, "
                        + "last_error_code = NULL, last_error_message = NULL, updated_at = ? "
                        + "WHERE id = ? AND status IN ('failed','cancelled')", now, now, id);
    }

    public void cancel(String id, String now) {
        jdbc.update("UPDATE async_task SET status = 'cancelled', updated_at = ? "
                + "WHERE id = ? AND status IN ('queued','running','retry_wait')", now, id);
    }

    public long countByStatus(String status) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM async_task WHERE status = ?", Long.class, status);
        return total == null ? 0L : total;
    }

    private String buildWhere(String status, String taskType, List<Object> params) {
        List<String> clauses = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            clauses.add("status = ?");
            params.add(status);
        }
        if (taskType != null && !taskType.isBlank()) {
            clauses.add("task_type = ?");
            params.add(taskType);
        }
        return clauses.isEmpty() ? "" : "WHERE " + String.join(" AND ", clauses);
    }

    private static final String SELECT_SQL = "SELECT id, task_type, status, payload_json, result_json, "
            + "idempotency_key, attempt_count, max_attempts, lease_owner, lease_until, heartbeat_at, next_run_at, "
            + "last_error_code, last_error_message, created_at, updated_at FROM async_task";

    private static final RowMapper<TaskRow> MAPPER = (rs, rowNum) -> new TaskRow(
            rs.getString("id"), rs.getString("task_type"), rs.getString("status"), rs.getString("payload_json"),
            rs.getString("result_json"), rs.getString("idempotency_key"), rs.getInt("attempt_count"),
            rs.getInt("max_attempts"), rs.getString("lease_owner"), rs.getString("lease_until"),
            rs.getString("heartbeat_at"), rs.getString("next_run_at"), rs.getString("last_error_code"),
            rs.getString("last_error_message"), rs.getString("created_at"), rs.getString("updated_at"));

    public record TaskRow(String id, String taskType, String status, String payloadJson, String resultJson,
                          String idempotencyKey, int attemptCount, int maxAttempts, String leaseOwner,
                          String leaseUntil, String heartbeatAt, String nextRunAt, String lastErrorCode,
                          String lastErrorMessage, String createdAt, String updatedAt) {
    }
}
