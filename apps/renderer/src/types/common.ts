/**
 * 契约 §0 通用约定：成功/失败包络、错误码、枚举、分页。
 */

/**
 * 契约 §0.3 错误码（v1 冻结），另加渲染层自有的两个客户端错误码。
 * 阶段 6 补齐 §0.3 中的 `DOCUMENT_*` 四个资料库错误码（此前遗漏，页面需要按码区分文案）。
 * 契约 §6 v1.1（文件驱动 + 热加载）新增 `CONFIG_FILE_INVALID`。
 */
export type ApiContractErrorCode =
  | 'VALIDATION_ERROR'
  | 'UNAUTHORIZED'
  | 'ORIGIN_REJECTED'
  | 'RESOURCE_NOT_FOUND'
  | 'STATE_CONFLICT'
  | 'VERSION_CONFLICT'
  | 'IDEMPOTENCY_CONFLICT'
  | 'DB_BUSY'
  | 'DB_MIGRATION_FAILED'
  | 'CONFIG_JSON_INVALID'
  | 'CONFIG_SCHEMA_UNSUPPORTED'
  | 'BASE_URL_BLOCKED'
  | 'CREDENTIAL_MISSING'
  | 'CONFIG_FILE_INVALID'
  | 'LLM_AUTH_FAILED'
  | 'LLM_CONNECT_TIMEOUT'
  | 'LLM_UNAVAILABLE'
  | 'TASK_RETRY_EXHAUSTED'
  | 'DOCUMENT_FORMAT_UNSUPPORTED'
  | 'DOCUMENT_TOO_LARGE'
  | 'DOCUMENT_PARSE_FAILED'
  | 'DOCUMENT_INDEX_FAILED'
  | 'INTERNAL_ERROR';

/** 渲染层本地产生的错误码（不来自后端） */
export type ClientErrorCode =
  | 'NETWORK_UNAVAILABLE'
  | 'RUNTIME_UNAVAILABLE'
  | 'MALFORMED_RESPONSE';

export type ApiErrorCode = ApiContractErrorCode | ClientErrorCode;

/** §0.2 error.details 的字段级错误；后端字段名可能不在此白名单内 */
export interface ApiErrorDetail {
  field?: string;
  path?: string;
  message?: string;
  [key: string]: unknown;
}

export interface ApiErrorBody {
  code: string;
  message: string;
  details?: ApiErrorDetail[] | null;
  retryable?: boolean;
}

/** §0.1 成功包络 */
export interface ApiSuccessEnvelope<T> {
  success: true;
  data: T;
  error: null;
  requestId: string;
  timestamp: string;
}

/** §0.2 失败包络 */
export interface ApiFailureEnvelope {
  success: false;
  data: null;
  error: ApiErrorBody;
  requestId: string;
  timestamp: string;
}

export type ApiEnvelope<T> = ApiSuccessEnvelope<T> | ApiFailureEnvelope;

/** §0 分页响应 */
export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface PageQuery {
  page?: number;
  size?: number;
}

/** §0.5 枚举 */
export const QUESTION_TYPES = [
  'single_choice',
  'multiple_choice',
  'true_false',
  'fill_blank',
  'short_answer',
  'essay'
] as const;
export type QuestionType = (typeof QUESTION_TYPES)[number];

export const DRAFTABLE_STATUSES = ['draft', 'published', 'archived'] as const;
export type ContentStatus = (typeof DRAFTABLE_STATUSES)[number];

export const ATTEMPT_STATUSES = ['in_progress', 'submitted', 'graded', 'abandoned'] as const;
export type AttemptStatus = (typeof ATTEMPT_STATUSES)[number];

export const ATTEMPT_MODES = ['paper', 'wrong_practice'] as const;
export type AttemptMode = (typeof ATTEMPT_MODES)[number];

export const GRADING_METHODS = [
  'exact',
  'normalized',
  'partial_credit',
  'ai_rubric',
  'manual',
  'ungraded'
] as const;
export type GradingMethod = (typeof GRADING_METHODS)[number];

export const GRADING_STATUSES = ['graded', 'pending_review', 'failed'] as const;
export type GradingStatus = (typeof GRADING_STATUSES)[number];

export const WRONG_STATUSES = ['active', 'reviewing', 'mastered', 'archived'] as const;
export type WrongStatus = (typeof WRONG_STATUSES)[number];

export const TASK_STATUSES = [
  'queued',
  'running',
  'succeeded',
  'retry_wait',
  'failed',
  'cancelled'
] as const;
export type TaskStatus = (typeof TASK_STATUSES)[number];

/** 难度 1-5（契约未显式限定范围，前端按 1-5 呈现，见 README 待对齐项） */
export type Difficulty = 1 | 2 | 3 | 4 | 5;
