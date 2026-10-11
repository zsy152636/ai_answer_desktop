/** 契约 §5 错题闭环（按 Java `WrongQuestionController` 对齐） */
import { http } from './client';
import type { PageResult } from '@/types/common';
import type { AttemptSheet } from '@/types/attempt';
import type {
  PatchWrongQuestionPayload,
  ReviewWrongQuestionPayload,
  WrongPracticePayload,
  WrongQuestion,
  WrongQuestionDetail,
  WrongQuestionListQuery
} from '@/types/wrongQuestion';

export function listWrongQuestions(
  query: WrongQuestionListQuery
): Promise<PageResult<WrongQuestion>> {
  return http.get<PageResult<WrongQuestion>>('/wrong-questions', { ...query });
}

/** 详情 = `{ question, reviews[] }` */
export function getWrongQuestion(id: string): Promise<WrongQuestionDetail> {
  return http.get<WrongQuestionDetail>(`/wrong-questions/${encodeURIComponent(id)}`);
}

/** 提交复习结果（正确/错误），返回更新后的详情 */
export function reviewWrongQuestion(
  id: string,
  payload: ReviewWrongQuestionPayload
): Promise<WrongQuestionDetail> {
  return http.post<WrongQuestionDetail>(
    `/wrong-questions/${encodeURIComponent(id)}/review`,
    payload,
    { idempotencyKey: true }
  );
}

/** 创建 wrong_practice attempt（去练习），返回 AttemptSheet */
export function createWrongPracticeAttempt(payload: WrongPracticePayload): Promise<AttemptSheet> {
  return http.post<AttemptSheet>('/wrong-questions/practice', payload, { idempotencyKey: true });
}

/** 手动标记掌握/归档/恢复，返回更新后的详情 */
export function patchWrongQuestion(
  id: string,
  payload: PatchWrongQuestionPayload
): Promise<WrongQuestionDetail> {
  return http.patch<WrongQuestionDetail>(`/wrong-questions/${encodeURIComponent(id)}`, payload);
}
