/** 契约 §0.5 枚举与业务标签的中文映射、样式类名 */
import type {
  AttemptMode,
  AttemptStatus,
  ContentStatus,
  GradingMethod,
  GradingStatus,
  QuestionType,
  TaskStatus,
  WrongStatus
} from '@/types/common';
import type { LlmTestCategory } from '@/types/llm';
import type { DocumentSourceKind, DocumentStatus, SearchMatchMode } from '@/types/document';

type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info';

export const QUESTION_TYPE_LABELS: Record<QuestionType, string> = {
  single_choice: '单选题',
  multiple_choice: '多选题',
  true_false: '判断题',
  fill_blank: '填空题',
  short_answer: '简答题',
  essay: '论述题'
};

export const CONTENT_STATUS_LABELS: Record<ContentStatus, string> = {
  draft: '草稿',
  published: '已发布',
  archived: '已归档'
};

export const CONTENT_STATUS_TAG: Record<ContentStatus, TagType> = {
  draft: 'info',
  published: 'success',
  archived: 'warning'
};

export const ATTEMPT_STATUS_LABELS: Record<AttemptStatus, string> = {
  in_progress: '进行中',
  submitted: '已提交',
  graded: '已判分',
  abandoned: '已放弃'
};

export const ATTEMPT_STATUS_TAG: Record<AttemptStatus, TagType> = {
  in_progress: 'primary',
  submitted: 'warning',
  graded: 'success',
  abandoned: 'info'
};

export const ATTEMPT_MODE_LABELS: Record<AttemptMode, string> = {
  paper: '整卷作答',
  wrong_practice: '错题练习'
};

export const GRADING_METHOD_LABELS: Record<GradingMethod, string> = {
  exact: '精确匹配',
  normalized: '归一化匹配',
  partial_credit: '部分得分',
  ai_rubric: 'AI 量表评分',
  manual: '人工复核',
  ungraded: '未判分'
};

export const GRADING_STATUS_LABELS: Record<GradingStatus, string> = {
  graded: '已判分',
  pending_review: '待复核',
  failed: '判分失败'
};

export const WRONG_STATUS_LABELS: Record<WrongStatus, string> = {
  active: '待复习',
  reviewing: '复习中',
  mastered: '已掌握',
  archived: '已归档'
};

export const WRONG_STATUS_TAG: Record<WrongStatus, TagType> = {
  active: 'danger',
  reviewing: 'warning',
  mastered: 'success',
  archived: 'info'
};

export const TASK_STATUS_LABELS: Record<TaskStatus, string> = {
  queued: '排队中',
  running: '执行中',
  succeeded: '已完成',
  retry_wait: '等待重试',
  failed: '失败',
  cancelled: '已取消'
};

export const LLM_TEST_CATEGORY_LABELS: Record<LlmTestCategory, string> = {
  ok: '连接正常',
  dns: 'DNS 解析失败',
  tls: 'TLS 握手失败',
  auth: '鉴权失败',
  model_not_found: '模型不存在',
  quota: '额度/限流',
  timeout: '连接超时',
  protocol: '协议不兼容',
  blocked: '地址被安全策略拒绝',
  unavailable: '服务不可用'
};

export const DIFFICULTY_LABELS: Record<number, string> = {
  1: '入门',
  2: '简单',
  3: '中等',
  4: '较难',
  5: '困难'
};

/** 契约 §11.1 资料状态 */
export const DOCUMENT_STATUS_LABELS: Record<DocumentStatus, string> = {
  imported: '已导入',
  indexed: '已建索引',
  failed: '导入失败'
};

export const DOCUMENT_STATUS_TAG: Record<DocumentStatus, TagType> = {
  imported: 'warning',
  indexed: 'success',
  failed: 'danger'
};

/** 契约 §11.1 资料来源类型（首期只支持文本 / Markdown） */
export const DOCUMENT_SOURCE_KIND_LABELS: Record<DocumentSourceKind, string> = {
  text: '纯文本',
  markdown: 'Markdown'
};

/** 契约 §11.1 检索模式（keyword 表示 FTS5 不可用或命中不足时的降级路径） */
export const SEARCH_MATCH_MODE_LABELS: Record<SearchMatchMode, string> = {
  fts5: 'FTS5 全文检索',
  keyword: '关键词降级'
};

/** 题目需要选项的题型 */
export const CHOICE_TYPES: QuestionType[] = ['single_choice', 'multiple_choice'];

/** 主观题题型（提交后 gradingStatus = pending_review） */
export const SUBJECTIVE_TYPES: QuestionType[] = ['short_answer', 'essay'];

export function isChoiceType(type: QuestionType | string): boolean {
  return type === 'single_choice' || type === 'multiple_choice';
}

export function isSubjectiveType(type: QuestionType | string): boolean {
  return type === 'short_answer' || type === 'essay';
}

export function labelOf<T extends string>(
  map: Record<T, string>,
  key: T | null | undefined,
  fallback = '—'
): string {
  if (!key) return fallback;
  return map[key] ?? fallback;
}

/**
 * 表格插槽中的 `row` 由 Element Plus 提供为宽松类型，
 * 这里提供按字符串键安全查表的辅助函数，避免在模板中做类型断言。
 */
export function labelFrom(map: Record<string, string>, key: unknown, fallback = '—'): string {
  if (typeof key !== 'string') return fallback;
  return map[key] ?? fallback;
}

export function tagFrom<T extends string>(
  map: Record<T, TagType>,
  key: unknown,
  fallback: TagType = 'info'
): TagType {
  if (typeof key !== 'string') return fallback;
  return map[key as T] ?? fallback;
}
