/**
 * 契约 §4 答题与判分。
 *
 * 字段与 Java 实现（`services/java-api` 的 `AttemptDtos`）逐字段对齐：
 *  - `AttemptSummary` 含 `paperSnapshotId`
 *  - `SheetQuestion` 在未提交时 `answer` / `explanation` 为 `null`（不泄露答案）
 *  - `ResultItem.correct` 为非空布尔
 *  - `AttemptResult` 直接带 `correctCount/wrongCount/unansweredCount/pendingReviewCount`
 *  - 主观题复核闭环：`POST /attempts/{id}/ai-grade` 排队 → 轮询任务 →
 *    `POST /attempts/{id}/answers/{snapshotQuestionId}/grade` 人工确认
 */
import type {
  AttemptMode,
  AttemptStatus,
  GradingMethod,
  GradingStatus,
  QuestionType
} from './common';
import type { QuestionAnswer, QuestionOption } from './question';

/** 契约 §4 AttemptAnswer */
export interface AttemptAnswer {
  snapshotQuestionId: string;
  questionId: string;
  questionVersionId: string;
  answer: QuestionAnswer | null;
  score: number | null;
  maxScore: number;
  gradingMethod: GradingMethod;
  gradingStatus: GradingStatus;
  gradingDetail: Record<string, unknown> | null;
  savedAt: string | null;
}

/** 契约 §4 AttemptSheet.questions 条目；未提交（in_progress）时 answer/explanation 为 null */
export interface AttemptSheetQuestion {
  snapshotQuestionId: string;
  orderNo: number;
  score: number;
  questionType: QuestionType;
  stem: string;
  options: QuestionOption[] | null;
  knowledgeTags: string[];
  /** 仅当 attempt.status !== 'in_progress' 时返回；未提交时为 null */
  answer: QuestionAnswer | null;
  /** 仅当 attempt.status !== 'in_progress' 时返回；未提交时为 null */
  explanation: string | null;
}

/** 契约 §4 AttemptSheet */
export interface AttemptSheet {
  id: string;
  paperId: string | null;
  paperSnapshotId: string;
  paperTitle: string;
  mode: AttemptMode;
  status: AttemptStatus;
  totalScore: number | null;
  maxScore: number;
  startedAt: string;
  submittedAt: string | null;
  gradedAt: string | null;
  questions: AttemptSheetQuestion[];
  answers: AttemptAnswer[];
}

/** 契约 §1 AttemptSummary（同时用于仪表盘、列表与结果页 attempt 字段） */
export interface AttemptSummary {
  id: string;
  paperId: string | null;
  paperSnapshotId: string;
  paperTitle: string;
  mode: AttemptMode;
  status: AttemptStatus;
  totalScore: number | null;
  maxScore: number;
  questionCount: number;
  startedAt: string;
  submittedAt: string | null;
  gradedAt: string | null;
}

/** 契约 §4 AttemptResult.items */
export interface AttemptResultItem {
  snapshotQuestionId: string;
  orderNo: number;
  questionType: QuestionType;
  stem: string;
  options: QuestionOption[] | null;
  userAnswer: QuestionAnswer | null;
  correctAnswer: QuestionAnswer | null;
  explanation: string | null;
  score: number | null;
  maxScore: number;
  /** 非空布尔：服务端保证有值 */
  correct: boolean;
  gradingMethod: GradingMethod;
  gradingStatus: GradingStatus;
  gradingDetail: Record<string, unknown> | null;
  knowledgeTags: string[];
}

/** 契约 §4 AttemptResult（统计数由服务端给出，前端不再自行计算） */
export interface AttemptResult {
  attempt: AttemptSummary;
  items: AttemptResultItem[];
  correctCount: number;
  wrongCount: number;
  unansweredCount: number;
  pendingReviewCount: number;
}

/** `POST /api/v1/attempts` body */
export type CreateAttemptPayload =
  | { paperId: string }
  | { mode: 'wrong_practice'; wrongQuestionIds?: string[]; limit?: number };

/** `PUT /api/v1/attempts/{id}/answers/{snapshotQuestionId}` body */
export interface SaveAnswerPayload {
  answer: QuestionAnswer;
}

export interface AttemptListQuery {
  page?: number;
  size?: number;
  status?: AttemptStatus;
  paperId?: string;
}

/** `POST /api/v1/attempts/{id}/ai-grade` → data */
export interface AiGradeEnqueueResponse {
  taskIds: string[];
  pendingCount: number;
}

/** `POST /api/v1/attempts/{id}/answers/{snapshotQuestionId}/grade` body */
export interface ManualGradeRequest {
  score: number;
  note?: string;
}

/**
 * AI 建议分明细（`gradingDetail` 内的稳定字段，见 TaskService#executeAiGrade）。
 * 键名与后端写入保持一致，读取时按 unknown 收窄。
 */
export interface AiGradingDetail {
  ruleVersion?: string;
  model?: string;
  confidence?: number;
  needsReview?: boolean;
  suggestedScore?: number;
  latencyMs?: number;
  note?: string;
  criteria?: { id?: string; score?: number; reason?: string }[];
}

/** 从 `gradingDetail` 安全读取 AI 建议分明细 */
export function readAiGradingDetail(
  detail: Record<string, unknown> | null | undefined
): AiGradingDetail | null {
  if (!detail) return null;
  const result: AiGradingDetail = {};
  if (typeof detail.ruleVersion === 'string') result.ruleVersion = detail.ruleVersion;
  if (typeof detail.model === 'string') result.model = detail.model;
  if (typeof detail.confidence === 'number') result.confidence = detail.confidence;
  if (typeof detail.needsReview === 'boolean') result.needsReview = detail.needsReview;
  if (typeof detail.suggestedScore === 'number') result.suggestedScore = detail.suggestedScore;
  if (typeof detail.latencyMs === 'number') result.latencyMs = detail.latencyMs;
  if (typeof detail.note === 'string') result.note = detail.note;
  if (Array.isArray(detail.criteria)) {
    result.criteria = detail.criteria
      .filter((item): item is Record<string, unknown> => typeof item === 'object' && item !== null)
      .map((item) => {
        const criterion: { id?: string; score?: number; reason?: string } = {};
        if (typeof item.id === 'string') criterion.id = item.id;
        if (typeof item.score === 'number') criterion.score = item.score;
        if (typeof item.reason === 'string') criterion.reason = item.reason;
        return criterion;
      });
  }
  return Object.keys(result).length > 0 ? result : null;
}
