package com.aia.answer.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 幂等记录（契约 §0.4）：同键同载荷返回首次结果，同键不同载荷返回 IDEMPOTENCY_CONFLICT。
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Record> find(String key) {
        List<Record> rows = jdbc.query("SELECT key, scope, request_hash, status_code, response_json, created_at, "
                + "expires_at FROM idempotency_record WHERE key = ?",
                (rs, rowNum) -> new Record(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)), key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void save(String key, String scope, String requestHash, int statusCode, String responseJson,
                     String createdAt, String expiresAt) {
        jdbc.update("INSERT INTO idempotency_record(key, scope, request_hash, status_code, response_json, created_at, "
                        + "expires_at) VALUES (?,?,?,?,?,?,?) "
                        + "ON CONFLICT(key) DO NOTHING",
                key, scope, requestHash, statusCode, responseJson, createdAt, expiresAt);
    }

    public int deleteExpired(String now) {
        return jdbc.update("DELETE FROM idempotency_record WHERE expires_at < ?", now);
    }

    public record Record(String key, String scope, String requestHash, int statusCode, String responseJson,
                         String createdAt, String expiresAt) {
    }
}
