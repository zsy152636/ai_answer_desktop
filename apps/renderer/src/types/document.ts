/**
 * 契约 §11 资料库与本地检索（阶段 6）。
 * 字段与 `docs/api/api-contract.md` §11.1 逐字段对齐（camelCase，可空字段显式返回 null）。
 */

/** §11.1 `status` ∈ imported | indexed | failed */
export const DOCUMENT_STATUSES = ['imported', 'indexed', 'failed'] as const;
export type DocumentStatus = (typeof DOCUMENT_STATUSES)[number];

/** §11.1 `sourceKind` ∈ text | markdown */
export const DOCUMENT_SOURCE_KINDS = ['text', 'markdown'] as const;
export type DocumentSourceKind = (typeof DOCUMENT_SOURCE_KINDS)[number];

/** §11.1 `matchMode` ∈ fts5 | keyword */
export const SEARCH_MATCH_MODES = ['fts5', 'keyword'] as const;
export type SearchMatchMode = (typeof SEARCH_MATCH_MODES)[number];

/** §11.2 导入时的重复文件策略（默认 reject，命中重复由用户改选 reuse / new） */
export const DOCUMENT_DUPLICATE_STRATEGIES = ['reject', 'reuse', 'new'] as const;
export type DocumentDuplicateStrategy = (typeof DOCUMENT_DUPLICATE_STRATEGIES)[number];

/** §11.1 `DocumentSummary` */
export interface DocumentSummary {
  id: string;
  title: string;
  status: DocumentStatus;
  sourceKind: DocumentSourceKind;
  originalFileName: string;
  sizeBytes: number;
  sha256: string;
  chunkCount: number;
  charCount: number;
  indexVersion: number | null;
  parserVersion: string | null;
  /** 仅在 `status=failed` 时有值，已脱敏且不含文件绝对路径 */
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

/** §11.1 `ChunkView`（分块预览，供校对与引用核对） */
export interface ChunkView {
  id: string;
  documentId: string;
  chunkIndex: number;
  pageNo: number | null;
  content: string;
  contentHash: string;
  parserVersion: string;
  indexVersion: number;
  createdAt: string;
}

/** §11.1 `SearchHit`（`snippet` 里的命中片段用 `[[` / `]]` 标记，客户端按纯文本渲染） */
export interface SearchHit {
  documentId: string;
  chunkId: string;
  title: string;
  pageNo: number | null;
  chunkIndex: number;
  snippet: string;
  /** 越大越相关（两套排序规则见契约 §11.4） */
  score: number;
  matchMode: SearchMatchMode;
}

/** §11.2 `GET /api/v1/search` 的 `data` */
export interface SearchResponse {
  query: string;
  mode: SearchMatchMode;
  tookMs: number;
  total: number;
  results: SearchHit[];
}

/** §11.1 `IndexState` */
export interface IndexState {
  indexName: string;
  activeVersion: number;
  /** 重建进行中才有值 */
  buildingVersion: number | null;
  updatedAt: string | null;
  fts5Available: boolean;
  documentCount: number;
  chunkCount: number;
}

/**
 * §11.2 `POST /api/v1/documents/reindex` → `{ indexState, chunks, failures }`。
 * 注意 `indexState` 是**嵌套字段**（Java `DocumentDtos.IndexRebuildResponse`），不是平铺的 IndexState。
 */
export interface ReindexResponse {
  indexState: IndexState;
  chunks: number;
  failures: string[];
}

/** §11.2 `GET /api/v1/documents` 查询参数 */
export interface DocumentListQuery {
  page?: number;
  size?: number;
  /** 标题模糊匹配 */
  q?: string;
  status?: DocumentStatus;
  /**
   * 按文件 SHA-256 反查引用该文件的资料（契约 §11.2 / Java `DocumentController#list` 的 `sha256` 参数）。
   * 重复导入时用它取回 **typed** 的 `DocumentSummary[]`，再让用户选择「复用 / 新建 / 取消」。
   */
  sha256?: string;
}

/** §11.2 `GET /api/v1/search` 查询参数（`q` 必填 1-200 字符，`limit` 默认 10、最大 50） */
export interface SearchQuery {
  q: string;
  limit?: number;
  documentId?: string;
}

/** §11.2 `POST /api/v1/documents/import` 请求体 */
export interface DocumentImportPayload {
  sourcePath: string;
  sha256: string;
  title?: string;
  onDuplicate?: DocumentDuplicateStrategy;
}

/**
 * 旧契约形状的**防御性兼容**视图模型：`error.details` 里若带 `duplicates`，只用于在重复文件对话框里
 * 作为补充信息展示（Java 实现不发这个形状——`details` 固定为 `[{field, message}]`）。
 * 复用它时使用的 documentId **一律来自** `GET /documents?sha256=` 返回的 typed `DocumentSummary[]`。
 */
export interface DuplicateDocument {
  documentId: string;
  title: string;
  createdAt: string;
}

/** §11.2 `DELETE /api/v1/documents/{id}` */
export interface DocumentDeleteResponse {
  deleted: boolean;
  fileRemoved: boolean;
}

/** 分块预览默认分页大小（契约 §0 分页默认 20） */
export const DOCUMENT_CHUNK_PAGE_SIZE = 20;
