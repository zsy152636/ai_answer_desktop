/** 按题型构造/规整标准答案与用户作答 */
import type { QuestionAnswer, QuestionOption, QuestionType } from '@/types';
import { isChoiceType, isSubjectiveType } from './labels';

/** 按题型生成空的用户作答对象 */
export function createEmptyUserAnswer(type: QuestionType): QuestionAnswer {
  switch (type) {
    case 'single_choice':
      return { type: 'single_choice', value: '' };
    case 'multiple_choice':
      return { type: 'multiple_choice', value: [] };
    case 'true_false':
      return { type: 'true_false', value: false };
    case 'fill_blank':
      return { type: 'fill_blank', blanks: [{ index: 1, accepted: [''] }] };
    case 'short_answer':
      return { type: 'short_answer', referenceAnswer: '' };
    case 'essay':
      return { type: 'essay', referenceAnswer: '' };
  }
}

/** 按题型生成标准答案模板（题目编辑用） */
export function createEmptyStandardAnswer(type: QuestionType, optionKeys: string[] = []): QuestionAnswer {
  switch (type) {
    case 'single_choice':
      return { type: 'single_choice', value: optionKeys[0] ?? 'A' };
    case 'multiple_choice':
      return { type: 'multiple_choice', value: [], partialCredit: false };
    case 'true_false':
      return { type: 'true_false', value: true };
    case 'fill_blank':
      return { type: 'fill_blank', blanks: [{ index: 1, accepted: [''] }] };
    case 'short_answer':
      return {
        type: 'short_answer',
        referenceAnswer: '',
        rubric: { criteria: [{ id: 'c1', desc: '', weight: 100 }], maxScore: 10 }
      };
    case 'essay':
      return {
        type: 'essay',
        referenceAnswer: '',
        rubric: { criteria: [{ id: 'c1', desc: '', weight: 100 }], maxScore: 10 }
      };
  }
}

/** 用户作答是否为空（用于未答题提示） */
export function isUserAnswerEmpty(answer: QuestionAnswer | null | undefined, type: QuestionType): boolean {
  if (!answer) return true;
  switch (type) {
    case 'single_choice':
      return answer.type === 'single_choice' ? answer.value.trim() === '' : true;
    case 'multiple_choice':
      return answer.type === 'multiple_choice' ? answer.value.length === 0 : true;
    case 'true_false':
      // 判断题没有「未作答」的表达，视为已作答
      return false;
    case 'fill_blank':
      return answer.type === 'fill_blank'
        ? answer.blanks.every((blank) => blank.accepted.every((item) => item.trim() === ''))
        : true;
    case 'short_answer':
    case 'essay':
      return answer.type === 'short_answer' || answer.type === 'essay'
        ? (answer.referenceAnswer ?? '').trim() === ''
        : true;
  }
}

/** 把选项数组压缩成题干展示所需的 key 列表 */
export function optionKeys(options: QuestionOption[] | null | undefined): string[] {
  if (!options) return [];
  return options.map((option) => option.key);
}

/** 自动生成下一个选项 key：A、B、C… */
export function nextOptionKey(options: QuestionOption[]): string {
  const used = new Set(options.map((option) => option.key));
  for (let index = 0; index < 26; index += 1) {
    const key = String.fromCharCode(65 + index);
    if (!used.has(key)) return key;
  }
  return `K${options.length + 1}`;
}

/** 作答列表 → 可读文本（结果页/错题页展示「你的答案」） */
export function describeAnswer(answer: QuestionAnswer | null | undefined): string {
  if (!answer) return '未作答';
  switch (answer.type) {
    case 'single_choice':
      return answer.value || '未作答';
    case 'multiple_choice':
      return answer.value.length > 0 ? [...answer.value].sort().join('、') : '未作答';
    case 'true_false':
      return answer.value ? '正确' : '错误';
    case 'fill_blank': {
      const parts = answer.blanks
        .slice()
        .sort((a, b) => a.index - b.index)
        .map((blank, index) => `第 ${blank.index || index + 1} 空：${blank.accepted.join(' / ') || '未作答'}`);
      return parts.length > 0 ? parts.join('；') : '未作答';
    }
    case 'short_answer':
    case 'essay':
      return (answer.referenceAnswer ?? '').trim() || '未作答';
  }
}

/** 选项 key → 文本，用于展示标准答案 */
export function describeChoiceKeys(keys: string[], options: QuestionOption[] | null | undefined): string {
  if (keys.length === 0) return '未作答';
  if (!options) return keys.join('、');
  const map = new Map(options.map((option) => [option.key, option.content]));
  return keys
    .slice()
    .sort()
    .map((key) => {
      const content = map.get(key);
      return content ? `${key}. ${content}` : key;
    })
    .join('；');
}

/** 该题型是否需要选项集合 */
export function requiresOptions(type: QuestionType): boolean {
  return isChoiceType(type);
}

export function isSubjective(type: QuestionType): boolean {
  return isSubjectiveType(type);
}
