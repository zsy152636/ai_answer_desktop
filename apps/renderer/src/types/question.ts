/**
 * 契约 §2 题库与题目版本。
 */
import type { ContentStatus, QuestionType } from './common';

/** 选择题选项 */
export interface QuestionOption {
  key: string;
  content: string;
  correct: boolean;
}

/** 填空归一化规则（契约 §2.1 fill_blank） */
export interface FillBlankNormalize {
  ignoreCase?: boolean;
  trim?: boolean;
  ignorePunctuation?: boolean;
  ignoreWhitespace?: boolean;
}

export interface FillBlankSlot {
  index: number;
  accepted: string[];
  normalize?: FillBlankNormalize;
}

/** 主观题评分量表（契约 §2.1 short_answer / essay） */
export interface RubricCriterion {
  id: string;
  desc: string;
  weight: number;
}

export interface Rubric {
  criteria: RubricCriterion[];
  maxScore: number;
}

export interface SingleChoiceAnswer {
  type: 'single_choice';
  value: string;
}
export interface MultipleChoiceAnswer {
  type: 'multiple_choice';
  value: string[];
  partialCredit?: boolean;
}
export interface TrueFalseAnswer {
  type: 'true_false';
  value: boolean;
}
export interface FillBlankAnswer {
  type: 'fill_blank';
  blanks: FillBlankSlot[];
}
export interface SubjectiveAnswer {
  type: 'short_answer' | 'essay';
  referenceAnswer?: string;
  rubric?: Rubric;
}
/** 契约未显式定义但判分需要 `essay` 变体，此处用联合类型覆盖 */
export interface EssayAnswer {
  type: 'essay';
  referenceAnswer?: string;
  rubric?: Rubric;
}

export type QuestionAnswer =
  | SingleChoiceAnswer
  | MultipleChoiceAnswer
  | TrueFalseAnswer
  | FillBlankAnswer
  | SubjectiveAnswer
  | EssayAnswer;

/** 版本列表条目（契约 §2.1 Question.versions） */
export interface QuestionVersionSummary {
  id: string;
  versionNo: number;
  createdAt: string;
  changeNote: string | null;
}

/** 当前版本完整内容 */
export interface QuestionVersion {
  id: string;
  versionNo: number;
  stem: string;
  options: QuestionOption[] | null;
  answer: QuestionAnswer;
  explanation: string | null;
  rubric: Rubric | null;
  knowledgeTags: string[];
  createdAt: string;
  changeNote: string | null;
}

/** 契约 §2.1 QuestionSummary */
export interface QuestionSummary {
  id: string;
  title: string;
  questionType: QuestionType;
  status: ContentStatus;
  currentVersionNo: number;
  knowledgeTags: string[];
  difficulty: number;
  updatedAt: string;
}

/** 契约 §2.1 Question */
export interface Question {
  id: string;
  title: string;
  questionType: QuestionType;
  status: ContentStatus;
  difficulty: number;
  currentVersion: QuestionVersion;
  versions: QuestionVersionSummary[];
  createdAt: string;
  updatedAt: string;
}

/** `GET /api/v1/questions` 查询参数 */
export interface QuestionListQuery {
  page?: number;
  size?: number;
  q?: string;
  questionType?: QuestionType;
  status?: ContentStatus;
  tag?: string;
  sort?: QuestionSort;
}

export const QUESTION_SORTS = [
  'updatedAt',
  '-updatedAt',
  'createdAt',
  '-createdAt'
] as const;
export type QuestionSort = (typeof QUESTION_SORTS)[number];

/**
 * POST / PUT body（Java `QuestionDtos.SaveRequest`）。
 * 后端同时接受顶层 `rubric`（与 `answer` 独立），主观题评分量表可放在这里。
 */
export interface QuestionUpsertPayload {
  title: string;
  questionType: QuestionType;
  difficulty: number;
  stem: string;
  options?: QuestionOption[] | null;
  answer: QuestionAnswer;
  explanation?: string | null;
  rubric?: Rubric | null;
  knowledgeTags?: string[];
  changeNote?: string | null;
  /** 乐观锁：PUT 时必传 */
  expectedVersionNo?: number;
}

/** `DELETE /api/v1/questions/{id}` 成功返回 */
export interface QuestionDeleteResponse {
  deleted: boolean;
  id: string;
}

/** `GET /api/v1/questions/{id}/versions`（Java `VersionBrief`） */
export interface QuestionVersionBrief {
  id: string;
  versionNo: number;
  createdAt: string;
  changeNote: string | null;
}
