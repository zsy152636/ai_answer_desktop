/** 桌面壳能力封装：文件选择、导出、打开路径、服务重启、服务状态订阅 */
import { desktopBridge, isDesktopHost } from '@/api/runtime';

export interface SelectedJsonFile {
  canceled: boolean;
  filePath?: string;
  content?: string;
}

export interface ExportedFile {
  canceled: boolean;
  filePath?: string;
}

/** 桌面壳选择的待导入资料（SHA-256 由壳侧计算，提交前作为一致性凭据） */
export interface SelectedImportFile {
  canceled: boolean;
  filePath?: string;
  name?: string;
  sizeBytes?: number;
  sha256?: string;
}

/** 读取一个 JSON 文件；桌面壳不可用时返回 canceled */
export async function selectJsonFile(): Promise<SelectedJsonFile> {
  const bridge = desktopBridge();
  if (!bridge) return { canceled: true };
  return bridge.selectJsonFile();
}

/** 选择一个待导入的文本资料（契约 §11.2 / §10）；桌面壳不可用时返回 canceled，由页面给出降级提示 */
export async function selectImportFile(): Promise<SelectedImportFile> {
  const bridge = desktopBridge();
  if (!bridge) return { canceled: true };
  return bridge.selectImportFile();
}

/** 通过桌面壳保存配置文件（默认不含密钥；`includeSecrets` 导出时由调用方做二次确认并提示风险） */
export async function exportConfigFile(defaultName: string, content: string): Promise<ExportedFile> {
  const bridge = desktopBridge();
  if (!bridge) return { canceled: true };
  return bridge.exportConfigFile(defaultName, content);
}

/** 在系统文件管理器中打开数据目录/日志目录 */
export async function openPath(target: string): Promise<{ ok: boolean; error?: string }> {
  const bridge = desktopBridge();
  if (!bridge) return { ok: false, error: '当前不在桌面壳环境中，无法打开本地路径。' };
  return bridge.openPath(target);
}

export async function showLogs(): Promise<{ ok: boolean }> {
  const bridge = desktopBridge();
  if (!bridge) return { ok: false };
  return bridge.showLogs();
}

/** 恢复备份后重启本机服务 */
export async function restartServices(): Promise<{ ok: boolean }> {
  const bridge = desktopBridge();
  if (!bridge) return { ok: false };
  return bridge.restartServices();
}

export async function getDesktopHealth(): Promise<{
  java: HealthProbeResult | null;
  python: HealthProbeResult | null;
} | null> {
  const bridge = desktopBridge();
  if (!bridge) return null;
  return bridge.getHealth();
}

/** 订阅服务状态事件；返回取消订阅函数 */
export function onServiceStatus(
  callback: (event: ServiceStatusEvent) => void
): () => void {
  const bridge = desktopBridge();
  if (!bridge) return () => undefined;
  return bridge.onServiceStatus(callback);
}

export { isDesktopHost };

/** 浏览器降级：用 Blob 触发下载（桌面壳不可用时的兜底） */
export function downloadTextFile(fileName: string, content: string, mime = 'application/json'): void {
  const blob = new Blob([content], { type: `${mime};charset=utf-8` });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  anchor.click();
  URL.revokeObjectURL(url);
}
