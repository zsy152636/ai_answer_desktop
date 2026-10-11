/** 契约 §2 题库与题目版本（按 Java `QuestionController` 对齐） */
import { http } from './client';
import type { PageResult } from '@/types/common';
import type {
  Question,
  QuestionDeleteResponse,
  QuestionListQuery,
  QuestionSummary,
  QuestionUpsertPayload,
  QuestionVersionBrief
} from '@/types/question';

export function listQuestions(query: QuestionListQuery): Promise<PageResult<QuestionSummary>> {
  return http.get<PageResult<QuestionSummary>>('/questions', { ...query });
}

export function getQuestion(id: string, versionNo?: number): Promise<Question> {
  return http.get<Question>(
    `/questions/${encodeURIComponent(id)}`,
    versionNo ? { versionNo } : undefined
  );
}

/** 建题（服务端返回 HTTP 201，同时建 version 1） */
export function createQuestion(payload: QuestionUpsertPayload): Promise<Question> {
  return http.post<Question>('/questions', payload);
}

export function updateQuestion(id: string, payload: QuestionUpsertPayload): Promise<Question> {
  return http.put<Question>(`/questions/${encodeURIComponent(id)}`, payload);
}

export function publishQuestion(id: string): Promise<Question> {
  return http.post<Question>(`/questions/${encodeURIComponent(id)}/publish`);
}

export function archiveQuestion(id: string): Promise<Question> {
  return http.post<Question>(`/questions/${encodeURIComponent(id)}/archive`);
}

/** 仅从未发布且从未被引用的草稿可删除，否则 409 STATE_CONFLICT */
export function deleteQuestion(id: string): Promise<QuestionDeleteResponse> {
  return http.delete<QuestionDeleteResponse>(`/questions/${encodeURIComponent(id)}`);
}

/** 版本列表（Java `VersionBrief`） */
export function listQuestionVersions(id: string): Promise<QuestionVersionBrief[]> {
  return http.get<QuestionVersionBrief[]>(`/questions/${encodeURIComponent(id)}/versions`);
}
