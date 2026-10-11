/** 运行时上下文与健康状态 store */
import { defineStore } from 'pinia';
import { computed, onScopeDispose, ref, shallowRef } from 'vue';

import { onIdempotentReplay, onUnauthorized } from '@/api/client';
import {
  RuntimeUnavailableError,
  desktopBridge,
  invalidateRuntime,
  isDesktopHost,
  resolveRuntime,
  type AppRuntimeContext
} from '@/api/runtime';
import { getJavaHealth, getMeta } from '@/api/system';
import { describeError, type UiErrorInfo } from '@/utils/error';
import { onServiceStatus } from '@/utils/desktop';
import type { HealthResponse, MetaResponse } from '@/types/system';

export type RuntimeStatus = 'idle' | 'loading' | 'ready' | 'error';
export type ServiceState = 'unknown' | 'up' | 'down';

export const useRuntimeStore = defineStore('runtime', () => {
  const status = ref<RuntimeStatus>('idle');
  const context = shallowRef<AppRuntimeContext | null>(null);
  const error = ref<UiErrorInfo | null>(null);
  const javaState = ref<ServiceState>('unknown');
  const pythonState = ref<ServiceState>('unknown');
  const javaHealth = ref<HealthResponse | null>(null);
  /** `GET /api/v1/meta` 的元信息（含 fts5Available / contractVersion / aiService） */
  const meta = ref<MetaResponse | null>(null);
  const metaError = ref<UiErrorInfo | null>(null);
  const lastCheckedAt = ref<string | null>(null);
  const sessionExpired = ref(false);

  let unsubscribe: (() => void) | null = null;
  let subscribed = false;

  const ready = computed(() => status.value === 'ready' && context.value !== null);
  const isDesktop = computed(() => isDesktopHost());
  const javaBaseUrl = computed(() => context.value?.javaBaseUrl ?? '');
  const appVersion = computed(() => context.value?.appVersion ?? '—');
  const platform = computed(() => context.value?.platform ?? '—');

  function setUnauthorized(): void {
    sessionExpired.value = true;
    status.value = 'error';
    error.value = {
      code: 'UNAUTHORIZED',
      message: '会话令牌已失效，需要重新获取运行时上下文。',
      text: '会话已失效。本机服务的会话令牌只在其进程生命周期内有效，请点击「重试」重新获取；若仍失败，请确认 Java 服务已重启。',
      retryable: true,
      requestId: null,
      fieldErrors: {},
      isUnauthorized: true
    };
  }

  /** 解析运行时上下文；失败时进入明确的错误态（页面不白屏） */
  async function ensureRuntime(force = false): Promise<boolean> {
    if (!force && ready.value) return true;
    status.value = 'loading';
    error.value = null;
    if (force) invalidateRuntime();

    try {
      context.value = await resolveRuntime(force);
      status.value = 'ready';
      sessionExpired.value = false;
      error.value = null;
      return true;
    } catch (caught) {
      context.value = null;
      status.value = 'error';
      if (caught instanceof RuntimeUnavailableError) {
        error.value = {
          code: 'RUNTIME_UNAVAILABLE',
          message: caught.message,
          text: caught.details.map((detail) => detail.message).filter(Boolean).join(' '),
          retryable: true,
          requestId: null,
          fieldErrors: {},
          isUnauthorized: false
        };
      } else {
        error.value = describeError(caught);
      }
      return false;
    }
  }

  /** 探活 Java；桌面壳可用时一并探活 Python */
  async function probeHealth(): Promise<void> {
    const bridge = desktopBridge();
    if (bridge) {
      try {
        const result = await bridge.getHealth();
        markJava(result.java);
        markPython(result.python);
        lastCheckedAt.value = new Date().toISOString();
        // 桌面壳只提供健康探针，元信息仍需通过 /meta 获取
        if (ready.value && !meta.value) await loadMeta();
        return;
      } catch {
        // 落到 HTTP 探活
      }
    }

    if (!ready.value) {
      javaState.value = 'unknown';
      pythonState.value = 'unknown';
      return;
    }

    try {
      javaHealth.value = await getJavaHealth();
      markJava(javaHealth.value);
    } catch {
      javaState.value = 'down';
      javaHealth.value = null;
    }

    await loadMeta();

    lastCheckedAt.value = new Date().toISOString();
  }

  /** 拉取 `/api/v1/meta`（fts5Available / contractVersion / aiService） */
  async function loadMeta(): Promise<void> {
    try {
      meta.value = await getMeta();
      metaError.value = null;
      if (meta.value.aiService.available) pythonState.value = 'up';
    } catch (caught) {
      metaError.value = describeError(caught);
      meta.value = null;
    }
  }

  function markJava(health: HealthProbeResult | null): void {
    if (health && typeof health.status === 'string' && health.status.toUpperCase() === 'UP') {
      javaState.value = 'up';
      javaHealth.value = health as HealthResponse;
    } else {
      javaState.value = 'down';
      javaHealth.value = null;
    }
  }

  function markPython(health: HealthProbeResult | null): void {
    if (health && typeof health.status === 'string' && health.status.toUpperCase() === 'UP') {
      pythonState.value = 'up';
    } else {
      pythonState.value = 'down';
    }
  }

  /** 订阅桌面壳服务状态事件（仅桌面壳可用时） */
  function subscribeServiceStatus(): void {
    if (subscribed) return;
    subscribed = true;
    unsubscribe = onServiceStatus((event) => {
      if (event.java === 'up') javaState.value = 'up';
      else if (event.java === 'down') javaState.value = 'down';
      else if (event.java === 'starting') javaState.value = 'unknown';

      if (event.python === 'up') pythonState.value = 'up';
      else if (event.python === 'down') pythonState.value = 'down';
      else if (event.python === 'starting') pythonState.value = 'unknown';
    });
  }

  function dispose(): void {
    if (unsubscribe) unsubscribe();
    unsubscribe = null;
    subscribed = false;
  }

  onScopeDispose(dispose);

  onUnauthorized(setUnauthorized);

  /** 幂等重放计数：服务端对同键同载荷的重复请求直接重放首次结果 */
  const idempotentReplayCount = ref(0);
  onIdempotentReplay(() => {
    idempotentReplayCount.value += 1;
  });

  async function retry(): Promise<boolean> {
    const ok = await ensureRuntime(true);
    if (ok) await probeHealth();
    return ok;
  }

  return {
    status,
    context,
    error,
    javaState,
    pythonState,
    javaHealth,
    meta,
    metaError,
    lastCheckedAt,
    sessionExpired,
    idempotentReplayCount,
    ready,
    isDesktop,
    javaBaseUrl,
    appVersion,
    platform,
    ensureRuntime,
    probeHealth,
    loadMeta,
    subscribeServiceStatus,
    retry,
    dispose
  };
});
