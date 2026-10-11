import * as fs from 'node:fs';

/**
 * 端点文件读取。
 *
 * 刻意与 `paths.ts` 分开：本模块不依赖 Electron，因此可以在纯 Node 环境里被测试脚本
 * 直接调用（见 scripts/verify-services.mjs）。子进程编排的价值就在于「能真的把服务拉起来」，
 * 而这部分逻辑不应该因为 GUI 环境不可用就无法验证。
 */
export interface EndpointSnapshot {
  readonly baseUrl: string;
  readonly token: string;
}

/** 读取端点文件；文件不存在、内容不完整或正在写入时返回 null。 */
export function readEndpointFile(file: string): EndpointSnapshot | null {
  try {
    const raw = fs.readFileSync(file, 'utf8');
    const parsed = JSON.parse(raw) as { baseUrl?: unknown; token?: unknown };
    if (typeof parsed.baseUrl === 'string' && parsed.baseUrl.length > 0) {
      return { baseUrl: parsed.baseUrl, token: typeof parsed.token === 'string' ? parsed.token : '' };
    }
    return null;
  } catch {
    return null;
  }
}
