/**
 * 契约 §3 试卷与发布快照。
 */
import type { ApiSuccessEnvelope, ContentStatus } from './common';
import type { QuestionAnswer, QuestionOption, Rubric } from './question';

/** 契约 §3 PaperSummary */
export interface PaperSummary {
  id: string;
  title: string;
  status: ContentStatus;
  questionCount: number;
  totalScore: number;
  publishedAt: string | null;
  createdAt: string;
  updatedAt: string;
  latestSnapshotId: string | null;
}

/** 契约 §3 Paper.questions */
export interface PaperQuestionItem {
  id: string;
  questionId: string;
  questionVersionId: string;
  orderNo: number;
  score: number;
  title: string;
  questionType: string;
  versionNo: number;
}

/** 契约 §3 Paper */
export interface Paper extends PaperSummary {
  description: string | null;
  questions: PaperQuestionItem[];
}

/** 契约 §3 PaperSnapshot.questions */
export interface PaperSnapshotQuestion {
  id: string;
  orderNo: number;
  score: number;
  questionId: string;
  questionVersionId: string;
  questionType: string;
  stem: string;
  options: QuestionOption[] | null;
  answer: QuestionAnswer;
  explanation: string | null;
  rubric: Rubric | null;
  knowledgeTags: string[];
}

/** 契约 §3 PaperSnapshot */
export interface PaperSnapshot {
  id: string;
  paperId: string;
  versionNo: number;
  title: string;
  questionCount: number;
  totalScore: number;
  publishedAt: string;
  questions: PaperSnapshotQuestion[];
}

/** `GET /api/v1/papers` 查询参数 */
export interface PaperListQuery {
  page?: number;
  size?: number;
  q?: string;
  status?: ContentStatus;
}

/** POST / PUT body（契约 §3） */
export interface PaperUpsertQuestion {
  questionId: string;
  questionVersionId?: string;
  score?: number;
}

export interface PaperUpsertPayload {
  title: string;
  description?: string | null;
  questions: PaperUpsertQuestion[];
}

/** 发布响应就是 PaperSnapshot，包络便于类型标注 */
export type PaperPublishResponse = ApiSuccessEnvelope<PaperSnapshot>;
