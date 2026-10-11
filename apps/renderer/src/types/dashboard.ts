/**
 * 契约 §1 `GET /api/v1/dashboard`（Java `SystemDtos.DashboardView`）。
 */
import type { AttemptSummary } from './attempt';
import type { WrongQuestion } from './wrongQuestion';
import type { AiServiceStatus } from './system';

/** 契约 §1 dashboard */
export interface DashboardData {
  questionCount: number;
  publishedQuestionCount: number;
  paperCount: number;
  publishedPaperCount: number;
  inProgressAttemptCount: number;
  dueWrongQuestionCount: number;
  activeWrongQuestionCount: number;
  masteredWrongQuestionCount: number;
  recentAttempts: AttemptSummary[];
  recentWrongQuestions: WrongQuestion[];
  aiService: AiServiceStatus;
}
