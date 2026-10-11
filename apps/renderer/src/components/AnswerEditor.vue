<script setup lang="ts">
/**
 * 作答输入组件：按题型渲染输入控件，v-model 绑定 QuestionAnswer。
 * 支持单选/多选/判断/填空/简答论述，并展示保存状态。
 */
import { computed } from 'vue';
import { ElButton, ElCheckbox, ElCheckboxGroup, ElInput, ElRadio, ElRadioGroup, ElTag } from 'element-plus';

import type { QuestionAnswer, QuestionOption, QuestionType } from '@/types';

const props = defineProps<{
  questionType: QuestionType;
  options: QuestionOption[] | null;
  modelValue: QuestionAnswer;
  disabled?: boolean;
  /** 保存状态提示 */
  saveState?: 'idle' | 'pending' | 'saving' | 'saved' | 'failed';
  saveMessage?: string | null;
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: QuestionAnswer): void;
}>();

const current = computed<QuestionAnswer>(() => props.modelValue);
const isDisabled = computed(() => props.disabled === true);

const singleValue = computed<string>({
  get: () => (current.value.type === 'single_choice' ? current.value.value : ''),
  set: (value: string) => emit('update:modelValue', { type: 'single_choice', value })
});

const multipleValue = computed<string[]>({
  get: () => (current.value.type === 'multiple_choice' ? [...current.value.value] : []),
  set: (value: string[]) => emit('update:modelValue', { type: 'multiple_choice', value })
});

const booleanValue = computed<boolean>({
  get: () => (current.value.type === 'true_false' ? current.value.value : false),
  set: (value: boolean) => emit('update:modelValue', { type: 'true_false', value })
});

const blankItems = computed(() => {
  if (current.value.type !== 'fill_blank') return [];
  return [...current.value.blanks].sort((a, b) => a.index - b.index);
});

const subjectiveText = computed<string>({
  get: () => {
    if (current.value.type === 'short_answer' || current.value.type === 'essay') {
      return current.value.referenceAnswer ?? '';
    }
    return '';
  },
  set: (value: string) => {
    if (current.value.type === 'short_answer') {
      emit('update:modelValue', { type: 'short_answer', referenceAnswer: value });
      return;
    }
    if (current.value.type === 'essay') {
      emit('update:modelValue', { type: 'essay', referenceAnswer: value });
      return;
    }
    // 兜底：按题型构造新对象
    const type = props.questionType === 'essay' ? 'essay' : 'short_answer';
    emit('update:modelValue', { type, referenceAnswer: value });
  }
});

function updateBlank(index: number, text: string): void {
  if (current.value.type !== 'fill_blank') return;
  const blanks = current.value.blanks.map((blank) =>
    blank.index === index ? { ...blank, accepted: [text] } : blank
  );
  emit('update:modelValue', { type: 'fill_blank', blanks });
}

function addBlank(): void {
  if (current.value.type !== 'fill_blank') {
    emit('update:modelValue', { type: 'fill_blank', blanks: [{ index: 1, accepted: [''] }] });
    return;
  }
  const blanks = [...current.value.blanks];
  const nextIndex = blanks.reduce((max, blank) => Math.max(max, blank.index), 0) + 1;
  blanks.push({ index: nextIndex, accepted: [''], normalize: { ignoreCase: true, trim: true } });
  emit('update:modelValue', { type: 'fill_blank', blanks });
}

function removeBlank(index: number): void {
  if (current.value.type !== 'fill_blank') return;
  const remaining = current.value.blanks.filter((blank) => blank.index !== index);
  if (remaining.length === 0) return;
  emit('update:modelValue', {
    type: 'fill_blank',
    blanks: remaining.map((blank, position) => ({ ...blank, index: position + 1 }))
  });
}

const saveStateText = computed(() => {
  switch (props.saveState) {
    case 'pending':
      return '待保存…';
    case 'saving':
      return '保存中…';
    case 'saved':
      return '已保存';
    case 'failed':
      return '保存失败';
    default:
      return '';
  }
});

const saveStateType = computed<'info' | 'success' | 'danger' | 'warning'>(() => {
  if (props.saveState === 'failed') return 'danger';
  if (props.saveState === 'saved') return 'success';
  if (props.saveState === 'pending') return 'warning';
  return 'info';
});
</script>

<template>
  <div class="answer-editor">
    <div class="answer-editor__status">
      <ElTag v-if="saveStateText" size="small" :type="saveStateType">{{ saveStateText }}</ElTag>
      <span v-if="saveState === 'failed' && saveMessage" class="answer-editor__save-error">
        {{ saveMessage }}
      </span>
    </div>

    <!-- 单选 -->
    <ElRadioGroup
      v-if="questionType === 'single_choice'"
      v-model="singleValue"
      :disabled="isDisabled"
      class="answer-editor__choices"
    >
      <ElRadio v-for="option in options ?? []" :key="option.key" :value="option.key" class="answer-editor__choice">
        <span class="answer-editor__choice-key">{{ option.key }}</span>
        <span>{{ option.content }}</span>
      </ElRadio>
    </ElRadioGroup>

    <!-- 多选 -->
    <ElCheckboxGroup
      v-else-if="questionType === 'multiple_choice'"
      v-model="multipleValue"
      :disabled="isDisabled"
      class="answer-editor__choices"
    >
      <ElCheckbox
        v-for="option in options ?? []"
        :key="option.key"
        :value="option.key"
        class="answer-editor__choice"
      >
        <span class="answer-editor__choice-key">{{ option.key }}</span>
        <span>{{ option.content }}</span>
      </ElCheckbox>
    </ElCheckboxGroup>

    <!-- 判断 -->
    <ElRadioGroup
      v-else-if="questionType === 'true_false'"
      v-model="booleanValue"
      :disabled="isDisabled"
      class="answer-editor__choices"
    >
      <ElRadio :value="true">正确</ElRadio>
      <ElRadio :value="false">错误</ElRadio>
    </ElRadioGroup>

    <!-- 填空 -->
    <div v-else-if="questionType === 'fill_blank'" class="answer-editor__blanks">
      <div v-for="blank in blankItems" :key="blank.index" class="answer-editor__blank">
        <span class="answer-editor__blank-label">第 {{ blank.index }} 空</span>
        <ElInput
          :model-value="blank.accepted.join(' ')"
          :disabled="isDisabled"
          placeholder="请输入答案"
          clearable
          @update:model-value="(value: string) => updateBlank(blank.index, value)"
        />
        <ElButton
          v-if="!isDisabled && blankItems.length > 1"
          text
          type="danger"
          @click="removeBlank(blank.index)"
        >
          删除
        </ElButton>
      </div>
      <ElButton v-if="!isDisabled" text type="primary" @click="addBlank">增加空</ElButton>
    </div>

    <!-- 简答 / 论述 -->
    <div v-else class="answer-editor__subjective">
      <ElInput
        v-model="subjectiveText"
        type="textarea"
        :rows="6"
        :disabled="isDisabled"
        maxlength="5000"
        show-word-limit
        placeholder="请输入你的作答内容"
      />
      <p class="answer-editor__hint">
        主观题提交后将标记为「AI 建议分待复核」，由 AI 量表评分给出建议分，可人工复核。
      </p>
    </div>
  </div>
</template>

<style scoped>
.answer-editor {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.answer-editor__status {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-height: 22px;
}

.answer-editor__save-error {
  color: var(--color-danger);
  font-size: var(--font-size-xs);
}

.answer-editor__choices {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  align-items: stretch;
}

.answer-editor__choice {
  display: flex;
  align-items: flex-start;
  height: auto;
  margin: 0;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-muted);
  white-space: normal;
}

.answer-editor__choice :deep(.el-radio__label),
.answer-editor__choice :deep(.el-checkbox__label) {
  white-space: normal;
  word-break: break-word;
  line-height: var(--line-height-base);
}

.answer-editor__choice-key {
  display: inline-block;
  min-width: 18px;
  margin-right: var(--space-2);
  font-weight: var(--font-weight-semibold);
  color: var(--color-brand-600);
}

.answer-editor__blanks {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.answer-editor__blank {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.answer-editor__blank-label {
  min-width: 64px;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

.answer-editor__subjective {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.answer-editor__hint {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
}
</style>
