/**
 * 统一 HTTP 客户端：原生 fetch，无 axios。
 *
 * 职责：
 *  - 解析契约 §0.1 / §0.2 响应包络，`success=false` 抛 `ApiError`
 *  - 所有 `/api/v1/**` 请求附加 `X-Session-Token`
 *  - 创建/提交类请求支持 `Idempotency-Key`
 *  - 网络失败映射为 `NETWORK_UNAVAILABLE`，401 映射为 `UNAUTHORIZED`
 */
import { invalidateRuntime, resolveRuntime } from './runtime';
import type { ApiEnvelope, ApiErrorBody, ApiErrorCode, ApiErrorDetail } from '@/types/common';

const API_PREFIX = '/api/v1';

export interface ApiErrorInit {
  code: ApiErrorCode;
  message: string;
  details?: ApiErrorDetail[];
  retryable?: boolean;
  requestId?: string | null;
  httpStatus?: number | null;
}

export class ApiError extends Error {
  public readonly code: ApiErrorCode;
  public readonly details: ApiErrorDetail[];
  public readonly retryable: boolean;
  public readonly requestId: string | null;
  public readonly httpStatus: number | null;

  constructor(init: ApiErrorInit) {
    super(init.message);
    this.name = 'ApiError';
    this.code = init.code;
    this.details = init.details ?? [];
    this.retryable = init.retryable ?? false;
    this.requestId = init.requestId ?? null;
    this.httpStatus = init.httpStatus ?? null;
  }

  /** 字段路径 → 错误信息，用于表单 `details` 回填 */
  get fieldErrors(): Record<string, string> {
    const map: Record<string, string> = {};
    for (const detail of this.details) {
      const key = normalizeFieldKey(detail.field ?? detail.path);
      const message = typeof detail.message === 'string' ? detail.message : '';
      if (key && message && !map[key]) map[key] = message;
    }
    return map;
  }

  /** 是否为会话失效，页面据此提示「会话失效」并给出「重试」 */
  get isUnauthorized(): boolean {
    return this.code === 'UNAUTHORIZED';
  }

  get isConflict(): boolean {
    return (
      this.code === 'VERSION_CONFLICT' ||
      this.code === 'STATE_CONFLICT' ||
      this.code === 'IDEMPOTENCY_CONFLICT'
    );
  }
}

/**
 * 把后端字段路径规整为表单可用的 key：
 *  - `answer.value` / `options[0].content` → 取首段
 *  - `currentVersion.stem` → `stem`
 */
export function normalizeFieldKey(raw: unknown): string {
  if (typeof raw !== 'string') return '';
  let value = raw.trim();
  if (!value) return '';
  value = value.replace(/^\$\.?/, '').replace(/^\//, '');
  // JSON Pointer 形式 /stem -> stem
  if (value.includes('/')) value = value.split('/').filter(Boolean).join('.');
  const segments = value.split('.').filter(Boolean);
  if (segments.length === 0) return '';
  const known = ['title', 'questionType', 'difficulty', 'stem', 'options', 'answer', 'explanation',
    'knowledgeTags', 'changeNote', 'expectedVersionNo', 'rubric', 'questions', 'score', 'description',
    'apiKey', 'name', 'provider', 'baseUrl', 'model', 'timeoutSeconds', 'connectTimeoutSeconds',
    'allowLocalNetwork', 'parameters', 'config', 'schemaVersion', 'profiles', 'fileName', 'confirm',
    'profileId', 'result', 'status', 'mode', 'paperId', 'wrongQuestionIds',
    // 契约 §11 资料库：导入/检索相关字段。`sourcePath` 是 409 重复文件时 `details[].field` 的取值，
    // `documentId`/`chunkId`/`duplicates` 来自同一 details，供页面定位与重复选择。
    'sourcePath', 'sha256', 'onDuplicate', 'documentId', 'chunkId', 'duplicates'];
  for (const segment of segments) {
    const clean = segment.replace(/\[\d+\]$/, '');
    if (known.includes(clean)) return clean;
  }
  return segments[0]!.replace(/\[\d+\]$/, '');
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  /** 相对 `/api/v1` 的路径，例如 `/questions`；也可传绝对路径以跳过前缀 */
  path: string;
  query?: Record<string, string | number | boolean | undefined | null>;
  body?: unknown;
  /** 幂等键：调用方传入则透传；`idempotencyKey: true` 表示自动生成 */
  idempotencyKey?: string | boolean;
  signal?: AbortSignal;
  /** 跳过 `/api/v1` 前缀（用于 `/health`） */
  raw?: boolean;
}

let unauthorizedHandler: (() => void) | null = null;

/** 注册全局 401 回调（由 runtime store 设置） */
export function onUnauthorized(handler: (() => void) | null): void {
  unauthorizedHandler = handler;
}

let idempotentReplayHandler: (() => void) | null = null;

/**
 * 注册幂等重放回调：当响应头带 `Idempotent-Replay: true`（同键重复提交被直接重放）时触发，
 * 由 UI 提示用户「本次操作已重放首次结果，未被重复执行」。
 */
export function onIdempotentReplay(handler: (() => void) | null): void {
  idempotentReplayHandler = handler;
}

function buildQuery(query: RequestOptions['query']): string {
  if (!query) return '';
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null) continue;
    if (typeof value === 'string' && value.trim() === '') continue;
    params.append(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}

function newIdempotencyKey(): string {
  const uuid =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  return `renderer-${uuid}`;
}

function isEnvelope(value: unknown): value is ApiEnvelope<unknown> {
  return typeof value === 'object' && value !== null && 'success' in value;
}

function pickErrorBody(value: unknown): ApiErrorBody | null {
  if (typeof value !== 'object' || value === null) return null;
  const error = (value as { error?: unknown }).error;
  if (typeof error !== 'object' || error === null) return null;
  const body = error as { code?: unknown; message?: unknown; details?: unknown; retryable?: unknown };
  return {
    code: typeof body.code === 'string' ? body.code : 'INTERNAL_ERROR',
    message: typeof body.message === 'string' ? body.message : '请求失败',
    details: Array.isArray(body.details) ? (body.details as ApiErrorDetail[]) : [],
    retryable: typeof body.retryable === 'boolean' ? body.retryable : false
  };
}

function pickRequestId(value: unknown): string | null {
  if (typeof value !== 'object' || value === null) return null;
  const requestId = (value as { requestId?: unknown }).requestId;
  return typeof requestId === 'string' ? requestId : null;
}

/**
 * 发起一次业务请求，返回包络中的 `data`。
 */
export async function request<T>(options: RequestOptions): Promise<T> {
  const runtime = await resolveRuntime();

  const url = `${runtime.javaBaseUrl}${options.raw ? '' : API_PREFIX}${options.path}${buildQuery(options.query)}`;

  const headers: Record<string, string> = {
    Accept: 'application/json',
    'X-Session-Token': runtime.sessionToken
  };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json; charset=utf-8';
  if (options.idempotencyKey) {
    headers['Idempotency-Key'] =
      typeof options.idempotencyKey === 'string' ? options.idempotencyKey : newIdempotencyKey();
  }

  const init: RequestInit = {
    method: options.method ?? 'GET',
    headers,
    cache: 'no-store'
  };
  if (options.body !== undefined) init.body = JSON.stringify(options.body);
  if (options.signal) init.signal = options.signal;

  let response: Response;
  try {
    response = await fetch(url, init);
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    const reason = error instanceof Error ? error.message : String(error);
    throw new ApiError({
      code: 'NETWORK_UNAVAILABLE',
      message: `无法连接本机业务服务（${reason}）。请确认 Java 服务正在运行。`,
      retryable: true,
      details: [{ field: 'javaBaseUrl', message: url }]
    });
  }

  if (response.status === 204) {
    return undefined as T;
  }

  let payload: unknown = null;
  const text = await response.text();
  if (text.trim().length > 0) {
    try {
      payload = JSON.parse(text) as unknown;
    } catch {
      throw new ApiError({
        code: 'MALFORMED_RESPONSE',
        message: `本机服务返回了非 JSON 响应（HTTP ${response.status}）。`,
        retryable: true,
        httpStatus: response.status
      });
    }
  }

  if (response.status === 401) {
    invalidateRuntime();
    unauthorizedHandler?.();
    const body = pickErrorBody(payload);
    throw new ApiError({
      code: 'UNAUTHORIZED',
      message: body?.message ?? '会话令牌已失效，请点击「重试」重新获取运行时上下文。',
      details: body?.details ?? [],
      retryable: true,
      requestId: pickRequestId(payload),
      httpStatus: 401
    });
  }

  if (isEnvelope(payload)) {
    if (payload.success === true) {
      // 幂等重放：同键同载荷的重复请求被服务端直接重放首次结果（未重复执行）
      if (response.headers.get('Idempotent-Replay') === 'true') {
        idempotentReplayHandler?.();
      }
      return payload.data as T;
    }
    const body = pickErrorBody(payload);
    const code = (body?.code ?? 'INTERNAL_ERROR') as ApiErrorCode;
    throw new ApiError({
      code,
      message: body?.message ?? `请求失败（HTTP ${response.status}）`,
      details: body?.details ?? [],
      retryable: body?.retryable ?? response.status >= 500,
      requestId: pickRequestId(payload),
      httpStatus: response.status
    });
  }

  if (!response.ok) {
    const body = pickErrorBody(payload);
    throw new ApiError({
      code: body ? (body.code as ApiErrorCode) : mapHttpStatus(response.status),
      message: body?.message ?? `请求失败（HTTP ${response.status}）`,
      details: body?.details ?? [],
      retryable: response.status >= 500,
      requestId: pickRequestId(payload),
      httpStatus: response.status
    });
  }

  // 未包裹络（例如未鉴权的 /health），直接返回原始体
  return payload as T;
}

function mapHttpStatus(status: number): ApiErrorCode {
  if (status === 400) return 'VALIDATION_ERROR';
  if (status === 403) return 'ORIGIN_REJECTED';
  if (status === 404) return 'RESOURCE_NOT_FOUND';
  if (status === 409) return 'STATE_CONFLICT';
  if (status === 503) return 'DB_BUSY';
  return 'INTERNAL_ERROR';
}

/** 便于调用的语法糖 */
export const http = {
  get: <T>(path: string, query?: RequestOptions['query'], signal?: AbortSignal): Promise<T> => {
    const options: RequestOptions = { method: 'GET', path };
    if (query) options.query = query;
    if (signal) options.signal = signal;
    return request<T>(options);
  },
  post: <T>(
    path: string,
    body?: unknown,
    extra?: Omit<RequestOptions, 'method' | 'path' | 'body'>
  ): Promise<T> => request<T>({ method: 'POST', path, body, ...extra }),
  put: <T>(
    path: string,
    body?: unknown,
    extra?: Omit<RequestOptions, 'method' | 'path' | 'body'>
  ): Promise<T> => request<T>({ method: 'PUT', path, body, ...extra }),
  patch: <T>(
    path: string,
    body?: unknown,
    extra?: Omit<RequestOptions, 'method' | 'path' | 'body'>
  ): Promise<T> => request<T>({ method: 'PATCH', path, body, ...extra }),
  delete: <T>(path: string, extra?: Omit<RequestOptions, 'method' | 'path'>): Promise<T> =>
    request<T>({ method: 'DELETE', path, ...extra })
};

/** 把任意异常规整为 ApiError，便于统一展示 */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error;
  if (error instanceof Error) {
    return new ApiError({
      code: 'INTERNAL_ERROR',
      message: error.message,
      retryable: false
    });
  }
  return new ApiError({
    code: 'INTERNAL_ERROR',
    message: '发生未预期错误，请查看日志。',
    retryable: false
  });
}
