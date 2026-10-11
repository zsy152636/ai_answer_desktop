/** 契约 §11 资料库（按 Java 实现对齐） */
import { http } from './client';
import type { PageResult } from '@/types/common';
import type {
  ChunkView,
  DocumentDeleteResponse,
  DocumentImportPayload,
  DocumentListQuery,
  DocumentSummary,
  IndexState,
  ReindexResponse,
  SearchQuery,
  SearchResponse
} from '@/types/document';

/**
 * `GET /api/v1/documents`：分页 + 标题模糊 `q` + `status` 筛选 + 按文件 `sha256` 反查。
 * 空值的 `sha256` 不放进 query（沿用 client 的 buildQuery 约定：空串/undefined 一律省略）。
 */
export function listDocuments(query: DocumentListQuery): Promise<PageResult<DocumentSummary>> {
  const params: DocumentListQuery = { ...query };
  const sha256 = params.sha256?.trim();
  if (sha256) params.sha256 = sha256;
  else delete params.sha256;
  return http.get<PageResult<DocumentSummary>>('/documents', { ...params });
}

/**
 * `POST /api/v1/documents/import`：导入文本资料（服务端重算 SHA-256 与入参比对，防 TOCTOU）。
 * 成功返回 HTTP **200** + `DocumentSummary`（Java `DocumentController#importDocument` 返回包络，非 201）。
 * 重复 `sha256` 且 `onDuplicate='reject'`（默认）时返回 409 `STATE_CONFLICT`，但 `details` 只有
 * `[{field:"sourcePath", message}]`（契约 §0.2 形状固定），**没有**结构化 duplicates；
 * 因此拿到 409 后必须再用 `listDocuments({ sha256 })` 取回 typed 列表，才能让用户可靠地「复用」。
 */
export function importDocument(payload: DocumentImportPayload): Promise<DocumentSummary> {
  return http.post<DocumentSummary>('/documents/import', payload, { idempotencyKey: true });
}

/** `GET /api/v1/documents/{id}`：不存在返回 `RESOURCE_NOT_FOUND` */
export function getDocument(id: string): Promise<DocumentSummary> {
  return http.get<DocumentSummary>(`/documents/${encodeURIComponent(id)}`);
}

/** `GET /api/v1/documents/{id}/chunks`：分块预览（分页） */
export function listDocumentChunks(
  id: string,
  query?: { page?: number; size?: number }
): Promise<PageResult<ChunkView>> {
  return http.get<PageResult<ChunkView>>(
    `/documents/${encodeURIComponent(id)}/chunks`,
    query ? { ...query } : undefined
  );
}

/**
 * `DELETE /api/v1/documents/{id}`：删除资料及其分块与索引行。
 * 不带 `Idempotency-Key`：Java 的幂等过滤器只覆盖 POST/PUT/PATCH（契约 §0.4），DELETE 会被忽略。
 */
export function deleteDocument(id: string): Promise<DocumentDeleteResponse> {
  return http.delete<DocumentDeleteResponse>(`/documents/${encodeURIComponent(id)}`);
}

/**
 * `POST /api/v1/documents/reindex`：版本化重建索引（新 `indexVersion` → 全量重建 → 校验 → 切换 activeVersion）。
 * 响应是 `{ indexState, chunks, failures }`（indexState 为嵌套字段）；失败保留旧版本并返回 `DOCUMENT_INDEX_FAILED`。
 */
export function reindexDocuments(): Promise<ReindexResponse> {
  return http.post<ReindexResponse>('/documents/reindex', undefined, { idempotencyKey: true });
}

/** `GET /api/v1/search/index`：索引状态 */
export function getIndexState(): Promise<IndexState> {
  return http.get<IndexState>('/search/index');
}

/** `GET /api/v1/search`：检索（`q` 必填 1-200 字符，`limit` 默认 10、最大 50） */
export function searchDocuments(query: SearchQuery): Promise<SearchResponse> {
  return http.get<SearchResponse>('/search', { ...query });
}
