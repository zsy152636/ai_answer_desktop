/**
 * 契约 §5 错题闭环。
 * 字段与 Java 实现（`services/java-api` 的 `WrongDtos`）逐字段对齐：
 *  - `WrongQuestionView` 直接带 `currentVersionNo` / `wrongVersionNo`，前端无需额外请求题目
 *  - 复习记录为 `ReviewRecord`，用 `intervalDaysBefore/After` + `nextReviewAt` 表达调度变化
 */
import type { QuestionType, WrongStatus } from './common';
import type { AttemptSheet } from './attempt';

/** 契约 §5 WrongQuestion（Java `WrongQuestionView`） */
export interface WrongQuestion {
  id: string;
  questionId: string;
  questionVersionId: string;
  questionType: QuestionType;
  title: string;
  stem: string;
  status: WrongStatus;
  wrongCount: number;
  correctStreak: number;
  lastWrongAt: string | null;
  nextReviewAt: string | null;
  lastScore: number | null;
  maxScore: number;
  knowledgeTags: string[];
  /** 题目当前版本号 */
  currentVersionNo: number;
  /** 该错题绑定的题目版本号 */
  wrongVersionNo: number;
  createdAt: string;
  updatedAt: string;
}

/** 复习记录（Java `ReviewRecord`） */
export interface WrongQuestionReview {
  id: string;
  result: 'correct' | 'wrong';
  note: string | null;
  /** 复习前的复习间隔天数 */
  intervalDaysBefore: number | null;
  /** 复习后的复习间隔天数 */
  intervalDaysAfter: number | null;
  /** 复习后的下次复习时间；已掌握时为 null */
  nextReviewAt: string | null;
  durationSeconds: number | null;
  reviewedAt: string;
}

/** `GET /api/v1/wrong-questions/{id}`、review、PATCH 的返回 */
export interface WrongQuestionDetail {
  question: WrongQuestion;
  reviews: WrongQuestionReview[];
}

export interface WrongQuestionListQuery {
  page?: number;
  size?: number;
  status?: WrongStatus;
  dueOnly?: boolean;
  tag?: string;
  questionType?: QuestionType;
  sort?: WrongQuestionSort;
}

/**
 * 后端白名单（`WrongQuestionRepository#orderClause`）：
 * `-updatedAt | updatedAt | nextReviewAt | lastWrongAt | wrongCount`；
 * 不传时默认 `-updatedAt`。传其他值返回 400 VALIDATION_ERROR。
 */
export const WRONG_QUESTION_SORTS = [
  '-updatedAt',
  'updatedAt',
  'nextReviewAt',
  'lastWrongAt',
  'wrongCount'
] as const;
export type WrongQuestionSort = (typeof WRONG_QUESTION_SORTS)[number];

export const DEFAULT_WRONG_QUESTION_SORT: WrongQuestionSort = '-updatedAt';

/** `POST /api/v1/wrong-questions/{id}/review` body */
export interface ReviewWrongQuestionPayload {
  result: 'correct' | 'wrong';
  note?: string;
  durationSeconds?: number;
  attemptAnswerId?: string;
}

/** `POST /api/v1/wrong-questions/practice` body */
export interface WrongPracticePayload {
  wrongQuestionIds?: string[];
  limit?: number;
}

/** 练习创建的 attempt（返回 AttemptSheet） */
export type WrongPracticeResponse = AttemptSheet;

/** `PATCH /api/v1/wrong-questions/{id}` body */
export interface PatchWrongQuestionPayload {
  status: Extract<WrongStatus, 'active' | 'reviewing' | 'mastered' | 'archived'>;
}

/**
 * 版本差异判定：错题绑定的版本号与题目当前版本号不一致即提示。
 * 两个字段都由 `GET /wrong-questions` / `GET /wrong-questions/{id}` 直接返回，无需额外请求。
 */
export function hasVersionDrift(item: {
  currentVersionNo: number;
  wrongVersionNo: number;
}): boolean {
  return item.currentVersionNo !== item.wrongVersionNo;
}
