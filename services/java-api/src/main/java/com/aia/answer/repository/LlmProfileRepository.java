package com.aia.answer.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 旧 LLM 配置表（{@code llm_profile} / {@code llm_config_state}）的访问入口。
 *
 * <p><b>v1.1 起运行期不再使用</b>：LLM 配置与密钥的唯一权威是
 * {@code <UserData>/config/setting.json}（见 {@code ConfigService}）。
 * 本仓储现在只剩两个用途：
 * <ol>
 *   <li>首次启动一次性迁移的<b>读取来源</b>（{@code LegacyLlmConfigMigrator#list()}）；</li>
 *   <li>迁移相关测试构造旧数据。</li>
 * </ol>
 * 表结构保留（不 DROP），字段 {@code api_key_ref} 只是旧凭据的引用，绝不是明文密钥。
 */
@Repository
public class LlmProfileRepository {

    private final JdbcTemplate jdbc;

    public LlmProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProfileRow> list() {
        return jdbc.query("SELECT id, name, provider, base_url, model, api_key_ref, timeout_seconds, "
                + "connect_timeout_seconds, allow_local_network, parameters_json, credential_configured, is_default, "
                + "created_at, updated_at FROM llm_profile ORDER BY is_default DESC, updated_at DESC", MAPPER);
    }

    public Optional<ProfileRow> findById(String id) {
        List<ProfileRow> rows = jdbc.query("SELECT id, name, provider, base_url, model, api_key_ref, timeout_seconds, "
                + "connect_timeout_seconds, allow_local_network, parameters_json, credential_configured, is_default, "
                + "created_at, updated_at FROM llm_profile WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<ProfileRow> findDefault() {
        List<ProfileRow> rows = jdbc.query("SELECT id, name, provider, base_url, model, api_key_ref, timeout_seconds, "
                + "connect_timeout_seconds, allow_local_network, parameters_json, credential_configured, is_default, "
                + "created_at, updated_at FROM llm_profile WHERE is_default = 1 LIMIT 1", MAPPER);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void insert(ProfileRow row) {
        jdbc.update("INSERT INTO llm_profile(id, name, provider, base_url, model, api_key_ref, timeout_seconds, "
                        + "connect_timeout_seconds, allow_local_network, parameters_json, credential_configured, "
                        + "is_default, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.id(), row.name(), row.provider(), row.baseUrl(), row.model(), row.apiKeyRef(),
                row.timeoutSeconds(), row.connectTimeoutSeconds(), row.allowLocalNetwork() ? 1 : 0,
                row.parametersJson(), row.credentialConfigured() ? 1 : 0, row.isDefault() ? 1 : 0,
                row.createdAt(), row.updatedAt());
    }

    public void update(ProfileRow row) {
        jdbc.update("UPDATE llm_profile SET name = ?, provider = ?, base_url = ?, model = ?, api_key_ref = ?, "
                        + "timeout_seconds = ?, connect_timeout_seconds = ?, allow_local_network = ?, "
                        + "parameters_json = ?, credential_configured = ?, updated_at = ? WHERE id = ?",
                row.name(), row.provider(), row.baseUrl(), row.model(), row.apiKeyRef(), row.timeoutSeconds(),
                row.connectTimeoutSeconds(), row.allowLocalNetwork() ? 1 : 0, row.parametersJson(),
                row.credentialConfigured() ? 1 : 0, row.updatedAt(), row.id());
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM llm_profile WHERE id = ?", id);
    }

    public void clearDefaults() {
        jdbc.update("UPDATE llm_profile SET is_default = 0");
    }

    public void setDefault(String id) {
        jdbc.update("UPDATE llm_profile SET is_default = 1, updated_at = ? WHERE id = ?", now(), id);
    }

    public void setCredentialConfigured(String id, boolean configured, String apiKeyRef, String updatedAt) {
        jdbc.update("UPDATE llm_profile SET credential_configured = ?, api_key_ref = ?, updated_at = ? WHERE id = ?",
                configured ? 1 : 0, apiKeyRef, updatedAt, id);
    }

    public void ensureStateRow(String now) {
        jdbc.update("INSERT INTO llm_config_state(id, default_profile_id, config_revision, updated_at) "
                + "VALUES (1, NULL, 0, ?) ON CONFLICT(id) DO NOTHING", now);
    }

    public StateRow state() {
        List<StateRow> rows = jdbc.query("SELECT default_profile_id, config_revision, updated_at FROM llm_config_state "
                + "WHERE id = 1", (rs, rowNum) -> new StateRow(rs.getString(1), rs.getInt(2), rs.getString(3)));
        return rows.isEmpty() ? new StateRow(null, 0, now()) : rows.get(0);
    }

    public void setState(String defaultProfileId, String now) {
        jdbc.update("UPDATE llm_config_state SET default_profile_id = ?, updated_at = ? WHERE id = 1",
                defaultProfileId, now);
    }

    public int incrementRevision(String now) {
        jdbc.update("UPDATE llm_config_state SET config_revision = config_revision + 1, updated_at = ? WHERE id = 1", now);
        return state().configRevision();
    }

    // 说明（v1.1）：这里刻意不再提供 llm_import_preview 的任何读写方法。
    // 导入预览已改为进程内存 + TTL 10 分钟（LlmProfileService），旧表保留结构但运行期不再使用；
    // 删掉 DAO 方法是「不再读写在代码层面不可发生」的保证。

    private String now() {
        return java.time.format.DateTimeFormatter.ISO_INSTANT
                .format(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    private static final RowMapper<ProfileRow> MAPPER = (rs, rowNum) -> new ProfileRow(
            rs.getString("id"), rs.getString("name"), rs.getString("provider"), rs.getString("base_url"),
            rs.getString("model"), rs.getString("api_key_ref"), rs.getInt("timeout_seconds"),
            rs.getInt("connect_timeout_seconds"), rs.getInt("allow_local_network") != 0,
            rs.getString("parameters_json"), rs.getInt("credential_configured") != 0, rs.getInt("is_default") != 0,
            rs.getString("created_at"), rs.getString("updated_at"));

    public record ProfileRow(String id, String name, String provider, String baseUrl, String model, String apiKeyRef,
                             int timeoutSeconds, int connectTimeoutSeconds, boolean allowLocalNetwork,
                             String parametersJson, boolean credentialConfigured, boolean isDefault,
                             String createdAt, String updatedAt) {
    }

    public record StateRow(String defaultProfileId, int configRevision, String updatedAt) {
    }
}
