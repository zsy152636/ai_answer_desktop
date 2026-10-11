/** 契约 §8 系统设置 + §1 健康与元信息（按 Java 实现对齐） */
import { http } from './client';
import type {
  BackupCreateResponse,
  BackupEntry,
  HealthResponse,
  IntegrityCheckResponse,
  LogFileEntry,
  LogTailResponse,
  MetaResponse,
  RestoreResponse,
  SeedDemoResponse,
  SystemInfoResponse
} from '@/types/system';

export function getMeta(): Promise<MetaResponse> {
  return http.get<MetaResponse>('/meta');
}

export function getSystemInfo(): Promise<SystemInfoResponse> {
  return http.get<SystemInfoResponse>('/system/info');
}

export function createBackup(): Promise<BackupCreateResponse> {
  return http.post<BackupCreateResponse>('/system/backup');
}

export function listBackups(): Promise<BackupEntry[]> {
  return http.get<BackupEntry[]>('/system/backups');
}

export function restoreBackup(fileName: string): Promise<RestoreResponse> {
  return http.post<RestoreResponse>('/system/restore', { fileName, confirm: true });
}

export function runIntegrityCheck(): Promise<IntegrityCheckResponse> {
  return http.post<IntegrityCheckResponse>('/system/integrity-check');
}

/**
 * 对指定备份做只读校验（integrity_check + foreign_key_check + schema_version），
 * 供恢复前二次确认。`fileName` 走 query 参数。
 */
export function verifyBackup(fileName: string): Promise<IntegrityCheckResponse> {
  return http.post<IntegrityCheckResponse>('/system/backups/verify', undefined, {
    query: { fileName }
  });
}

export function seedDemoData(): Promise<SeedDemoResponse> {
  return http.post<SeedDemoResponse>('/system/seed-demo');
}

/** `GET /api/v1/system/logs` 返回日志文件列表 */
export function listLogFiles(): Promise<LogFileEntry[]> {
  return http.get<LogFileEntry[]>('/system/logs');
}

/** `GET /api/v1/system/logs/tail?file=` 返回尾部 500 行（脱敏后） */
export function readLogTail(file: string): Promise<LogTailResponse> {
  return http.get<LogTailResponse>('/system/logs/tail', { file });
}

/** `/health` 不鉴权、不参与版本前缀，且不包裹络 */
export function getJavaHealth(signal?: AbortSignal): Promise<HealthResponse> {
  return http.get<HealthResponse>('/health', undefined, signal);
}
