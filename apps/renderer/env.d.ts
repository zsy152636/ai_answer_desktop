/// <reference types="vite/client" />

/**
 * Electron preload 注入的运行时上下文（契约 §10 `window.app.getRuntime()`）。
 * 浏览器开发调试时该对象不存在，运行时解析会退化为 dev shim。
 */
interface AppRuntimeInjection {
  javaBaseUrl: string;
  sessionToken: string;
  pythonBaseUrl?: string;
  dataDir?: string;
  logDir?: string;
  appVersion?: string;
  platform?: string;
}

/** 桌面壳服务状态事件（契约 §10 `onServiceStatus`） */
interface ServiceStatusEvent {
  java?: 'starting' | 'up' | 'down';
  python?: 'starting' | 'up' | 'down';
}

interface HealthProbeResult {
  status?: string;
  service?: string;
  version?: string;
  schemaVersion?: number;
  uptimeSeconds?: number;
}

/** 契约 §10 preload 白名单，渲染层只允许使用这些方法 */
interface DesktopBridge {
  getVersion(): Promise<string>;
  getRuntime(): Promise<AppRuntimeInjection>;
  getHealth(): Promise<{ java: HealthProbeResult | null; python: HealthProbeResult | null }>;
  selectJsonFile(): Promise<{ canceled: boolean; filePath?: string; content?: string }>;
  selectImportFile(): Promise<{
    canceled: boolean;
    filePath?: string;
    name?: string;
    sizeBytes?: number;
    sha256?: string;
  }>;
  exportConfigFile(
    defaultName: string,
    content: string
  ): Promise<{ canceled: boolean; filePath?: string }>;
  openPath(target: string): Promise<{ ok: boolean; error?: string }>;
  showLogs(): Promise<{ ok: boolean }>;
  restartServices(): Promise<{ ok: boolean }>;
  onServiceStatus(cb: (event: ServiceStatusEvent) => void): () => void;
}

interface Window {
  __APP_RUNTIME__?: AppRuntimeInjection;
  app?: DesktopBridge;
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue';
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>;
  export default component;
}
