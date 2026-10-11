/**
 * 运行时上下文解析（契约 §10 preload 白名单；浏览器 dev 走 `/__app_runtime` shim）。
 *
 * 优先级：
 *   1. `window.__APP_RUNTIME__`（Electron preload 注入）
 *   2. dev 环境 `GET /__app_runtime`（结果缓存在内存）
 *   3. 失败 → 抛 RuntimeUnavailableError，由 `StatePanel` 呈现「本机服务未就绪」错误态
 */
import type { ApiErrorDetail } from '@/types/common';

export interface AppRuntimeContext {
  javaBaseUrl: string;
  sessionToken: string;
  pythonBaseUrl?: string;
  dataDir?: string;
  logDir?: string;
  appVersion?: string;
  platform?: string;
  /** 运行时来源，便于诊断展示 */
  source: 'preload' | 'dev-shim';
}

export class RuntimeUnavailableError extends Error {
  public readonly code = 'RUNTIME_UNAVAILABLE';
  public readonly details: ApiErrorDetail[];
  public readonly retryable = true;

  constructor(message: string, details: ApiErrorDetail[] = []) {
    super(message);
    this.name = 'RuntimeUnavailableError';
    this.details = details;
  }
}

let cached: AppRuntimeContext | null = null;
let inflight: Promise<AppRuntimeContext> | null = null;

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function stripTrailingSlash(url: string): string {
  return url.replace(/\/+$/, '');
}

function fromPreload(): AppRuntimeContext | null {
  const injected = window.__APP_RUNTIME__;
  if (!injected) return null;
  return normalizeContext(injected, 'preload');
}

/**
 * 兜底路径：桌面壳若只暴露 `window.app.getRuntime()`（契约 §10 的定义方式）
 * 而未注入 `window.__APP_RUNTIME__`，则通过 preload 白名单异步获取。
 */
async function fromDesktopBridge(): Promise<AppRuntimeContext | null> {
  const bridge = typeof window === 'undefined' ? undefined : window.app;
  if (!bridge || typeof bridge.getRuntime !== 'function') return null;
  try {
    const runtime = await bridge.getRuntime();
    return normalizeContext(runtime, 'preload');
  } catch {
    return null;
  }
}

function normalizeContext(
  injected: AppRuntimeInjection,
  source: AppRuntimeContext['source']
): AppRuntimeContext | null {
  if (!isNonEmptyString(injected.javaBaseUrl) || !isNonEmptyString(injected.sessionToken)) {
    return null;
  }
  const context: AppRuntimeContext = {
    javaBaseUrl: stripTrailingSlash(injected.javaBaseUrl.trim()),
    sessionToken: injected.sessionToken.trim(),
    source
  };
  if (isNonEmptyString(injected.pythonBaseUrl)) context.pythonBaseUrl = injected.pythonBaseUrl.trim();
  if (isNonEmptyString(injected.dataDir)) context.dataDir = injected.dataDir;
  if (isNonEmptyString(injected.logDir)) context.logDir = injected.logDir;
  if (isNonEmptyString(injected.appVersion)) context.appVersion = injected.appVersion;
  if (isNonEmptyString(injected.platform)) context.platform = injected.platform;
  return context;
}

interface DevShimPayload {
  javaBaseUrl?: unknown;
  sessionToken?: unknown;
  pythonBaseUrl?: unknown;
  dataDir?: unknown;
  logDir?: unknown;
  appVersion?: unknown;
  platform?: unknown;
  message?: unknown;
}

async function fromDevShim(): Promise<AppRuntimeContext> {
  let response: Response;
  try {
    response = await fetch('/__app_runtime', {
      method: 'GET',
      headers: { Accept: 'application/json' },
      cache: 'no-store'
    });
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);
    throw new RuntimeUnavailableError(`本机服务未就绪：无法访问开发期运行时接口（${reason}）。`, [
      { field: 'javaBaseUrl', message: '请确认 Vite dev server 正在运行，且 Java 业务服务已启动。' }
    ]);
  }

  let payload: DevShimPayload = {};
  try {
    payload = (await response.json()) as DevShimPayload;
  } catch {
    payload = {};
  }

  if (!response.ok) {
    const hint = isNonEmptyString(payload.message)
      ? payload.message
      : `开发期运行时接口返回 HTTP ${response.status}。`;
    throw new RuntimeUnavailableError(`本机服务未就绪：${hint}`, [
      {
        field: 'javaBaseUrl',
        message:
          '请先启动 Java 业务服务生成 .runtime/java-endpoint.json，或用 AIA_ENDPOINT_FILE 指定端点文件路径。'
      }
    ]);
  }

  if (!isNonEmptyString(payload.javaBaseUrl) || !isNonEmptyString(payload.sessionToken)) {
    throw new RuntimeUnavailableError('本机服务未就绪：开发期运行时接口返回内容缺少 javaBaseUrl 或 sessionToken。');
  }

  const context: AppRuntimeContext = {
    javaBaseUrl: stripTrailingSlash(payload.javaBaseUrl.trim()),
    sessionToken: payload.sessionToken.trim(),
    source: 'dev-shim'
  };
  if (isNonEmptyString(payload.pythonBaseUrl)) context.pythonBaseUrl = payload.pythonBaseUrl.trim();
  if (isNonEmptyString(payload.dataDir)) context.dataDir = payload.dataDir;
  if (isNonEmptyString(payload.logDir)) context.logDir = payload.logDir;
  if (isNonEmptyString(payload.appVersion)) context.appVersion = payload.appVersion;
  if (isNonEmptyString(payload.platform)) context.platform = payload.platform;
  return context;
}

/** 解析运行时上下文；同一时刻只会发起一次请求 */
export async function resolveRuntime(force = false): Promise<AppRuntimeContext> {
  if (!force && cached) return cached;
  if (!force && inflight) return inflight;

  const task = (async (): Promise<AppRuntimeContext> => {
    const preload = fromPreload();
    if (preload) {
      cached = preload;
      return preload;
    }
    const bridged = await fromDesktopBridge();
    const resolved = bridged ?? (await fromDevShim());
    cached = resolved;
    return resolved;
  })();

  inflight = task;
  try {
    return await task;
  } finally {
    inflight = null;
  }
}

/** 已解析的运行时（未解析时返回 null，供展示层读取） */
export function peekRuntime(): AppRuntimeContext | null {
  return cached;
}

/** 会话失效后强制重新解析（用于「重试」按钮） */
export function invalidateRuntime(): void {
  cached = null;
}

/** 桌面壳桥接对象（浏览器环境为 undefined） */
export function desktopBridge(): DesktopBridge | undefined {
  return typeof window === 'undefined' ? undefined : window.app;
}

export function isDesktopHost(): boolean {
  return desktopBridge() !== undefined;
}
