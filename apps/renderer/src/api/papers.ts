/** 契约 §3 试卷与发布快照 */
import { http } from './client';
import type { PageResult } from '@/types/common';
import type {
  Paper,
  PaperListQuery,
  PaperSnapshot,
  PaperSummary,
  PaperUpsertPayload
} from '@/types/paper';

export function listPapers(query: PaperListQuery): Promise<PageResult<PaperSummary>> {
  return http.get<PageResult<PaperSummary>>('/papers', { ...query });
}

export function getPaper(id: string): Promise<Paper> {
  return http.get<Paper>(`/papers/${encodeURIComponent(id)}`);
}

export function createPaper(payload: PaperUpsertPayload): Promise<Paper> {
  return http.post<Paper>('/papers', payload);
}

export function updatePaper(id: string, payload: PaperUpsertPayload): Promise<Paper> {
  return http.put<Paper>(`/papers/${encodeURIComponent(id)}`, payload);
}

/** 发布试卷：生成不可变快照，使用幂等键防止重复发布 */
export function publishPaper(id: string, idempotencyKey?: string): Promise<PaperSnapshot> {
  return http.post<PaperSnapshot>(`/papers/${encodeURIComponent(id)}/publish`, undefined, {
    idempotencyKey: idempotencyKey ?? true
  });
}

export function getPaperSnapshot(id: string, snapshotId: string): Promise<PaperSnapshot> {
  return http.get<PaperSnapshot>(
    `/papers/${encodeURIComponent(id)}/snapshots/${encodeURIComponent(snapshotId)}`
  );
}

export function archivePaper(id: string): Promise<Paper> {
  return http.post<Paper>(`/papers/${encodeURIComponent(id)}/archive`);
}
