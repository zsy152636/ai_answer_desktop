<script setup lang="ts">
/**
 * 题目编辑：题干、题型、难度、选项增删与正确项、答案、解析、知识点标签、
 * 版本历史侧栏、未保存离开提示、expectedVersionNo 乐观锁。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElCheckbox,
  ElCheckboxGroup,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElRadio,
  ElRadioGroup,
  ElSelect,
  ElSwitch,
  ElTag,
  ElTimeline,
  ElTimelineItem
} from 'element-plus';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';

import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import * as questionsApi from '@/api/questions';
import type {
  QuestionAnswer,
  QuestionOption,
  QuestionType,
  QuestionVersionBrief,
  Rubric,
  RubricCriterion
} from '@/types';
import { QUESTION_TYPES } from '@/types/common';
import { nextOptionKey } from '@/utils/answer';
import { confirmAction, toastError, toastSuccess } from '@/utils/confirm';
import { describeError } from '@/utils/error';
import { formatDateTime } from '@/utils/format';
import { QUESTION_TYPE_LABELS } from '@/utils/labels';

const route = useRoute();
const router = useRouter();

const questionId = computed(() => {
  const raw = route.params.id;
  return typeof raw === 'string' && raw.length > 0 ? raw : null;
});
const isEdit = computed(() => questionId.value !== null);
const isPublished = ref(false);

interface CriteriaRow {
  id: string;
  desc: string;
  weight: number;
}

interface FormState {
  title: string;
  questionType: QuestionType;
  difficulty: number;
  stem: string;
  options: QuestionOption[];
  explanation: string;
  knowledgeTags: string[];
  changeNote: string;
  expectedVersionNo: number | null;
  /** 单选/多选/判断/填空答案的通用承载 */
  singleValue: string;
  multipleValue: string[];
  booleanValue: boolean;
  blanks: { index: number; accepted: string; ignoreCase: boolean; trim: boolean }[];
  referenceAnswer: string;
  criteria: CriteriaRow[];
  rubricMaxScore: number;
  partialCredit: boolean;
}

function emptyCriteria(): CriteriaRow[] {
  return [{ id: 'c1', desc: '', weight: 100 }];
}

const form = reactive<FormState>({
  title: '',
  questionType: 'single_choice',
  difficulty: 2,
  stem: '',
  options: [
    { key: 'A', content: '', correct: false },
    { key: 'B', content: '', correct: false },
    { key: 'C', content: '', correct: false },
    { key: 'D', content: '', correct: false }
  ],
  explanation: '',
  knowledgeTags: [],
  changeNote: '',
  expectedVersionNo: null,
  singleValue: '',
  multipleValue: [],
  booleanValue: true,
  blanks: [{ index: 1, accepted: '', ignoreCase: true, trim: true }],
  referenceAnswer: '',
  criteria: emptyCriteria(),
  rubricMaxScore: 10,
  partialCredit: false
});

const loading = ref(false);
const saving = ref(false);
const loadError = ref<ReturnType<typeof describeError> | null>(null);
const fieldErrors = ref<Record<string, string>>({});
const globalError = ref<string | null>(null);
const versions = ref<QuestionVersionBrief[]>([]);
const versionsLoading = ref(false);
const versionsError = ref<string | null>(null);
const dirty = ref(false);
const loadedSnapshot = ref<string>('');

const isChoice = computed(
  () => form.questionType === 'single_choice' || form.questionType === 'multiple_choice'
);
const isSubjective = computed(
  () => form.questionType === 'short_answer' || form.questionType === 'essay'
);
const canEditContent = computed(() => !isPublished.value);

const criteriaWeightTotal = computed(() =>
  form.criteria.reduce((total, item) => total + (Number.isFinite(item.weight) ? item.weight : 0), 0)
);

const shouldGateLeave = computed(() => dirty.value && canEditContent.value);

function snapshot(): string {
  return JSON.stringify({
    title: form.title,
    questionType: form.questionType,
    difficulty: form.difficulty,
    stem: form.stem,
    options: form.options,
    explanation: form.explanation,
    knowledgeTags: [...form.knowledgeTags].sort(),
    singleValue: form.singleValue,
    multipleValue: [...form.multipleValue].sort(),
    booleanValue: form.booleanValue,
    blanks: form.blanks,
    referenceAnswer: form.referenceAnswer,
    criteria: form.criteria,
    rubricMaxScore: form.rubricMaxScore,
    partialCredit: form.partialCredit
  });
}

watch(
  () => snapshot(),
  (value) => {
    dirty.value = loadedSnapshot.value !== '' && value !== loadedSnapshot.value;
  }
);

/** 题型变化时重置答案载体，避免残留与题型不匹配的答案 */
watch(
  () => form.questionType,
  (type) => {
    if (type === 'single_choice' && !form.options.some((option) => option.key === form.singleValue)) {
      form.singleValue = form.options.find((option) => option.correct)?.key ?? '';
    }
    if (type === 'multiple_choice') {
      form.multipleValue = form.options.filter((option) => option.correct).map((option) => option.key);
    }
  }
);

function toggleCorrect(option: QuestionOption): void {
  if (!canEditContent.value) return;
  if (form.questionType === 'single_choice') {
    for (const item of form.options) item.correct = item.key === option.key;
    form.singleValue = option.key;
    return;
  }
  if (form.questionType === 'multiple_choice') {
    option.correct = !option.correct;
    form.multipleValue = form.options.filter((item) => item.correct).map((item) => item.key);
  }
}

function addOption(): void {
  if (form.options.length >= 10) {
    toastError('选择题最多 10 个选项。');
    return;
  }
  form.options.push({ key: nextOptionKey(form.options), content: '', correct: false });
}

function removeOption(key: string): void {
  if (form.options.length <= 2) {
    toastError('选择题至少保留 2 个选项。');
    return;
  }
  form.options = form.options.filter((option) => option.key !== key);
  if (form.singleValue === key) form.singleValue = '';
  form.multipleValue = form.multipleValue.filter((item) => item !== key);
}

function addBlank(): void {
  const nextIndex = form.blanks.reduce((max, blank) => Math.max(max, blank.index), 0) + 1;
  form.blanks.push({ index: nextIndex, accepted: '', ignoreCase: true, trim: true });
}

function removeBlank(index: number): void {
  if (form.blanks.length <= 1) {
    toastError('填空题至少保留 1 个空。');
    return;
  }
  form.blanks = form.blanks
    .filter((blank) => blank.index !== index)
    .map((blank, position) => ({ ...blank, index: position + 1 }));
}

function addCriterion(): void {
  form.criteria.push({ id: `c${form.criteria.length + 1}`, desc: '', weight: 0 });
}

function removeCriterion(id: string): void {
  if (form.criteria.length <= 1) {
    toastError('至少保留 1 条评分标准。');
    return;
  }
  form.criteria = form.criteria.filter((item) => item.id !== id);
}

/** 前端预校验，与契约 §2.2 校验规则一致 */
function validateLocally(): Record<string, string> {
  const errors: Record<string, string> = {};
  if (form.title.trim().length === 0) errors.title = '标题不能为空。';
  else if (form.title.length > 200) errors.title = '标题最长 200 个字符。';
  if (form.stem.trim().length === 0) errors.stem = '题干不能为空。';
  else if (form.stem.length > 5000) errors.stem = '题干最长 5000 个字符。';

  if (isChoice.value) {
    if (form.options.length < 2 || form.options.length > 10) {
      errors.options = '选择题需要 2-10 个选项。';
    } else {
      const keys = form.options.map((option) => option.key);
      if (new Set(keys).size !== keys.length) errors.options = '选项 key 必须唯一。';
      if (form.options.some((option) => option.content.trim().length === 0)) {
        errors.options = '选项内容不能为空。';
      }
      const correctKeys = new Set(form.options.filter((option) => option.correct).map((option) => option.key));
      if (form.questionType === 'single_choice') {
        if (!form.singleValue || !correctKeys.has(form.singleValue)) {
          errors.answer = '请将某个选项标记为正确，并选择标准答案。';
        }
      } else if (form.multipleValue.length === 0 || form.multipleValue.some((key) => !correctKeys.has(key))) {
        errors.answer = '多选题标准答案必须命中已标记为正确的选项。';
      }
    }
  }

  if (form.questionType === 'fill_blank') {
    if (form.blanks.length === 0) errors.answer = '填空题至少需要 1 个空。';
    else if (form.blanks.some((blank) => blank.accepted.trim().length === 0)) {
      errors.answer = '每个空的接受答案不能为空。';
    }
  }

  if (isSubjective.value) {
    if (form.criteria.length === 0) errors.rubric = '主观题需要至少 1 条评分标准。';
    else if (criteriaWeightTotal.value !== 100 && form.rubricMaxScore <= 0) {
      errors.rubric = '评分标准权重合计需为 100，或提供一个大于 0 的满分值。';
    }
  }

  return errors;
}

function buildAnswer(): QuestionAnswer {
  switch (form.questionType) {
    case 'single_choice':
      return { type: 'single_choice', value: form.singleValue };
    case 'multiple_choice':
      return {
        type: 'multiple_choice',
        value: [...form.multipleValue].sort(),
        partialCredit: form.partialCredit
      };
    case 'true_false':
      return { type: 'true_false', value: form.booleanValue };
    case 'fill_blank':
      return {
        type: 'fill_blank',
        blanks: form.blanks.map((blank) => ({
          index: blank.index,
          accepted: blank.accepted
            .split(/\r?\n|,|，/)
            .map((item) => item.trim())
            .filter((item) => item.length > 0),
          normalize: {
            ignoreCase: blank.ignoreCase,
            trim: blank.trim,
            ignorePunctuation: true,
            ignoreWhitespace: true
          }
        }))
      };
    case 'short_answer':
      return {
        type: 'short_answer',
        referenceAnswer: form.referenceAnswer,
        rubric: { criteria: form.criteria.map((item) => ({ ...item })), maxScore: form.rubricMaxScore }
      };
    case 'essay':
      return {
        type: 'essay',
        referenceAnswer: form.referenceAnswer,
        rubric: { criteria: form.criteria.map((item) => ({ ...item })), maxScore: form.rubricMaxScore }
      };
  }
}

function applyQuestion(question: {
  title: string;
  questionType: QuestionType;
  difficulty: number;
  status: string;
  currentVersion: {
    stem: string;
    options: QuestionOption[] | null;
    answer: QuestionAnswer;
    explanation: string | null;
    rubric: { criteria: { id: string; desc: string; weight: number }[]; maxScore: number } | null;
    knowledgeTags: string[];
    versionNo: number;
    changeNote: string | null;
  };
}): void {
  form.title = question.title;
  form.questionType = question.questionType;
  form.difficulty = question.difficulty;
  form.stem = question.currentVersion.stem;
  form.options = (question.currentVersion.options ?? []).map((option) => ({ ...option }));
  form.explanation = question.currentVersion.explanation ?? '';
  form.knowledgeTags = [...question.currentVersion.knowledgeTags];
  form.expectedVersionNo = question.currentVersion.versionNo;
  form.changeNote = '';
  form.singleValue = '';
  form.multipleValue = [];
  form.booleanValue = true;
  form.blanks = [{ index: 1, accepted: '', ignoreCase: true, trim: true }];
  form.referenceAnswer = '';
  form.criteria = emptyCriteria();
  form.rubricMaxScore = 10;
  form.partialCredit = false;

  const answer = question.currentVersion.answer;
  switch (answer.type) {
    case 'single_choice':
      form.singleValue = answer.value;
      break;
    case 'multiple_choice':
      form.multipleValue = [...answer.value];
      form.partialCredit = answer.partialCredit === true;
      break;
    case 'true_false':
      form.booleanValue = answer.value;
      break;
    case 'fill_blank':
      form.blanks = answer.blanks.map((blank) => ({
        index: blank.index,
        accepted: blank.accepted.join('\n'),
        ignoreCase: blank.normalize?.ignoreCase !== false,
        trim: blank.normalize?.trim !== false
      }));
      break;
    case 'short_answer':
    case 'essay':
      form.referenceAnswer = answer.referenceAnswer ?? '';
      break;
  }

  const answerRubric = answer.type === 'short_answer' || answer.type === 'essay' ? answer.rubric ?? null : null;
  const rubric: Rubric | null = question.currentVersion.rubric ?? answerRubric;
  if (rubric) {
    form.criteria =
      rubric.criteria.length > 0
        ? rubric.criteria.map((item: RubricCriterion) => ({ ...item }))
        : emptyCriteria();
    form.rubricMaxScore = rubric.maxScore;
  }

  isPublished.value = question.status === 'published';
  loadedSnapshot.value = snapshot();
  dirty.value = false;
}

async function loadQuestion(): Promise<void> {
  if (!questionId.value) {
    loadedSnapshot.value = snapshot();
    dirty.value = false;
    return;
  }
  loading.value = true;
  loadError.value = null;
  try {
    const question = await questionsApi.getQuestion(questionId.value);
    applyQuestion(question);
  } catch (caught) {
    loadError.value = describeError(caught);
  } finally {
    loading.value = false;
  }
}

async function loadVersions(): Promise<void> {
  if (!questionId.value) return;
  versionsLoading.value = true;
  versionsError.value = null;
  try {
    versions.value = await questionsApi.listQuestionVersions(questionId.value);
  } catch (caught) {
    versionsError.value = describeError(caught).text;
  } finally {
    versionsLoading.value = false;
  }
}

async function save(): Promise<void> {
  fieldErrors.value = {};
  globalError.value = null;
  const localErrors = validateLocally();
  if (Object.keys(localErrors).length > 0) {
    fieldErrors.value = localErrors;
    toastError('表单校验未通过，请修正标出的字段。');
    return;
  }

  saving.value = true;
  try {
    const payload = {
      title: form.title.trim(),
      questionType: form.questionType,
      difficulty: form.difficulty,
      stem: form.stem,
      options: isChoice.value ? form.options.map((option) => ({ ...option })) : null,
      answer: buildAnswer(),
      explanation: form.explanation.trim() ? form.explanation : null,
      // 后端 SaveRequest 同时接受顶层 rubric（独立于 answer），主观题量表两边一起写
      rubric: isSubjective.value
        ? { criteria: form.criteria.map((item) => ({ ...item })), maxScore: form.rubricMaxScore }
        : null,
      knowledgeTags: form.knowledgeTags,
      changeNote: form.changeNote.trim() ? form.changeNote.trim() : null
    };

    if (questionId.value) {
      const updated = await questionsApi.updateQuestion(questionId.value, {
        ...payload,
        expectedVersionNo: form.expectedVersionNo ?? undefined
      });
      toastSuccess('保存成功');
      applyQuestion(updated);
      await loadVersions();
    } else {
      const created = await questionsApi.createQuestion(payload);
      toastSuccess('创建成功');
      loadedSnapshot.value = snapshot();
      dirty.value = false;
      await router.replace(`/questions/${created.id}/edit`);
    }
  } catch (caught) {
    const info = describeError(caught);
    globalError.value = info.text;
    fieldErrors.value = info.fieldErrors;
    if (info.code === 'VERSION_CONFLICT') {
      globalError.value = `版本冲突：题目已被其他修改更新（当前提交版本 v${form.expectedVersionNo ?? '—'}）。请重新载入最新内容后再保存。`;
    }
    toastError(info.text);
  } finally {
    saving.value = false;
  }
}

async function reloadLatest(): Promise<void> {
  const confirmed = await confirmAction('重新载入将丢弃当前未保存的修改。确认继续？', {
    title: '重新载入最新版本'
  });
  if (!confirmed) return;
  await loadQuestion();
  await loadVersions();
}

onBeforeRouteLeave(async () => {
  if (!shouldGateLeave.value) return true;
  const confirmed = await confirmAction('当前题目有未保存的修改，离开将丢失这些修改。确认离开？', {
    title: '未保存的修改',
    confirmText: '离开',
    type: 'warning'
  });
  return confirmed;
});

function onKnowledgeTagsChange(value: string[]): void {
  form.knowledgeTags = value;
}

onMounted(async () => {
  await loadQuestion();
  await loadVersions();
});
</script>

<template>
  <div class="question-edit">
    <PageHeader
      :title="isEdit ? '编辑题目' : '新建题目'"
      :description="
        isEdit
          ? '题干、选项、答案、量表或标签的修改会创建新版本；仅标题与难度修改不产生新版本。'
          : '创建题目将同时生成 version 1，初始状态为草稿。'
      "
      :breadcrumbs="[
        { label: '题库', to: '/questions' },
        { label: isEdit ? '编辑题目' : '新建题目' }
      ]"
    >
      <template #actions>
        <ElTag v-if="isEdit" :type="isPublished ? 'success' : 'info'">
          {{ isPublished ? '已发布（内容锁定）' : '草稿' }}
        </ElTag>
        <ElButton :disabled="!dirty || saving" @click="reloadLatest">放弃修改并重载</ElButton>
        <ElButton type="primary" :loading="saving" :disabled="!canEditContent" @click="save">
          保存
        </ElButton>
      </template>
    </PageHeader>

    <StatePanel
      :state="loading ? 'loading' : loadError ? 'error' : 'ready'"
      :description="loadError?.message ?? ''"
      :code="loadError?.code ?? null"
      :request-id="loadError?.requestId ?? null"
      :retryable="loadError?.retryable ?? false"
      @retry="loadQuestion"
    >
      <div class="question-edit__layout">
        <div class="question-edit__main">
          <ElAlert
            v-if="globalError"
            class="question-edit__banner"
            type="error"
            :closable="false"
            title="保存失败"
            :description="globalError"
          />
          <ElAlert
            v-else-if="isPublished"
            class="question-edit__banner"
            type="warning"
            :closable="false"
            title="已发布题目"
            description="已发布题目的版本内容不可原地修改。保存时若改动了题干/选项/答案/量表/标签，后端会创建新版本；如需全新语义，建议新建题目。"
          />

          <ElForm label-position="top" class="question-edit__form">
            <div class="question-edit__row">
              <ElFormItem
                label="标题"
                required
                class="question-edit__grow"
                :error="fieldErrors.title"
              >
                <ElInput
                  v-model="form.title"
                  maxlength="200"
                  show-word-limit
                  placeholder="例如：TCP 三次握手"
                  :disabled="!canEditContent"
                />
              </ElFormItem>
              <ElFormItem label="题型" required :error="fieldErrors.questionType">
                <ElSelect v-model="form.questionType" :disabled="!canEditContent" class="question-edit__type">
                  <ElOption
                    v-for="type in QUESTION_TYPES"
                    :key="type"
                    :label="QUESTION_TYPE_LABELS[type]"
                    :value="type"
                  />
                </ElSelect>
              </ElFormItem>
              <ElFormItem label="难度" required :error="fieldErrors.difficulty">
                <ElInputNumber v-model="form.difficulty" :min="1" :max="5" :disabled="!canEditContent" />
              </ElFormItem>
            </div>

            <ElFormItem label="题干" required :error="fieldErrors.stem">
              <ElInput
                v-model="form.stem"
                type="textarea"
                :rows="4"
                maxlength="5000"
                show-word-limit
                placeholder="请输入题干内容"
                :disabled="!canEditContent"
              />
            </ElFormItem>

            <ElFormItem v-if="isChoice" label="选项（勾选正确项）" required :error="fieldErrors.options">
              <div class="question-edit__options">
                <div v-for="option in form.options" :key="option.key" class="question-edit__option">
                  <span class="question-edit__option-key">{{ option.key }}</span>
                  <ElInput
                    v-model="option.content"
                    placeholder="选项内容"
                    :disabled="!canEditContent"
                  />
                  <ElCheckbox
                    :model-value="option.correct"
                    :disabled="!canEditContent"
                    @change="toggleCorrect(option)"
                  >
                    正确项
                  </ElCheckbox>
                  <ElButton
                    text
                    type="danger"
                    :disabled="!canEditContent"
                    @click="removeOption(option.key)"
                  >
                    删除
                  </ElButton>
                </div>
                <ElButton text type="primary" :disabled="!canEditContent" @click="addOption">
                  增加选项
                </ElButton>
              </div>
            </ElFormItem>

            <ElFormItem v-if="form.questionType === 'single_choice'" label="标准答案" required :error="fieldErrors.answer">
              <ElRadioGroup v-model="form.singleValue" :disabled="!canEditContent">
                <ElRadio v-for="option in form.options" :key="option.key" :value="option.key">
                  {{ option.key }}
                </ElRadio>
              </ElRadioGroup>
            </ElFormItem>

            <template v-if="form.questionType === 'multiple_choice'">
              <ElFormItem label="标准答案（多选）" required :error="fieldErrors.answer">
                <ElCheckboxGroup v-model="form.multipleValue" :disabled="!canEditContent">
                  <ElCheckbox v-for="option in form.options" :key="option.key" :value="option.key">
                    {{ option.key }}
                  </ElCheckbox>
                </ElCheckboxGroup>
              </ElFormItem>
              <ElFormItem label="部分得分">
                <ElSwitch v-model="form.partialCredit" :disabled="!canEditContent" />
                <span class="question-edit__hint">
                  开启后按 (命中 - 错误) / 正确数 归一化并夹到 [0,1]（partial_credit）。
                </span>
              </ElFormItem>
            </template>

            <ElFormItem v-if="form.questionType === 'true_false'" label="标准答案" required :error="fieldErrors.answer">
              <ElRadioGroup v-model="form.booleanValue" :disabled="!canEditContent">
                <ElRadio :value="true">正确</ElRadio>
                <ElRadio :value="false">错误</ElRadio>
              </ElRadioGroup>
            </ElFormItem>

            <ElFormItem v-if="form.questionType === 'fill_blank'" label="填空答案" required :error="fieldErrors.answer">
              <div class="question-edit__blanks">
                <div v-for="blank in form.blanks" :key="blank.index" class="question-edit__blank">
                  <span class="question-edit__option-key">第 {{ blank.index }} 空</span>
                  <ElInput
                    v-model="blank.accepted"
                    type="textarea"
                    :rows="2"
                    placeholder="每行一个可接受答案"
                    :disabled="!canEditContent"
                          />
                  <div class="question-edit__blank-flags">
                    <ElCheckbox v-model="blank.ignoreCase" :disabled="!canEditContent">忽略大小写</ElCheckbox>
                    <ElCheckbox v-model="blank.trim" :disabled="!canEditContent">忽略首尾空白</ElCheckbox>
                    <ElButton text type="danger" :disabled="!canEditContent" @click="removeBlank(blank.index)">
                      删除该空
                    </ElButton>
                  </div>
                </div>
                <ElButton text type="primary" :disabled="!canEditContent" @click="addBlank">增加空</ElButton>
              </div>
            </ElFormItem>

            <template v-if="isSubjective">
              <ElFormItem label="参考答案">
                <ElInput
                  v-model="form.referenceAnswer"
                  type="textarea"
                  :rows="4"
                  placeholder="供人工复核与 AI 量表评分参考"
                  :disabled="!canEditContent"
                />
              </ElFormItem>
              <ElFormItem label="评分量表（rubric）" :error="fieldErrors.rubric">
                <div class="question-edit__rubric">
                  <div v-for="criterion in form.criteria" :key="criterion.id" class="question-edit__criterion">
                    <ElInput v-model="criterion.id" placeholder="标准 id" class="question-edit__criterion-id" :disabled="!canEditContent" />
                    <ElInput v-model="criterion.desc" placeholder="评分标准描述" :disabled="!canEditContent" />
                    <ElInputNumber v-model="criterion.weight" :min="0" :max="100" :disabled="!canEditContent" />
                    <span class="question-edit__hint">权重</span>
                    <ElButton text type="danger" :disabled="!canEditContent" @click="removeCriterion(criterion.id)">
                      删除
                    </ElButton>
                  </div>
                  <div class="question-edit__rubric-footer">
                    <ElButton text type="primary" :disabled="!canEditContent" @click="addCriterion">
                      增加评分标准
                    </ElButton>
                    <span class="question-edit__hint">
                      权重合计：{{ criteriaWeightTotal }}（需为 100，或提供满分值）
                    </span>
                    <span class="question-edit__hint">满分</span>
                    <ElInputNumber v-model="form.rubricMaxScore" :min="0" :max="100" :disabled="!canEditContent" />
                  </div>
                </div>
              </ElFormItem>
            </template>

            <ElFormItem label="解析">
              <ElInput
                v-model="form.explanation"
                type="textarea"
                :rows="3"
                placeholder="面向学习者的解析内容"
                :disabled="!canEditContent"
              />
            </ElFormItem>

            <ElFormItem label="知识点标签">
              <ElSelect
                :model-value="form.knowledgeTags"
                multiple
                filterable
                allow-create
                default-first-option
                placeholder="输入后回车创建标签"
                :disabled="!canEditContent"
                class="question-edit__tags"
                @update:model-value="onKnowledgeTagsChange"
              >
                <ElOption v-for="tag in form.knowledgeTags" :key="tag" :label="tag" :value="tag" />
              </ElSelect>
            </ElFormItem>

            <ElFormItem label="版本说明（changeNote）">
              <ElInput
                v-model="form.changeNote"
                maxlength="200"
                placeholder="例如：补充解析"
                :disabled="!canEditContent"
              />
            </ElFormItem>
          </ElForm>
        </div>

        <aside class="question-edit__aside">
          <ElCard shadow="never" class="question-edit__card">
            <template #header>
              <div class="question-edit__card-header">
                <span>版本历史</span>
                <ElButton text size="small" @click="loadVersions">刷新</ElButton>
              </div>
            </template>
            <p v-if="isEdit" class="question-edit__meta">
              当前版本：v{{ form.expectedVersionNo ?? '—' }}（乐观锁字段 expectedVersionNo）
            </p>
            <StatePanel
              :state="versionsLoading ? 'loading' : versionsError ? 'error' : versions.length === 0 ? 'empty' : 'ready'"
              :description="versionsError ?? '该题目暂无历史版本记录。'"
              :retryable="Boolean(versionsError)"
              :skeleton-rows="3"
              @retry="loadVersions"
            >
              <ElTimeline>
                <ElTimelineItem
                  v-for="version in versions"
                  :key="version.id"
                  :timestamp="formatDateTime(version.createdAt)"
                  placement="top"
                >
                  <p class="question-edit__version-title">v{{ version.versionNo }}</p>
                  <p class="question-edit__version-note">{{ version.changeNote || '（无版本说明）' }}</p>
                </ElTimelineItem>
              </ElTimeline>
            </StatePanel>
          </ElCard>

          <ElCard shadow="never" class="question-edit__card">
            <template #header><span>校验规则提示</span></template>
            <ul class="question-edit__rules">
              <li>标题 1-200 字符；题干 1-5000 字符。</li>
              <li>选择题 2-10 个选项，key 唯一，标准答案必须命中正确项。</li>
              <li>判断题无选项；填空题至少 1 个空。</li>
              <li>主观题评分标准权重合计为 100，或提供大于 0 的满分值。</li>
              <li>并发保存使用 expectedVersionNo；不匹配会返回 VERSION_CONFLICT。</li>
            </ul>
          </ElCard>
        </aside>
      </div>
    </StatePanel>
  </div>
</template>

<style scoped>
.question-edit__layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: var(--space-4);
  align-items: start;
}

@media (max-width: 1180px) {
  .question-edit__layout {
    grid-template-columns: minmax(0, 1fr);
  }
}

.question-edit__main {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
}

.question-edit__banner {
  margin-bottom: var(--space-4);
}

.question-edit__row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-4);
}

.question-edit__grow {
  flex: 1 1 320px;
}

.question-edit__type {
  width: 170px;
}

.question-edit__options,
.question-edit__blanks,
.question-edit__rubric {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  width: 100%;
}

.question-edit__option {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.question-edit__option-key {
  min-width: 56px;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

.question-edit__blank {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.question-edit__blank-flags {
  display: flex;
  align-items: center;
  gap: var(--space-4);
}

.question-edit__criterion {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.question-edit__criterion-id {
  width: 110px;
}

.question-edit__rubric-footer {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.question-edit__tags {
  width: 100%;
}

.question-edit__hint {
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
}

.question-edit__aside {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.question-edit__card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.question-edit__meta {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}

.question-edit__version-title {
  margin: 0;
  font-weight: var(--font-weight-semibold);
}

.question-edit__version-note {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
}

.question-edit__rules {
  margin: 0;
  padding-left: var(--space-5);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: var(--line-height-base);
}
</style>
