package com.aia.answer.web;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.ApiResponse;
import com.aia.answer.domain.LlmDtos;
import com.aia.answer.service.LlmProfileService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM 配置（契约 §6，文件驱动 v1.1）。
 *
 * <p>配置源是 {@code <UserData>/config/setting.json}：本控制器不直接碰文件，
 * 全部经 {@link LlmProfileService} → {@code ConfigService}（原子写 + 校验 + 热加载）。
 *
 * <p>所有响应都不包含明文密钥：读取只返回 {@code apiKeyMasked}/{@code apiKeyConfigured}，
 * 写入接口只接受密钥、不回读也不回显。唯一的例外是显式 {@code includeSecrets=true} 的导出，
 * 它同时会写审计事件。
 */
@RestController
@RequestMapping("/api/v1/settings/llm-profiles")
public class LlmProfileController {

    private final LlmProfileService service;

    public LlmProfileController(LlmProfileService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<LlmDtos.ProfileListResponse> list() {
        return ApiResponse.ok(service.list());
    }

    /** 新建档案：{@code {"profile": ProfileInput}} → {@code 201}（写 setting.json，原子替换）。 */
    @PostMapping
    public ResponseEntity<ApiResponse<LlmDtos.ProfileView>> create(
            @Valid @RequestBody LlmDtos.CreateProfileRequest request) {
        if (request == null || request.profile() == null) {
            throw ApiException.validation("请求体必须是 {profile: ProfileInput}", "profile");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(request.profile())));
    }

    /** 保存档案：请求体是 {@code ProfileInput}（{@code apiKey} 三态见契约 §6）。 */
    @PutMapping("/{id}")
    public ApiResponse<LlmDtos.ProfileView> update(@PathVariable String id,
                                                   @Valid @RequestBody LlmDtos.ProfileInput request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<LlmDtos.ProfileListResponse> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.ok(service.list());
    }

    /** 只更新该档案的 apiKey（写入 setting.json）；不落 OS 凭据存储，响应不回显密钥。 */
    @PostMapping("/{id}/credential")
    public ApiResponse<LlmDtos.ProfileView> setCredential(@PathVariable String id,
                                                          @Valid @RequestBody LlmDtos.CredentialRequest request) {
        return ApiResponse.ok(service.setCredential(id, request.apiKey()));
    }

    /** 清除该档案的 apiKey（等价于 PUT 时 {@code apiKey: ""}）。 */
    @DeleteMapping("/{id}/credential")
    public ApiResponse<LlmDtos.ProfileView> deleteCredential(@PathVariable String id) {
        return ApiResponse.ok(service.clearCredential(id));
    }

    /** 纯校验：不写文件、不发网络请求。 */
    @PostMapping("/validate")
    public ApiResponse<LlmDtos.ValidationResponse> validate(@RequestBody(required = false) LlmDtos.ValidateRequest request) {
        return ApiResponse.ok(service.validate(request == null ? null : request.config()));
    }

    /** 连接测试：{@code {profile}} 或 {@code {profileId}}，短超时最小请求，不回传密钥与请求头。 */
    @PostMapping("/test-connection")
    public ApiResponse<LlmDtos.TestConnectionResponse> testConnection(
            @RequestBody(required = false) LlmDtos.TestConnectionRequest request) {
        return ApiResponse.ok(service.testConnection(request == null
                ? new LlmDtos.TestConnectionRequest(null, null) : request));
    }

    /** 导入预览：只在进程内存中（TTL 10 分钟），不改 setting.json、不落任何表。 */
    @PostMapping("/import/preview")
    public ApiResponse<LlmDtos.ImportPreviewResponse> previewImport(
            @RequestBody(required = false) LlmDtos.ImportPreviewRequest request) {
        if (request == null || request.config() == null) {
            throw ApiException.validation("config 不能为空", "config");
        }
        return ApiResponse.ok(service.previewImport(request.config()));
    }

    /** 导入确认：应用变更并原子写入 setting.json；previewId 过期/不存在返回 {@code STATE_CONFLICT}。 */
    @PostMapping("/import/confirm")
    public ApiResponse<LlmDtos.ImportConfirmResponse> confirmImport(
            @Valid @RequestBody LlmDtos.ImportConfirmRequest request) {
        return ApiResponse.ok(service.confirmImport(request.previewId()));
    }

    /** 完整配置导出：默认不含明文密钥；{@code includeSecrets: true} 才含密钥且写审计。 */
    @PostMapping("/export")
    public ApiResponse<LlmDtos.ExportResponse> export(
            @RequestBody(required = false) LlmDtos.ExportRequest request) {
        return ApiResponse.ok(service.export(request == null ? null : request.includeSecrets()));
    }

    @PutMapping("/default")
    public ApiResponse<LlmDtos.ProfileListResponse> setDefault(
            @RequestBody(required = false) LlmDtos.DefaultProfileRequest request) {
        LlmDtos.DefaultProfileRequest effective = request == null
                ? new LlmDtos.DefaultProfileRequest(null, null) : request;
        return ApiResponse.ok(service.setDefault(effective.profileId(), effective.disableAi()));
    }

    /**
     * 手动重读 setting.json（契约 §6）。
     *
     * <p>校验失败返回 {@code 400 CONFIG_FILE_INVALID}（details 里带 schema path），
     * 内存缓存仍保留上一份有效配置。
     */
    @PostMapping("/reload")
    public ApiResponse<LlmDtos.ReloadResponse> reload() {
        return ApiResponse.ok(service.reload());
    }
}
