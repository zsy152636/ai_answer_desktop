package com.aia.answer.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * LLM 配置传输对象（契约 api-contract.md §6，文件驱动 v1.1）。
 *
 * <p>安全边界：本类只承载 {@code apiKeyMasked}（有值时恒为 {@code "******"}）与
 * {@code apiKeyConfigured} 这类「是否已配置」的布尔事实；
 * 唯一的明文入口是 {@link ProfileInput#apiKey()} 与含密钥导出（{@link ExportResponse}），
 * 它们只允许流向 setting.json 与显式请求了密钥的调用方，绝不进入日志、异常消息或审计事件。
 */
public final class LlmDtos {

    private LlmDtos() {
    }

    /**
     * 档案视图（契约 §6 {@code ProfileView}）。
     *
     * <p>刻意不再返回 {@code apiKeyRef} / {@code credentialConfigured} / {@code updatedAt}：
     * 密钥不再有「引用」概念（直接存在 setting.json 的 {@code apiKey} 字段），
     * 是否已配置由 {@code apiKeyConfigured} 表达。
     *
     * @param temperature      采样温度；Schema 允许 0–2，未设置时为 {@code null}
     *                         （配置文件里就是 profile 级 {@code temperature}，不再借道 {@code parameters}）
     * @param apiKeyConfigured 该档案在 setting.json 中是否写了非空 {@code apiKey}
     * @param isDefault        是否当前默认档案（等价于文件里的 {@code llm.defaultProfileId}）
     */
    public record ProfileView(
            String id,
            String name,
            String provider,
            String baseUrl,
            String model,
            String apiKeyMasked,
            boolean apiKeyConfigured,
            Double temperature,
            int timeoutSeconds,
            int connectTimeoutSeconds,
            boolean allowLocalNetwork,
            Map<String, Object> parameters,
            boolean isDefault) {
    }

    /**
     * 档案输入（契约 §6 {@code ProfileInput}）。
     *
     * <p><b>{@code apiKey} 三态</b>（必须是严格的三种语义，缺一不可）：
     * <ol>
     *   <li>字段<b>省略</b>（{@code null} 引用）= 保持原值，绝不覆盖文件里已有的密钥；</li>
     *   <li>{@code ""} 或 {@code null}（JSON null，反序列化为 {@code NullNode}）= 清除该档案的密钥；</li>
     *   <li>非空字符串 = 替换（先 {@code trim}，trim 后为空同样按「清除」处理）。</li>
     * </ol>
     * 用 {@link JsonNode} 而不是 {@code String} 承载，正是为了区分「省略」与「显式 null」——
     * 用 {@code String} 时两者都会变成 {@code null}，三态就不成立。
     *
     * @param temperature 采样温度（profile 级，Schema 0–2）；{@code null} = 保持原值（新建时不写）
     * @param parameters  供应商扩展参数；{@code null} 视为空对象（整体替换，不做深合并）
     * @param isDefault   仅为兼容「回显整个 ProfileView 再提交」的客户端而接受，服务端忽略它：
     *                    默认档案只能通过 {@code PUT /default} 切换
     */
    public record ProfileInput(
            @Size(max = 64, message = "id 最长 64 字符")
            @Pattern(regexp = "^$|^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$", message = "id 只允许字母、数字、点、下划线与连字符")
            String id,
            @NotBlank(message = "名称不能为空") @Size(max = 64, message = "名称最长 64 字符") String name,
            @NotBlank(message = "provider 不能为空") String provider,
            @NotBlank(message = "baseUrl 不能为空") @Size(max = 512, message = "baseUrl 最长 512 字符") String baseUrl,
            @NotBlank(message = "model 不能为空") @Size(max = 128, message = "model 最长 128 字符") String model,
            JsonNode apiKey,
            Double temperature,
            Integer timeoutSeconds,
            Integer connectTimeoutSeconds,
            Boolean allowLocalNetwork,
            Map<String, Object> parameters,
            Boolean isDefault) {
    }

    /** 新建档案请求体：{@code {"profile": ProfileInput}}。 */
    public record CreateProfileRequest(@Valid ProfileInput profile) {
    }

    /**
     * 热加载/校验问题（契约 §6 列表响应的 {@code warnings} 元素）。
     *
     * @param code    {@code CONFIG_FILE_INVALID}（Schema/跨字段校验失败）或
     *                {@code CONFIG_JSON_INVALID}（JSON 语法错误）或 {@code CONFIG_WARNING}
     * @param message 可读原因；来自配置基础层，其中不含任何密钥内容
     * @param path    从消息里摘出的 schema path（如 {@code $.llm.profiles[0].provider}），无则为 null
     */
    public record ConfigWarning(String code, String message, String path) {
    }

    /**
     * 档案列表（契约 §6）。
     *
     * @param configRevision 当前配置修订号 = setting.json 字节的 SHA-256；文件不存在时为 null
     * @param configSource   固定 {@code setting.json}
     * @param configPath     setting.json 的绝对路径
     * @param configUpdatedAt 当前内存快照的加载时刻（UTC ISO-8601）
     * @param warnings       热加载/校验提示；非法配置时缓存保留上一份有效配置并在这里说明原因
     */
    public record ProfileListResponse(
            List<ProfileView> profiles,
            String defaultProfileId,
            String configRevision,
            String configSource,
            String configPath,
            String configUpdatedAt,
            List<ConfigWarning> warnings) {
    }

    /** 只写密钥：{@code POST /{id}/credential}（写入 setting.json，不回显密钥）。 */
    public record CredentialRequest(
            @NotBlank(message = "apiKey 不能为空；清除密钥请调用 DELETE /{id}/credential")
            @Size(max = 4096, message = "apiKey 过长") String apiKey) {
    }

    /** 单条校验错误（契约 §6 validate 响应）。 */
    public record ValidationIssue(String path, String message) {
    }

    public record ValidationSummary(int profileCount, List<String> providers) {
    }

    public record ValidationResponse(boolean valid, List<ValidationIssue> errors, ValidationSummary summary) {
    }

    /** 纯校验请求：{@code {"config": <setting.json 或仅 llm 段>}}。 */
    public record ValidateRequest(JsonNode config) {
    }

    /**
     * 连接测试请求：给 {@code profile}（内联候选档案，可自带一次性 {@code apiKey}）
     * 或 {@code profileId}（从 setting.json 读取，含其 apiKey）。
     *
     * <p>已删除 {@code useStoredCredential} 之类旧参数：密钥来源现在只由这两个二选一的字段决定。
     */
    public record TestConnectionRequest(JsonNode profile, String profileId) {
    }

    /** 连接测试响应：不含请求头与密钥（契约 §6）。 */
    public record TestConnectionResponse(
            boolean ok,
            String category,
            long latencyMs,
            Integer httpStatus,
            String modelEcho,
            String message,
            String suggestion) {
    }

    /** 导入差异项：{@code action} ∈ create | update | unchanged | conflict。 */
    public record ChangeItem(String action, String profileId, String name, List<String> fields) {
    }

    /** 导入预览请求：{@code {"config": <setting.json 或旧 llm-config.json>}}。 */
    public record ImportPreviewRequest(JsonNode config) {
    }

    /**
     * 导入预览响应。预览只存在于进程内存中（TTL 10 分钟），不落任何表、不改任何文件。
     *
     * @param expiresAt 附加字段（契约未列但 UI 需要展示有效期）：过期时刻 UTC ISO-8601
     */
    public record ImportPreviewResponse(
            String previewId,
            ValidationSummary summary,
            List<ChangeItem> changes,
            List<ValidationIssue> errors,
            String expiresAt) {
    }

    public record ImportConfirmRequest(@NotBlank(message = "previewId 不能为空") String previewId) {
    }

    /** 导入确认结果；{@code configRevision} 是写入后的新修订号（SHA-256）。 */
    public record ImportConfirmResponse(int created, int updated, int unchanged, String configRevision) {
    }

    /** 导出请求：{@code includeSecrets} 默认 false（不含明文密钥）。 */
    public record ExportRequest(Boolean includeSecrets) {
    }

    /**
     * 完整配置导出响应（契约 §6）。
     *
     * @param config            与 setting.json 同构的文档；{@code includeSecrets=false} 时每个
     *                          profile 的 {@code apiKey} 显式置 {@code null}
     * @param includeSecrets    本次是否请求了明文密钥
     * @param containsSecrets   结果中是否真的含明文密钥（含密钥导出必须留审计）
     * @param suggestedFileName 建议文件名：含密钥为 {@code setting-with-keys.json}，否则 {@code setting.json}
     */
    public record ExportResponse(
            JsonNode config,
            boolean includeSecrets,
            boolean containsSecrets,
            String suggestedFileName) {
    }

    /** 切换默认档案：{@code profileId} 置 null 时必须显式 {@code disableAi=true}。 */
    public record DefaultProfileRequest(String profileId, Boolean disableAi) {
    }

    /** 手动重读 setting.json 的结果（契约 §6 reload）。 */
    public record ReloadResponse(String configRevision, List<ConfigWarning> warnings) {
    }
}
