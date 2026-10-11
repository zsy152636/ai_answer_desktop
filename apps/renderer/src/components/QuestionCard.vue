<script setup lang="ts">
/** 题目展示组件：题干、选项、标准答案、解析 */
import { computed } from 'vue';
import { ElAlert, ElTag } from 'element-plus';

import type { QuestionAnswer, QuestionOption, QuestionType } from '@/types';
import { describeAnswer, describeChoiceKeys } from '@/utils/answer';
import { GRADING_METHOD_LABELS, QUESTION_TYPE_LABELS, labelOf } from '@/utils/labels';
import type { GradingMethod } from '@/types/common';

const props = withDefaults(
  defineProps<{
    orderNo?: number;
    questionType: QuestionType;
    stem: string;
    options?: QuestionOption[] | null;
    knowledgeTags?: string[];
    score?: number | null;
    maxScore?: number;
    /** 标准答案；答题中（未提交）应传 null */
    correctAnswer?: QuestionAnswer | null;
    userAnswer?: QuestionAnswer | null;
    explanation?: string | null;
    gradingMethod?: GradingMethod | null;
    gradingStatus?: string | null;
    /** 是否展示标准答案区 */
    showCorrectAnswer?: boolean;
    compact?: boolean;
  }>(),
  {
    orderNo: undefined,
    options: null,
    knowledgeTags: () => [],
    score: null,
    maxScore: undefined,
    correctAnswer: null,
    userAnswer: null,
    explanation: null,
    gradingMethod: null,
    gradingStatus: null,
    showCorrectAnswer: true,
    compact: false
  }
);

const correctKeys = computed<string[]>(() => {
  const answer = props.correctAnswer;
  if (!answer) return [];
  if (answer.type === 'single_choice') return [answer.value];
  if (answer.type === 'multiple_choice') return answer.value;
  return [];
});

const correctText = computed(() => {
  const answer = props.correctAnswer;
  if (!answer) return '';
  if (answer.type === 'single_choice' || answer.type === 'multiple_choice') {
    return describeChoiceKeys(correctKeys.value, props.options);
  }
  if (answer.type === 'true_false') return answer.value ? '正确' : '错误';
  if (answer.type === 'fill_blank') {
    return answer.blanks
      .slice()
      .sort((a, b) => a.index - b.index)
      .map((blank) => `第 ${blank.index} 空：${blank.accepted.join(' / ')}`)
      .join('；');
  }
  return (answer.referenceAnswer ?? '').trim() || '（未提供参考答案）';
});

const userText = computed(() => {
  if (!props.userAnswer) return '未作答';
  return describeAnswer(props.userAnswer);
});

const isSubjective = computed(
  () => props.questionType === 'short_answer' || props.questionType === 'essay'
);

/** 用户作答命中的选项 key（用于高亮已选项） */
const chosenKeys = computed<string[]>(() => {
  const answer = props.userAnswer;
  if (!answer) return [];
  if (answer.type === 'single_choice') return answer.value ? [answer.value] : [];
  if (answer.type === 'multiple_choice') return answer.value;
  return [];
});
</script>

<template>
  <article class="question-card" :class="{ 'question-card--compact': compact }">
    <header class="question-card__head">
      <span v-if="orderNo !== undefined" class="question-card__order">第 {{ orderNo }} 题</span>
      <ElTag size="small" type="info">{{ labelOf(QUESTION_TYPE_LABELS, questionType) }}</ElTag>
      <ElTag v-if="maxScore !== undefined" size="small" type="info">满分 {{ maxScore }}</ElTag>
      <ElTag
        v-if="score !== undefined && score !== null"
        size="small"
        :type="score > 0 ? 'success' : 'danger'"
      >
        得分 {{ score }}
      </ElTag>
      <ElTag v-if="gradingMethod" size="small" type="info">
        {{ labelOf(GRADING_METHOD_LABELS, gradingMethod) }}
      </ElTag>
    </header>

    <p class="question-card__stem">{{ stem }}</p>

    <ul v-if="options && options.length > 0" class="question-card__options">
      <li
        v-for="option in options"
        :key="option.key"
        class="question-card__option"
        :class="{
          'is-correct': showCorrectAnswer && correctKeys.includes(option.key),
          'is-chosen': chosenKeys.includes(option.key)
        }"
      >
        <span class="question-card__option-key">{{ option.key }}</span>
        <span class="question-card__option-content">{{ option.content }}</span>
        <ElTag v-if="showCorrectAnswer && correctKeys.includes(option.key)" size="small" type="success">
          正确项
        </ElTag>
      </li>
    </ul>

    <div v-if="!compact" class="question-card__answers">
      <p v-if="userAnswer !== null" class="question-card__answer-line">
        <span class="question-card__answer-label">你的答案</span>{{ userText }}
      </p>
      <p v-if="showCorrectAnswer" class="question-card__answer-line">
        <span class="question-card__answer-label">标准答案</span>{{ correctText }}
      </p>
    </div>

    <ElAlert
      v-if="showCorrectAnswer && explanation"
      class="question-card__explanation"
      type="info"
      :closable="false"
      title="解析"
    >
      <p class="question-card__explanation-text">{{ explanation }}</p>
    </ElAlert>

    <ElAlert
      v-if="showCorrectAnswer && isSubjective && !explanation"
      class="question-card__explanation"
      type="info"
      :closable="false"
      title="参考答案"
    >
      <p class="question-card__explanation-text">{{ correctText }}</p>
    </ElAlert>

    <div v-if="knowledgeTags.length > 0" class="question-card__tags">
      <ElTag v-for="tag in knowledgeTags" :key="tag" size="small" effect="plain">{{ tag }}</ElTag>
    </div>
  </article>
</template>

<style scoped>
.question-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
}

.question-card--compact {
  padding: var(--space-4);
}

.question-card__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.question-card__order {
  font-weight: var(--font-weight-semibold);
  color: var(--color-brand-600);
}

.question-card__stem {
  margin: 0 0 var(--space-4);
  font-size: var(--font-size-md);
  line-height: var(--line-height-base);
  white-space: pre-wrap;
  word-break: break-word;
}

.question-card__options {
  list-style: none;
  margin: 0 0 var(--space-4);
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.question-card__option {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-muted);
}

.question-card__option.is-correct {
  border-color: var(--color-success);
  background: var(--color-success-bg);
}

.question-card__option.is-chosen {
  border-color: var(--color-brand-400);
  box-shadow: inset 3px 0 0 var(--color-brand-500);
}

.question-card__option-key {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  height: 24px;
  border-radius: var(--radius-pill);
  background: var(--color-brand-50);
  color: var(--color-brand-700);
  font-weight: var(--font-weight-semibold);
  font-size: var(--font-size-xs);
}

.question-card__option-content {
  flex: 1;
  word-break: break-word;
}

.question-card__answers {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  margin-bottom: var(--space-3);
}

.question-card__answer-line {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  word-break: break-word;
}

.question-card__answer-label {
  display: inline-block;
  min-width: 68px;
  color: var(--color-text-muted);
}

.question-card__explanation {
  margin-top: var(--space-3);
}

.question-card__explanation-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.question-card__tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-top: var(--space-3);
}
</style>
