/**
 * 契约 §6 LLM 配置（文件驱动 v1.1）+ `contracts/json-schema/setting.schema.json`。
 *
 * 配置源是 `<UserData>/config/setting.json`（唯一权威），不再是 SQLite + OS 凭据存储：
 *  - 没有 `apiKeyRef` / `credentialRef` / `credentialConfigured` / `credentialBackend`；
 *  - `ProfileView.apiKeyMasked` 有值时恒为 `"******"`，绝不回显明文；
 *  - 保存 profile 时 `apiKey` 有三态语义（见 `LlmProfileInput.apiKey`）。
 */
import type { AiServiceStatus } from './system';

/** Provider 白名单，与 `setting.schema.json` `$defs.profile.provider.enum` 一致 */
export const LLM_PROVIDERS = ['openai-compatible', 'ollama'] as const;
export type LlmProvider = (typeof LLM_PROVIDERS)[number];

/** `ProfileView.apiKeyMasked` 的固定掩码值（契约 §6，绝不回显明文） */
export const LLM_API_KEY_MASK = '******';

/** `setting.json` 的 `schemaVersion`（唯一支持值） */
export const SETTING_SCHEMA_VERSION = 1;

/** `configSource` 的固定取值（契约 §6/§8） */
export const SETTING_CONFIG_SOURCE = 'setting.json';

/**
 * 档案参数（`setting.schema.json` `$defs.profile.properties.parameters`）。
 * `temperature` 属于这里，不是 profile 级字段（服务端 `ProfileInput` 无 params 之外的 temperature）。
 */
export interface LlmParameters {
  temperature?: number;
  topP?: number;
  maxTokens?: number;
  stream?: boolean;
}

/**
 * 契约 §6 `ProfileView`（`GET /settings/llm-profiles` 列表项，不含明文密钥）。
 *
 * `temperature` **不是** profile 级字段：它与 `setting.schema.json` 的
 * `$defs.profile.properties.parameters` 一起放在 `parameters` 对象里（键 `temperature`，0-2，选填），
 * 服务端 `ProfileView.parameters` 是 `Map<String,Object>`，原样回传。
 */
export interface LlmProfileView {
  id: string;
  name: string;
  provider: LlmProvider;
  baseUrl: string;
  model: string;
  /** 有值时恒为 `"******"`，未配置为 `null` */
  apiKeyMasked: string | null;
  apiKeyConfigured: boolean;
  timeoutSeconds: number;
  connectTimeoutSeconds: number;
  allowLocalNetwork: boolean;
  parameters: LlmParameters;
  isDefault: boolean;
}

/**
 * 契约 §6 `ProfileInput`（新建/更新 profile 的请求体）。
 *
 * `apiKey` 三态：
 *  - **字段省略**：保持 setting.json 中的原值不变；
 *  - `""` 或 `null`：清除该档案的 apiKey；
 *  - 非空字符串：替换为新密钥。
 *
 * 服务端只在**新建**时包一层 `{ profile: ProfileInput }`；**更新**（PUT）与其他写入接口
 * 直接以本对象作为请求体。
 */
export interface LlmProfileInput {
  id: string;
  name: string;
  provider: LlmProvider;
  baseUrl: string;
  model: string;
  apiKey?: string | null;
  timeoutSeconds?: number | null;
  connectTimeoutSeconds?: number | null;
  allowLocalNetwork?: boolean | null;
  parameters?: LlmParameters | null;
}

/** 契约 §6 `warnings[]` 元素：热加载/校验问题（非法时保留上一份有效配置，服务照常可用） */
export interface LlmConfigWarning {
  code: string;
  message: string;
  path?: string | null;
}

/** setting.json 内的 profile 条目（`schemaVersion` / `llm` 段） */
export interface LlmSettingProfile {
  id: string;
  name: string;
  provider: LlmProvider;
  baseUrl: string;
  model: string;
  /** 文件中唯一的明文密钥字段；默认导出时置 null */
  apiKey?: string | null;
  timeoutSeconds?: number | null;
  connectTimeoutSeconds?: number | null;
  allowLocalNetwork?: boolean | null;
  /** provider 参数（含 temperature / topP / maxTokens / stream） */
  parameters?: LlmParameters | null;
}

/** `setting.json` 文档（导出/导入的对象；`additionalProperties=false`） */
export interface LlmSettingFile {
  schemaVersion: number;
  application?: Record<string, unknown> | null;
  llm: {
    defaultProfileId?: string | null;
    profiles: LlmSettingProfile[];
  };
}

/**
 * 配置来源与热加载状态（`GET /settings/llm-profiles` 的顶层字段）。
 * `configSource` 固定为 `setting.json`；`configPath` 是该文件的绝对路径。
 */
export interface LlmConfigState {
  /**
   * 配置修订号 = setting.json 字节的 SHA-256（服务端 `String`）；
   * 文件不存在时为 `null`。前后端一致按字符串处理，不要当数字。
   */
  configRevision: string | null;
  configSource: string;
  configPath: string;
  /** 当前内存快照的加载时刻（UTC ISO-8601） */
  configUpdatedAt: string | null;
  warnings: LlmConfigWarning[];
}

/** `GET /api/v1/settings/llm-profiles` → data */
export interface LlmProfilesResponse extends LlmConfigState {
  profiles: LlmProfileView[];
  defaultProfileId: string | null;
}

/** `POST /api/v1/settings/llm-profiles/reload` → data */
export interface LlmConfigReloadResponse {
  configRevision: string | null;
  warnings: LlmConfigWarning[];
}

/** `POST /api/v1/settings/llm-profiles/validate` 的 `errors[]` */
export interface LlmValidateError {
  path: string;
  message: string;
}

/** `POST /api/v1/settings/llm-profiles/validate`（Java `ValidationResponse`） */
export interface LlmValidateResponse {
  valid: boolean;
  errors: LlmValidateError[];
  summary: {
    profileCount: number;
    providers: string[];
  };
}

/** `POST /api/v1/settings/llm-profiles/test-connection` */
export const LLM_TEST_CATEGORIES = [
  'ok',
  'dns',
  'tls',
  'auth',
  'model_not_found',
  'quota',
  'timeout',
  'protocol',
  'blocked',
  'unavailable'
] as const;
export type LlmTestCategory = (typeof LLM_TEST_CATEGORIES)[number];

/**
 * `profile` 形式用表单里的未保存值（含明文 apiKey，仅走请求体）；
 * `profileId` 形式由服务端从 setting.json 取密钥。两者互斥。
 */
export interface LlmTestConnectionRequest {
  profile?: LlmProfileInput;
  profileId?: string;
}

export interface LlmTestConnectionResponse {
  ok: boolean;
  category: LlmTestCategory;
  latencyMs: number;
  httpStatus: number | null;
  modelEcho: string | null;
  message: string;
  suggestion: string | null;
}

/** `POST /api/v1/settings/llm-profiles/import/preview` */
export type LlmImportAction = 'create' | 'update' | 'unchanged' | 'conflict';

export interface LlmImportChange {
  action: LlmImportAction;
  profileId: string | null;
  name: string | null;
  fields: string[];
}

/**
 * 预览响应的 `summary` 是 `ValidationSummary`（`profileCount` + `providers`），
 * **不是**按 action 分类的计数；分类计数由前端从 `changes[]` 自行汇总。
 * `previewId` 10 分钟过期；预览**只在内存**，不修改 setting.json。
 */
export interface LlmImportPreviewResponse {
  previewId: string;
  summary: {
    profileCount: number;
    providers: string[];
  };
  changes: LlmImportChange[];
  errors: LlmValidateError[];
  expiresAt: string;
}

/** 按 action 汇总 `changes[]`，供 UI 展示 create/update/unchanged/conflict 明细 */
export interface LlmImportChangeCounts {
  create: number;
  update: number;
  unchanged: number;
  conflict: number;
  total: number;
}

export function countImportChanges(changes: LlmImportChange[]): LlmImportChangeCounts {
  const counts: LlmImportChangeCounts = {
    create: 0,
    update: 0,
    unchanged: 0,
    conflict: 0,
    total: changes.length
  };
  for (const change of changes) {
    if (change.action === 'create') counts.create += 1;
    else if (change.action === 'update') counts.update += 1;
    else if (change.action === 'unchanged') counts.unchanged += 1;
    else if (change.action === 'conflict') counts.conflict += 1;
  }
  return counts;
}

/** `POST /api/v1/settings/llm-profiles/import/confirm`（应用变更并原子写入 setting.json） */
export interface LlmImportConfirmResponse {
  created: number;
  updated: number;
  unchanged: number;
  /** 写入后的新修订号（SHA-256 字符串） */
  configRevision: string | null;
}

/**
 * `POST /api/v1/settings/llm-profiles/export` →
 * `data.config` 是与 setting.json 同构的文档，`includeSecrets=false` 时每个 profile 的
 * `apiKey` 置 `null`；`includeSecrets=true` 时响应带 `containsSecrets: true`，
 * `suggestedFileName` 为 `setting-with-keys.json`（否则 `setting.json`）。
 * 任何情况下都不含内部令牌。
 */
export interface LlmExportResponse {
  config: LlmSettingFile;
  /** 本次是否请求了明文密钥 */
  includeSecrets?: boolean;
  /** 结果中是否真的含明文密钥（含密钥导出必须留审计） */
  containsSecrets?: boolean;
  suggestedFileName?: string;
}

/** `PUT /api/v1/settings/llm-profiles/default` body */
export interface LlmSetDefaultPayload {
  profileId: string | null;
  /** profileId 为 null 时必须为 true */
  disableAi?: boolean;
}

/** 面板展示用的 AI 可用性（复用 §1 的 AiServiceStatus） */
export type LlmAiServiceStatus = AiServiceStatus;
