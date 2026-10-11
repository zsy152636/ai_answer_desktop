/**
 * 契约 §1 健康与元信息、§8 系统设置。
 * 字段与 Java 实现（`services/java-api` 的 `SystemDtos`）逐字段对齐。
 */

/** `GET /health`（不鉴权） */
export interface HealthResponse {
  status: string;
  service: string;
  version: string;
  schemaVersion: number;
  uptimeSeconds: number;
}

/** AI 服务状态（`MetaView.aiService` / `SystemInfoView.aiService` / dashboard 共用） */
export interface AiServiceStatus {
  available: boolean;
  baseUrl: string | null;
  latencyMs: number | null;
  message: string | null;
}

/** 契约 §1 `GET /api/v1/meta`（Java `MetaView`） */
export interface MetaResponse {
  appVersion: string;
  contractVersion: string;
  schemaVersion: number;
  dataDir: string;
  databasePath: string;
  databaseSizeBytes: number;
  fts5Available: boolean;
  aiService: AiServiceStatus;
  startedAt: string;
}

/** 契约 §8 备份条目（Java `BackupView`） */
export interface BackupEntry {
  fileName: string;
  sizeBytes: number;
  checksum: string | null;
  createdAt: string;
  schemaVersion: number;
  appVersion: string | null;
  integrityOk: boolean;
  kind: string | null;
}

/** 契约 §8 `GET /api/v1/system/info`（Java `SystemInfoView`） */
export interface SystemInfoResponse {
  appVersion: string;
  schemaVersion: number;
  dataDir: string;
  logDir: string;
  databasePath: string;
  databaseSizeBytes: number;
  backupCount: number;
  backups: BackupEntry[];
  uptimeSeconds: number;
  /** 固定为 `setting.json`（契约 §8：LLM 配置文件的来源标识，UI 必须显式展示） */
  configSource: string;
  /** `<UserData>/config/setting.json` 的绝对路径 */
  configPath: string;
  aiService: AiServiceStatus;
}

/** 契约 §8 `POST /api/v1/system/backup` → `BackupView` */
export type BackupCreateResponse = BackupEntry;

/** 契约 §8 `POST /api/v1/system/restore`（Java `RestoreResponse`） */
export interface RestoreResponse {
  pendingRestore: boolean;
  requiresRestart: boolean;
  backup: BackupEntry | null;
}

/** 契约 §8 `POST /api/v1/system/integrity-check`、`POST /api/v1/system/backups/verify` */
export interface IntegrityCheckResponse {
  ok: boolean;
  integrityCheck: string;
  foreignKeyCheck: string;
  schemaVersion: number;
  databaseSizeBytes: number;
}

/** 契约 §8 `POST /api/v1/system/seed-demo`（Java `SeedDemoResponse`） */
export interface SeedDemoResponse {
  questionsCreated: number;
  papersCreated: number;
  skipped: boolean;
}

/** 契约 §8 `GET /api/v1/system/logs`（Java `LogFileView`） */
export interface LogFileEntry {
  fileName: string;
  sizeBytes: number;
  modifiedAt: string;
}

/** 契约 §8 `GET /api/v1/system/logs/tail?file=`（Java `LogTailView`） */
export interface LogTailResponse {
  fileName: string;
  lines: string[];
}
