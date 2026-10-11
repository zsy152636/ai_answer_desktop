/**
 * 契约 §6 LLM 配置（文件驱动 v1.1，`<UserData>/config/setting.json`）。
 *
 * 没有「绑定/解绑凭据」接口：`apiKey` 随 profile 一起读写（`ProfileInput.apiKey` 三态语义）。
 * 只依赖 `./client` 的 `http`。
 */
import { http } from './client';
import type {
  LlmConfigReloadResponse,
  LlmExportResponse,
  LlmImportConfirmResponse,
  LlmImportPreviewResponse,
  LlmProfileInput,
  LlmProfilesResponse,
  LlmProfileView,
  LlmSetDefaultPayload,
  LlmSettingFile,
  LlmTestConnectionRequest,
  LlmTestConnectionResponse,
  LlmValidateResponse
} from '@/types/llm';

/** 列表：附带配置来源（`configSource`/`configPath`/`configRevision`/`configUpdatedAt`）与 `warnings` */
export function listLlmProfiles(): Promise<LlmProfilesResponse> {
  return http.get<LlmProfilesResponse>('/settings/llm-profiles');
}

/** 新建档案（服务端返回 HTTP 201）；请求体为 `{ "profile": ProfileInput }` */
export function createLlmProfile(payload: LlmProfileInput): Promise<LlmProfileView> {
  return http.post<LlmProfileView>('/settings/llm-profiles', { profile: payload });
}

/**
 * 保存档案：请求体就是 **`ProfileInput` 本身**（不包 `profile` 字段，与新建不同）。
 * `apiKey` 三态：省略=保持、`""`/null=清除、非空=替换。
 */
export function updateLlmProfile(id: string, payload: LlmProfileInput): Promise<LlmProfileView> {
  return http.put<LlmProfileView>(`/settings/llm-profiles/${encodeURIComponent(id)}`, payload);
}

/** 删除档案；返回更新后的配置状态（含删除后的最新列表） */
export function deleteLlmProfile(id: string): Promise<LlmProfilesResponse> {
  return http.delete<LlmProfilesResponse>(`/settings/llm-profiles/${encodeURIComponent(id)}`);
}

/** 纯校验：不写文件、不发网络请求 */
export function validateLlmConfig(config: LlmSettingFile): Promise<LlmValidateResponse> {
  return http.post<LlmValidateResponse>('/settings/llm-profiles/validate', { config });
}

/** 连接测试：`profileId` 形式时密钥由服务端从 setting.json 取 */
export function testLlmConnection(
  payload: LlmTestConnectionRequest
): Promise<LlmTestConnectionResponse> {
  return http.post<LlmTestConnectionResponse>('/settings/llm-profiles/test-connection', payload);
}

/** 导入预览：只在内存中预览，**不修改** setting.json；`previewId` 10 分钟过期 */
export function previewLlmImport(config: unknown): Promise<LlmImportPreviewResponse> {
  return http.post<LlmImportPreviewResponse>('/settings/llm-profiles/import/preview', { config });
}

/** 应用导入：原子写入 setting.json；`previewId` 过期/不存在返回 `STATE_CONFLICT` */
export function confirmLlmImport(previewId: string): Promise<LlmImportConfirmResponse> {
  return http.post<LlmImportConfirmResponse>('/settings/llm-profiles/import/confirm', {
    previewId
  });
}

/**
 * 完整配置导出（与 setting.json 同构）。
 * 默认 `includeSecrets: false` → 每个 profile 的 `apiKey` 置 null；
 * `includeSecrets: true` 才包含明文密钥，响应带 `containsSecrets: true`，建议文件名
 * `setting-with-keys.json`，并写审计事件。任何情况下都不含内部令牌。
 */
export function exportLlmConfig(includeSecrets = false): Promise<LlmExportResponse> {
  return http.post<LlmExportResponse>('/settings/llm-profiles/export', { includeSecrets });
}

export function setDefaultLlmProfile(payload: LlmSetDefaultPayload): Promise<LlmProfilesResponse> {
  return http.put<LlmProfilesResponse>('/settings/llm-profiles/default', payload);
}

/**
 * 手动重读 setting.json 并刷新缓存 → `{ configRevision, warnings }`。
 * 校验失败返回 `400 CONFIG_FILE_INVALID`（缓存仍保留上一份有效配置，服务未中断）。
 */
export function reloadLlmConfig(): Promise<LlmConfigReloadResponse> {
  return http.post<LlmConfigReloadResponse>('/settings/llm-profiles/reload');
}
