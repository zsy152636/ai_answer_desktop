/**
 * LLM 配置档案 store（契约 §6 v1.1，文件驱动 + 热加载）。
 *
 * 配置源是 `<UserData>/config/setting.json`：没有「绑定/解绑凭据」，`apiKey` 随 profile 一起保存；
 * 服务端保留上一份有效配置，热加载失败只体现在 `warnings` 与 `reload` 的错误里。
 */
import { defineStore } from 'pinia';
import { computed, ref, shallowRef } from 'vue';

import * as llmProfilesApi from '@/api/llmProfiles';
import {
  countImportChanges,
  type LlmConfigReloadResponse,
  type LlmConfigWarning,
  type LlmExportResponse,
  type LlmImportChangeCounts,
  type LlmImportConfirmResponse,
  type LlmImportPreviewResponse,
  type LlmProfileInput,
  type LlmProfileView,
  type LlmSettingFile,
  type LlmTestConnectionResponse,
  type LlmValidateError
} from '@/types/llm';
import { configValidationIssues, describeError, type UiErrorInfo } from '@/utils/error';
import { validateLlmConfigFile } from '@/utils/llmConfig';

export const useLlmProfilesStore = defineStore('llmProfiles', () => {
  const profiles = shallowRef<LlmProfileView[]>([]);
  const defaultProfileId = ref<string | null>(null);
  /** 配置来源与热加载状态（契约 §6） */
  const configRevision = ref<string | null>(null);
  const configSource = ref('');
  const configPath = ref('');
  const configUpdatedAt = ref<string | null>(null);
  const warnings = shallowRef<LlmConfigWarning[]>([]);
  const loading = ref(false);
  const saving = ref(false);
  const reloading = ref(false);
  const error = ref<UiErrorInfo | null>(null);
  /** 最近一次「重新加载配置」失败的 schema path 明细（CONFIG_FILE_INVALID） */
  const reloadIssues = ref<string[]>([]);
  const loaded = ref(false);

  const preview = shallowRef<LlmImportPreviewResponse | null>(null);
  const previewLoading = ref(false);
  const localValidationErrors = ref<LlmValidateError[]>([]);

  const testingId = ref<string | null>(null);
  const testResult = ref<LlmTestConnectionResponse | null>(null);
  const testError = ref<UiErrorInfo | null>(null);

  const isEmpty = computed(() => loaded.value && profiles.value.length === 0);
  const defaultProfile = computed(
    () => profiles.value.find((profile) => profile.id === defaultProfileId.value) ?? null
  );

  /**
   * 预览响应的 `summary` 是 `ValidationSummary`（profileCount + providers），
   * 按 action 的分类计数需从 `changes[]` 汇总。
   */
  const previewCounts = computed<LlmImportChangeCounts | null>(() =>
    preview.value ? countImportChanges(preview.value.changes) : null
  );

  /** 把列表/默认切换/删除响应里的配置状态回填（三者响应形状一致） */
  function applyState(response: {
    profiles: LlmProfileView[];
    defaultProfileId: string | null;
    configRevision: string | null;
    configSource: string;
    configPath: string;
    configUpdatedAt: string | null;
    warnings: LlmConfigWarning[];
  }): void {
    profiles.value = response.profiles;
    defaultProfileId.value = response.defaultProfileId;
    configRevision.value = response.configRevision;
    configSource.value = response.configSource;
    configPath.value = response.configPath;
    configUpdatedAt.value = response.configUpdatedAt;
    warnings.value = response.warnings ?? [];
  }

  async function load(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const response = await llmProfilesApi.listLlmProfiles();
      applyState(response);
      loaded.value = true;
    } catch (caught) {
      error.value = describeError(caught);
      profiles.value = [];
      warnings.value = [];
      loaded.value = false;
    } finally {
      loading.value = false;
    }
  }

  /** 保存档案；`payload.apiKey` 三态由调用方决定是否带上该字段 */
  async function saveProfile(id: string | null, payload: LlmProfileInput): Promise<LlmProfileView> {
    saving.value = true;
    try {
      const saved = id
        ? await llmProfilesApi.updateLlmProfile(id, payload)
        : await llmProfilesApi.createLlmProfile(payload);
      await load();
      return saved;
    } finally {
      saving.value = false;
    }
  }

  /** 删除档案；服务端直接返回删除后的最新列表与配置状态，无需二次 GET */
  async function removeProfile(id: string): Promise<void> {
    applyState(await llmProfilesApi.deleteLlmProfile(id));
  }

  /**
   * 重新加载配置（`POST /settings/llm-profiles/reload`）。
   * 失败时返回 false 并把 `CONFIG_FILE_INVALID` 的 schema path 放进 `reloadIssues`，
   * 缓存仍保留上一份有效配置（服务未中断），调用方据此提示文件位置。
   */
  async function reloadConfig(): Promise<LlmConfigReloadResponse | null> {
    reloading.value = true;
    reloadIssues.value = [];
    try {
      const response = await llmProfilesApi.reloadLlmConfig();
      configRevision.value = response.configRevision;
      warnings.value = response.warnings ?? [];
      await load();
      return response;
    } catch (caught) {
      reloadIssues.value = configValidationIssues(caught);
      error.value = describeError(caught);
      return null;
    } finally {
      reloading.value = false;
    }
  }

  /**
   * 切换默认档案。
   * 后端规则：`profileId` 为 null 时必须显式带 `disableAi: true`（否则拒绝）；
   * 指向具体档案时不能带 `disableAi`。
   */
  async function setDefault(profileId: string | null): Promise<void> {
    applyState(
      await llmProfilesApi.setDefaultLlmProfile(
        profileId === null ? { profileId: null, disableAi: true } : { profileId }
      )
    );
  }

  /** 用未保存的表单值测试连接（明文密钥只走请求体，不落盘、不回显） */
  async function testConnection(profile: LlmProfileInput): Promise<void> {
    testingId.value = profile.id;
    testResult.value = null;
    testError.value = null;
    try {
      testResult.value = await llmProfilesApi.testLlmConnection({ profile });
    } catch (caught) {
      testError.value = describeError(caught);
    } finally {
      testingId.value = null;
    }
  }

  /** 使用已保存档案测试连接（密钥由服务端从 setting.json 读取，不回传前端） */
  async function testStoredProfile(profileId: string): Promise<void> {
    testingId.value = profileId;
    testResult.value = null;
    testError.value = null;
    try {
      testResult.value = await llmProfilesApi.testLlmConnection({ profileId });
    } catch (caught) {
      testError.value = describeError(caught);
    } finally {
      testingId.value = null;
    }
  }

  function clearTestResult(): void {
    testResult.value = null;
    testError.value = null;
  }

  /** 本地预检 + 后端校验（错误逐条展示）；两者都不写文件 */
  async function validateConfig(config: LlmSettingFile): Promise<LlmValidateError[]> {
    const local = validateLlmConfigFile(config);
    localValidationErrors.value = local.errors;
    if (!local.valid) return local.errors;
    try {
      const remote = await llmProfilesApi.validateLlmConfig(config);
      localValidationErrors.value = remote.errors;
      return remote.errors;
    } catch (caught) {
      const info = describeError(caught);
      localValidationErrors.value = Object.entries(info.fieldErrors).map(([path, message]) => ({
        path,
        message
      }));
      if (localValidationErrors.value.length === 0) {
        localValidationErrors.value = [{ path: '', message: info.text }];
      }
      return localValidationErrors.value;
    }
  }

  /** 导入预览：只在内存中预览，不改动 setting.json */
  async function previewImport(config: unknown): Promise<LlmImportPreviewResponse | null> {
    previewLoading.value = true;
    try {
      preview.value = await llmProfilesApi.previewLlmImport(config);
      return preview.value;
    } finally {
      previewLoading.value = false;
    }
  }

  /** 应用导入：返回 `{ created, updated, unchanged, configRevision }` */
  async function confirmImport(previewId: string): Promise<LlmImportConfirmResponse> {
    const applied = await llmProfilesApi.confirmLlmImport(previewId);
    preview.value = null;
    await load();
    return applied;
  }

  /**
   * 完整配置导出（与 setting.json 同构）。
   * 默认不含密钥；`includeSecrets: true` 时响应带 `containsSecrets: true`，由页面显式提示风险。
   */
  async function exportConfig(includeSecrets = false): Promise<LlmExportResponse> {
    return llmProfilesApi.exportLlmConfig(includeSecrets);
  }

  function clearPreview(): void {
    preview.value = null;
  }

  function clearReloadIssues(): void {
    reloadIssues.value = [];
  }

  return {
    profiles,
    defaultProfileId,
    configRevision,
    configSource,
    configPath,
    configUpdatedAt,
    warnings,
    loading,
    saving,
    reloading,
    error,
    reloadIssues,
    loaded,
    isEmpty,
    defaultProfile,
    preview,
    previewLoading,
    previewCounts,
    localValidationErrors,
    testingId,
    testResult,
    testError,
    load,
    saveProfile,
    removeProfile,
    reloadConfig,
    setDefault,
    testConnection,
    testStoredProfile,
    clearTestResult,
    validateConfig,
    previewImport,
    confirmImport,
    exportConfig,
    clearPreview,
    clearReloadIssues
  };
});
