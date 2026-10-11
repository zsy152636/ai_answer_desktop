/**
 * 契约 §4 答题与判分。
 * 主观题复核闭环按 Java 实现对齐：
 *   1. `POST /attempts/{id}/ai-grade` 排队 AI 评分任务 → `{ taskIds, pendingCount }`
 *   2. 轮询 `GET /tasks/{taskId}` 直到任务终态（AI 只写“建议分”，仍为 `pending_review`）
 *   3. `POST /attempts/{id}/answers/{snapshotQuestionId}/grade` 人工确认/改分 → `AttemptResult`
 */
import { http } from './client';
import type { PageResult } from '@/types/common';
import type {
  AiGradeEnqueueResponse,
  AttemptAnswer,
  AttemptListQuery,
  AttemptResult,
  AttemptSheet,
  AttemptSummary,
  CreateAttemptPayload,
  ManualGradeRequest
} from '@/types/attempt';
import type { QuestionAnswer } from '@/types/question';

/** `POST /api/v1/attempts`（服务端返回 HTTP 201） */
export function createAttempt(payload: CreateAttemptPayload): Promise<AttemptSheet> {
  return http.post<AttemptSheet>('/attempts', payload, { idempotencyKey: true });
}

export function listAttempts(query: AttemptListQuery): Promise<PageResult<AttemptSummary>> {
  return http.get<PageResult<AttemptSummary>>('/attempts', { ...query });
}

/** 刷新后恢复未完成作答：读服务端已保存的 answers */
export function getAttemptSheet(id: string): Promise<AttemptSheet> {
  return http.get<AttemptSheet>(`/attempts/${encodeURIComponent(id)}`);
}

export function saveAttemptAnswer(
  attemptId: string,
  snapshotQuestionId: string,
  answer: QuestionAnswer
): Promise<AttemptAnswer> {
  return http.put<AttemptAnswer>(
    `/attempts/${encodeURIComponent(attemptId)}/answers/${encodeURIComponent(snapshotQuestionId)}`,
    { answer }
  );
}

/** 提交作答：幂等键由调用方复用，重复提交返回首次结果（响应头 `Idempotent-Replay: true`） */
export function submitAttempt(attemptId: string, idempotencyKey?: string): Promise<AttemptResult> {
  return http.post<AttemptResult>(`/attempts/${encodeURIComponent(attemptId)}/submit`, undefined, {
    idempotencyKey: idempotencyKey ?? true
  });
}

export function getAttemptResult(attemptId: string): Promise<AttemptResult> {
  return http.get<AttemptResult>(`/attempts/${encodeURIComponent(attemptId)}/result`);
}

export function abandonAttempt(attemptId: string): Promise<AttemptSheet> {
  return http.post<AttemptSheet>(`/attempts/${encodeURIComponent(attemptId)}/abandon`);
}

/**
 * 触发 AI 评分：服务端为所有待复核主观题排队后台任务。
 * 本接口**不修改分数**，AI 只回写 `gradingDetail.suggestedScore` 并保持 `pending_review`，
 * 必须再经「人工确认」才会生效。
 */
export function requestAiGrade(attemptId: string): Promise<AiGradeEnqueueResponse> {
  return http.post<AiGradeEnqueueResponse>(`/attempts/${encodeURIComponent(attemptId)}/ai-grade`);
}

/** 人工确认或覆盖主观题分数，返回重算后的完整结果 */
export function gradeAnswerManually(
  attemptId: string,
  snapshotQuestionId: string,
  payload: ManualGradeRequest
): Promise<AttemptResult> {
  return http.post<AttemptResult>(
    `/attempts/${encodeURIComponent(attemptId)}/answers/${encodeURIComponent(snapshotQuestionId)}/grade`,
    payload
  );
}

/**
 * 题目讲解（题库侧，不是答题结果侧，也不是 AI 评分）。
 * `POST /api/v1/ai/questions/{questionId}/explain`，body `{ userAnswer?, profileId? }`。
 * 返回结构由 Python AI 服务决定，此处按 `Record<string, unknown>` 交由调用方收窄。
 */
export function explainQuestion(
  questionId: string,
  body: { userAnswer?: QuestionAnswer; profileId?: string } = {}
): Promise<Record<string, unknown>> {
  return http.post<Record<string, unknown>>(
    `/ai/questions/${encodeURIComponent(questionId)}/explain`,
    body
  );
}
