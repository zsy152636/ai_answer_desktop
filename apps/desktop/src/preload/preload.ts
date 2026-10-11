import { contextBridge, ipcRenderer } from 'electron';

/**
 * preload：只暴露固定函数（开发文档 §2.2、契约 §10）。
 *
 * 禁止暴露 ipcRenderer、Node 全局对象或任意文件路径读写；
 * 运行时会话（本机端口 + 短期令牌）通过同步 IPC 取得，不落任何持久存储。
 */

let runtime: unknown = null;
try {
  runtime = ipcRenderer.sendSync('app:runtime');
} catch {
  // 主进程尚未注册同步处理器时保持为 null，渲染层会走显式错误态而不是白屏
  runtime = null;
}
contextBridge.exposeInMainWorld('__APP_RUNTIME__', runtime);

const api = {
  getVersion: (): Promise<string> => ipcRenderer.invoke('app:get-version'),
  getRuntime: (): Promise<unknown> => ipcRenderer.invoke('app:get-runtime'),
  getHealth: (): Promise<unknown> => ipcRenderer.invoke('app:get-health'),
  selectJsonFile: (): Promise<unknown> => ipcRenderer.invoke('dialog:select-json-file'),
  selectImportFile: (): Promise<unknown> => ipcRenderer.invoke('dialog:select-import-file'),
  exportConfigFile: (defaultName: string, content: string): Promise<unknown> =>
    ipcRenderer.invoke('file:export-config-file', defaultName, content),
  openPath: (target: string): Promise<unknown> => ipcRenderer.invoke('shell:open-path', target),
  showLogs: (): Promise<unknown> => ipcRenderer.invoke('app:show-logs'),
  restartServices: (): Promise<unknown> => ipcRenderer.invoke('app:restart-services'),
  onServiceStatus: (callback: (snapshot: unknown) => void): (() => void) => {
    const listener = (_event: unknown, snapshot: unknown): void => callback(snapshot);
    ipcRenderer.on('service:status', listener);
    return () => {
      ipcRenderer.removeListener('service:status', listener);
    };
  },
  onServiceRestarted: (callback: (snapshot: unknown) => void): (() => void) => {
    const listener = (_event: unknown, snapshot: unknown): void => callback(snapshot);
    ipcRenderer.on('service:restarted', listener);
    return () => {
      ipcRenderer.removeListener('service:restarted', listener);
    };
  },
} as const;

contextBridge.exposeInMainWorld('app', api);

export type DesktopApi = typeof api;
