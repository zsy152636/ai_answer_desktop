/** 统一错误展示：把 ApiError 转成可读文案与字段回填数据 */
import { ApiError, toApiError } from '@/api/client';
import type { ApiErrorCode } from '@/types/common';

/** 错误码 → 用户可读补充说明（契约 §0.3 客户端行为） */
const CODE_HINTS: Partial<Record<ApiErrorCode, string>> = {
  VALIDATION_ERROR: '请修正标出的字段后重试。',
  UNAUTHORIZED: '会话已失效，请点击「重试」重新获取运行时上下文。',
  ORIGIN_REJECTED: '请求来源被安全策略拒绝，本操作不会重试。',
  RESOURCE_NOT_FOUND: '数据可能已被删除，请刷新列表。',
  STATE_CONFLICT: '当前状态不允许该操作，请刷新后重试。',
  VERSION_CONFLICT: '数据已被其他修改更新，请重新载入最新版本。',
  IDEMPOTENCY_CONFLICT: '检测到同一幂等键的重复提交，请勿重复操作。',
  DB_BUSY: '数据库繁忙，请稍后重试。',
  DB_MIGRATION_FAILED: '数据库迁移失败，请按恢复路径处理。',
  CONFIG_JSON_INVALID: 'JSON 语法有误，请按提示的行列位置修正。',
  CONFIG_SCHEMA_UNSUPPORTED: '配置版本不受支持，请升级应用或使用兼容配置。',
  BASE_URL_BLOCKED: '目标地址被安全策略拒绝，请检查地址类别或开启「允许本地网络」。',
  CREDENTIAL_MISSING: '该档案未配置 apiKey，请在 LLM 设置里填写。',
  CONFIG_FILE_INVALID:
    'setting.json 校验失败，当前仍在使用上一份有效配置、服务未中断；请按 schema path 修正文件后点击「重新加载配置」。',
  LLM_AUTH_FAILED: '模型鉴权失败，请检查密钥与权限，配置未被覆盖。',
  LLM_CONNECT_TIMEOUT: '连接模型超时，请检查端点、网络与超时设置。',
  LLM_UNAVAILABLE: 'AI 能力暂不可用，主流程不受影响，成绩仍标记为待复核。',
  TASK_RETRY_EXHAUSTED: '任务重试次数已耗尽，可重试、取消或查看详情。',
  DOCUMENT_FORMAT_UNSUPPORTED: '首期只支持 .txt / .md 文本资料，PDF / DOCX 提取属阶段 7，请更换文件。',
  DOCUMENT_TOO_LARGE: '文件超过导入体积上限，请拆分或压缩后重试（不会自动重试）。',
  DOCUMENT_PARSE_FAILED: '文件读取或解码失败（原文文件保留），可更换文件后重试。',
  DOCUMENT_INDEX_FAILED: '索引重建失败，旧索引版本仍然可用，检索不受影响。',
  INTERNAL_ERROR: '发生未预期错误，请联系诊断时提供 requestId。'
};

export interface UiErrorInfo {
  code: ApiErrorCode;
  message: string;
  /** 展示用完整文案（message + 提示 + requestId） */
  text: string;
  retryable: boolean;
  requestId: string | null;
  fieldErrors: Record<string, string>;
  isUnauthorized: boolean;
}

/** 把任意异常规整为 UI 可展示的信息 */
export function describeError(error: unknown): UiErrorInfo {
  const apiError = error instanceof ApiError ? error : toApiError(error);
  const hint = CODE_HINTS[apiError.code];
  const parts: string[] = [apiError.message];
  if (hint && hint !== apiError.message) parts.push(hint);
  if (apiError.requestId) parts.push(`请求编号：${apiError.requestId}`);

  return {
    code: apiError.code,
    message: apiError.message,
    text: parts.join(' '),
    retryable: apiError.retryable,
    requestId: apiError.requestId,
    fieldErrors: apiError.fieldErrors,
    isUnauthorized: apiError.isUnauthorized
  };
}

/** 便捷取字段级错误 */
export function fieldErrorsOf(error: unknown): Record<string, string> {
  return describeError(error).fieldErrors;
}

/**
 * 取出后端返回的 schema path 列表（`CONFIG_FILE_INVALID` 时用于展示「文件里哪个位置不合法」）。
 *
 * 后端的 `errors[]` / `details[]` 元素形状介于 `{ path, message }`、`{ field, message }` 与
 * `{ field: "llm.profiles[0].name", message: "..." }` 之间，这里统一收敛为 `path: message` 文本；
 * 同时从 `ApiError.fieldErrors`（`normalizeFieldKey` 已把 path 收窄为表单 key）兜底。
 * 绝不包含密钥：`message` 由服务端生成，本函数不拼接请求体。
 */
export function configValidationIssues(error: unknown): string[] {
  const issues: string[] = [];
  if (error instanceof ApiError) {
    for (const detail of error.details) {
      const rawPath =
        typeof detail.path === 'string' && detail.path.length > 0
          ? detail.path
          : typeof detail.field === 'string'
            ? detail.field
            : '';
      const message = typeof detail.message === 'string' ? detail.message : '';
      if (!rawPath && !message) continue;
      const line = rawPath ? `${rawPath}：${message}` : message;
      if (!issues.includes(line)) issues.push(line);
    }
  }
  if (issues.length === 0) {
    for (const [path, message] of Object.entries(describeError(error).fieldErrors)) {
      issues.push(`${path}：${message}`);
    }
  }
  return issues;
}

/** 判断错误是否属于给定错误码集合 */
export function isErrorCode(error: unknown, ...codes: ApiErrorCode[]): boolean {
  return error instanceof ApiError && codes.includes(error.code);
}
