package com.aia.answer.repository;

import com.aia.answer.common.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 应用元数据与审计事件。
 *
 * <p>审计事件是错题掌握状态、配置切换等可追溯性的落地表（开发文档 §5.3、§14）。
 */
@Repository
public class MetaRepository {

    private final JdbcTemplate jdbc;

    public MetaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String key) {
        List<String> values = jdbc.query("SELECT value FROM app_meta WHERE key = ?",
                (rs, rowNum) -> rs.getString(1), key);
        return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.get(0));
    }

    public void put(String key, String value, String now) {
        jdbc.update("INSERT INTO app_meta(key, value, updated_at) VALUES (?,?,?) "
                + "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
                key, value, now);
    }

    public void audit(String eventType, String entityType, String entityId, String detailJson, String now) {
        jdbc.update("INSERT INTO audit_event(id, event_type, entity_type, entity_id, detail_json, created_at) "
                + "VALUES (?,?,?,?,?,?)",
                Ids.of("aud"), eventType, entityType, entityId, detailJson, now);
    }

    public List<String> recentAudit(int limit) {
        return jdbc.query("SELECT event_type FROM audit_event ORDER BY created_at DESC LIMIT ?",
                (rs, rowNum) -> rs.getString(1), limit);
    }
}
