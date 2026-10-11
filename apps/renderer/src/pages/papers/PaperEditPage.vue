<script setup lang="ts">
/**
 * 试卷编辑：从题库挑选题目、排序、逐题分值、总分实时校验、预览、发布（发布前确认）。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue';
import {
  ElAlert,
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag
} from 'element-plus';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';

import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import * as papersApi from '@/api/papers';
import * as questionsApi from '@/api/questions';
import type { ContentStatus, QuestionType } from '@/types/common';
import { QUESTION_TYPES } from '@/types/common';
import type { QuestionSummary } from '@/types/question';
import { confirmAction, toastError, toastSuccess } from '@/utils/confirm';
import { describeError } from '@/utils/error';
import { QUESTION_TYPE_LABELS } from '@/utils/labels';

const route = useRoute();
const router = useRouter();

const paperId = computed(() => {
  const raw = route.params.id;
  return typeof raw === 'string' && raw.length > 0 ? raw : null;
});
const isEdit = computed(() => paperId.value !== null);

interface PaperLine {
  /** 试卷题目关系 id；新建时为空 */
  lineId: string | null;
  questionId: string;
  questionVersionId: string | null;
  versionNo: number | null;
  orderNo: number;
  score: number;
  title: string;
  questionType: QuestionType;
  questionStatus: ContentStatus;
}

const form = reactive({
  title: '',
  description: ''
});
const lines = ref<PaperLine[]>([]);
const paperStatus = ref<ContentStatus>('draft');
const loading = ref(false);
const saving = ref(false);
const publishing = ref(false);
const loadError = ref<ReturnType<typeof describeError> | null>(null);
const fieldErrors = ref<Record<string, string>>({});
const globalError = ref<string | null>(null);
const dirty = ref(false);
const loadedSnapshot = ref('');

const pickerVisible = ref(false);
const previewVisible = ref(false);
const pickerLoading = ref(false);
const pickerError = ref<string | null>(null);
const pickerItems = ref<QuestionSummary[]>([]);
const pickerPage = ref(1);
const pickerSize = ref(10);
const pickerTotal = ref(0);
const pickerQuery = reactive({ q: '', questionType: '' as QuestionType | '' });

const totalScore = computed(() => lines.value.reduce((total, line) => total + (line.score || 0), 0));
const questionCount = computed(() => lines.value.length);
const totalScoreValid = computed(() => totalScore.value > 0);
const canEdit = computed(() => paperStatus.value === 'draft');
const duplicateQuestionIds = computed(() => {
  const seen = new Set<string>();
  const duplicates = new Set<string>();
  for (const line of lines.value) {
    if (seen.has(line.questionId)) duplicates.add(line.questionId);
    seen.add(line.questionId);
  }
  return duplicates;
});

function snapshot(): string {
  return JSON.stringify({
    title: form.title,
    description: form.description,
    lines: lines.value.map((line) => ({
      questionId: line.questionId,
      questionVersionId: line.questionVersionId,
      score: line.score
    }))
  });
}

watch(
  () => snapshot(),
  (value) => {
    dirty.value = loadedSnapshot.value !== '' && value !== loadedSnapshot.value;
  }
);

function reindex(): void {
  lines.value = lines.value.map((line, index) => ({ ...line, orderNo: index + 1 }));
}

function moveUp(index: number): void {
  if (index <= 0) return;
  const next = [...lines.value];
  const current = next[index];
  const previous = next[index - 1];
  if (!current || !previous) return;
  next[index - 1] = current;
  next[index] = previous;
  lines.value = next;
  reindex();
}

function moveDown(index: number): void {
  if (index >= lines.value.length - 1) return;
  moveUp(index + 1);
}

function removeLine(index: number): void {
  lines.value = lines.value.filter((_, position) => position !== index);
  reindex();
}

function applyPaper(paper: {
  title: string;
  description: string | null;
  status: ContentStatus;
  questions: {
    id: string;
    questionId: string;
    questionVersionId: string;
    orderNo: number;
    score: number;
    title: string;
    questionType: string;
    versionNo: number;
  }[];
}): void {
  form.title = paper.title;
  form.description = paper.description ?? '';
  paperStatus.value = paper.status;
  lines.value = paper.questions
    .slice()
    .sort((a, b) => a.orderNo - b.orderNo)
    .map((item) => ({
      lineId: item.id,
      questionId: item.questionId,
      questionVersionId: item.questionVersionId,
      versionNo: item.versionNo,
      orderNo: item.orderNo,
      score: item.score,
      title: item.title,
      questionType: item.questionType as QuestionType,
      questionStatus: paper.status
    }));
  loadedSnapshot.value = snapshot();
  dirty.value = false;
}

async function loadPaper(): Promise<void> {
  if (!paperId.value) {
    loadedSnapshot.value = snapshot();
    dirty.value = false;
    return;
  }
  loading.value = true;
  loadError.value = null;
  try {
    const paper = await papersApi.getPaper(paperId.value);
    applyPaper(paper);
  } catch (caught) {
    loadError.value = describeError(caught);
  } finally {
    loading.value = false;
  }
}

async function loadPicker(): Promise<void> {
  pickerLoading.value = true;
  pickerError.value = null;
  try {
    const query: Parameters<typeof questionsApi.listQuestions>[0] = {
      page: pickerPage.value,
      size: pickerSize.value,
      status: 'published',
      sort: '-updatedAt'
    };
    if (pickerQuery.q.trim()) query.q = pickerQuery.q.trim();
    if (pickerQuery.questionType) query.questionType = pickerQuery.questionType;
    const result = await questionsApi.listQuestions(query);
    pickerItems.value = result.items;
    pickerTotal.value = result.total;
  } catch (caught) {
    pickerError.value = describeError(caught).text;
    pickerItems.value = [];
    pickerTotal.value = 0;
  } finally {
    pickerLoading.value = false;
  }
}

function openPicker(): void {
  pickerVisible.value = true;
  pickerPage.value = 1;
  void loadPicker();
}

function addQuestion(item: QuestionSummary): void {
  if (duplicateQuestionIds.value.has(item.id)) {
    toastError(`「${item.title}」已在试卷中。`);
    return;
  }
  lines.value = [
    ...lines.value,
    {
      lineId: null,
      questionId: item.id,
      questionVersionId: null,
      versionNo: item.currentVersionNo,
      orderNo: lines.value.length + 1,
      score: 5,
      title: item.title,
      questionType: item.questionType,
      questionStatus: item.status
    }
  ];
  reindex();
}

function validateLocally(): Record<string, string> {
  const errors: Record<string, string> = {};
  if (form.title.trim().length === 0) errors.title = '试卷标题不能为空。';
  if (lines.value.length === 0) errors.questions = '试卷至少需要 1 道题目。';
  if (totalScore.value <= 0) errors.totalScore = '总分必须大于 0。';
  if (lines.value.some((line) => line.score <= 0)) errors.totalScore = '每道题的分值必须大于 0。';
  if (duplicateQuestionIds.value.size > 0) errors.questions = '同一题目不能重复添加。';
  return errors;
}

function buildPayload(): Parameters<typeof papersApi.createPaper>[0] {
  return {
    title: form.title.trim(),
    description: form.description.trim() ? form.description : null,
    questions: lines.value.map((line) => {
      const item: { questionId: string; questionVersionId?: string; score?: number } = {
        questionId: line.questionId,
        score: line.score
      };
      if (line.questionVersionId) item.questionVersionId = line.questionVersionId;
      return item;
    })
  };
}

async function save(): Promise<boolean> {
  fieldErrors.value = {};
  globalError.value = null;
  const localErrors = validateLocally();
  if (Object.keys(localErrors).length > 0) {
    fieldErrors.value = localErrors;
    toastError('试卷校验未通过，请修正标出的问题。');
    return false;
  }

  saving.value = true;
  try {
    if (paperId.value) {
      const updated = await papersApi.updatePaper(paperId.value, buildPayload());
      applyPaper(updated);
      toastSuccess('保存成功');
    } else {
      const created = await papersApi.createPaper(buildPayload());
      toastSuccess('创建成功');
      loadedSnapshot.value = snapshot();
      dirty.value = false;
      await router.replace(`/papers/${created.id}/edit`);
    }
    return true;
  } catch (caught) {
    const info = describeError(caught);
    globalError.value = info.text;
    fieldErrors.value = info.fieldErrors;
    toastError(info.text);
    return false;
  } finally {
    saving.value = false;
  }
}

async function publish(): Promise<void> {
  if (!paperId.value) return;
  const saved = await save();
  if (!saved) return;

  const confirmed = await confirmAction(
    `发布「${form.title}」将生成不可变快照（${questionCount.value} 题 / 总分 ${totalScore.value}）。\n` +
      '发布后旧快照与既有作答结果永不改变；引用的题目版本必须存在且未归档。确认发布？',
    { title: '发布试卷', confirmText: '发布' }
  );
  if (!confirmed) return;

  publishing.value = true;
  try {
    const createdPaper = paperId.value;
    await papersApi.publishPaper(createdPaper);
    toastSuccess('发布成功，已生成快照');
    await loadPaper();
  } catch (caught) {
    const info = describeError(caught);
    globalError.value = info.text;
    toastError(info.text);
  } finally {
    publishing.value = false;
  }
}

async function reloadLatest(): Promise<void> {
  const confirmed = await confirmAction('重新载入将丢弃当前未保存的修改。确认继续？', {
    title: '重新载入'
  });
  if (!confirmed) return;
  await loadPaper();
}

onBeforeRouteLeave(async () => {
  if (!dirty.value || !canEdit.value) return true;
  const confirmed = await confirmAction('试卷有未保存的修改，离开将丢失这些修改。确认离开？', {
    title: '未保存的修改',
    confirmText: '离开'
  });
  return confirmed;
});

onMounted(() => {
  void loadPaper();
});
</script>

<template>
  <div class="paper-edit">
    <PageHeader
      :title="isEdit ? '编辑试卷' : '新建试卷'"
      :description="isEdit ? '仅草稿状态可修改组成与分值；已发布试卷需归档后另行处理。' : '从题库挑选已发布题目，设置顺序与分值后保存为草稿。'"
      :breadcrumbs="[{ label: '试卷', to: '/papers' }, { label: isEdit ? '编辑试卷' : '新建试卷' }]"
    >
      <template #actions>
        <ElTag v-if="isEdit" :type="paperStatus === 'draft' ? 'info' : paperStatus === 'published' ? 'success' : 'warning'">
          {{ paperStatus === 'draft' ? '草稿' : paperStatus === 'published' ? '已发布' : '已归档' }}
        </ElTag>
        <ElButton :disabled="!dirty || saving" @click="reloadLatest">放弃修改并重载</ElButton>
        <ElButton :disabled="lines.length === 0" @click="previewVisible = true">预览</ElButton>
        <ElButton :disabled="!canEdit" :loading="saving" @click="save">保存</ElButton>
        <ElButton type="primary" :disabled="!canEdit || !isEdit" :loading="publishing" @click="publish">
          发布
        </ElButton>
      </template>
    </PageHeader>

    <StatePanel
      :state="loading ? 'loading' : loadError ? 'error' : 'ready'"
      :description="loadError?.message ?? ''"
      :code="loadError?.code ?? null"
      :request-id="loadError?.requestId ?? null"
      :retryable="loadError?.retryable ?? false"
      @retry="loadPaper"
    >
      <ElAlert
        v-if="globalError"
        class="paper-edit__banner"
        type="error"
        :closable="false"
        title="操作失败"
        :description="globalError"
      />

      <section class="panel paper-edit__meta">
        <ElForm label-position="top">
          <ElFormItem label="试卷标题" required :error="fieldErrors.title">
            <ElInput v-model="form.title" maxlength="200" show-word-limit :disabled="!canEdit" placeholder="例如：网络基础单元测验" />
          </ElFormItem>
          <ElFormItem label="试卷说明">
            <ElInput
              v-model="form.description"
              type="textarea"
              :rows="2"
              maxlength="1000"
              :disabled="!canEdit"
              placeholder="可选：说明适用范围、注意事项"
            />
          </ElFormItem>
        </ElForm>
      </section>

      <section class="panel">
        <div class="paper-edit__section-head">
          <h2 class="paper-edit__section-title">题目组成</h2>
          <div class="toolbar">
            <span class="paper-edit__summary">
              共 <strong>{{ questionCount }}</strong> 题 ·
              总分
              <strong :class="{ 'text-danger': !totalScoreValid }">{{ totalScore }}</strong>
            </span>
            <ElButton type="primary" size="small" :disabled="!canEdit" @click="openPicker">从题库添加题目</ElButton>
          </div>
        </div>

        <ElAlert
          v-if="!totalScoreValid"
          type="warning"
          :closable="false"
          class="paper-edit__banner"
          title="总分校验"
          description="试卷题数需 ≥1 且总分 >0 才能发布；请检查是否遗漏题目或分值为 0。"
        />

        <StatePanel
          :state="lines.length === 0 ? 'empty' : 'ready'"
          title="试卷还没有题目"
          description="点击「从题库添加题目」挑选已发布题目，之后可调整顺序与逐题分值。"
          action-text="从题库添加题目"
          @action="openPicker"
        >
          <ElTable :data="lines" row-key="orderNo">
            <ElTableColumn label="顺序" width="90">
              <template #default="{ $index }">
                <span class="paper-edit__order">{{ Number($index) + 1 }}</span>
                <ElButton text size="small" :disabled="!canEdit || Number($index) === 0" @click="moveUp(Number($index))">↑</ElButton>
                <ElButton
                  text
                  size="small"
                  :disabled="!canEdit || Number($index) === lines.length - 1"
                  @click="moveDown(Number($index))"
                >
                  ↓
                </ElButton>
              </template>
            </ElTableColumn>
            <ElTableColumn label="题目" min-width="240">
              <template #default="{ row }">
                <span class="paper-edit__title">{{ row.title }}</span>
                <ElTag v-if="row.questionVersionId" size="small" effect="plain" class="paper-edit__version-tag">
                  锁定版本 v{{ row.versionNo }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="题型" width="110">
              <template #default="{ row }">{{ QUESTION_TYPE_LABELS[row.questionType as QuestionType] ?? '—' }}</template>
            </ElTableColumn>
            <ElTableColumn label="分值" width="150">
              <template #default="{ row }">
                <ElInputNumber
                  v-model="row.score"
                  :min="1"
                  :max="1000"
                  :disabled="!canEdit"
                  size="small"
                  controls-position="right"
                />
              </template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="90">
              <template #default="{ $index }">
                <ElButton text type="danger" :disabled="!canEdit" @click="removeLine(Number($index))">移除</ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
        </StatePanel>
      </section>
    </StatePanel>

    <!-- 题库选题对话框 -->
    <ElDialog v-model="pickerVisible" title="从题库挑选题目" width="860px" top="6vh">
      <div class="toolbar paper-edit__picker-toolbar">
        <ElInput
          v-model="pickerQuery.q"
          placeholder="搜索题目"
          clearable
          class="paper-edit__picker-search"
          @keyup.enter="pickerPage = 1; loadPicker()"
        />
        <ElSelect v-model="pickerQuery.questionType" placeholder="题型" clearable class="paper-edit__picker-select">
          <ElOption
            v-for="type in QUESTION_TYPES"
            :key="type"
            :label="QUESTION_TYPE_LABELS[type]"
            :value="type"
          />
        </ElSelect>
        <ElButton type="primary" @click="pickerPage = 1; loadPicker()">查询</ElButton>
        <span class="text-muted paper-edit__picker-hint">仅可挑选「已发布」题目，默认每题 5 分。</span>
      </div>

      <StatePanel
        :state="pickerLoading ? 'loading' : pickerError ? 'error' : pickerItems.length === 0 ? 'empty' : 'ready'"
        :description="pickerError ?? '没有符合条件的已发布题目，请先在题库中发布题目。'"
        :retryable="Boolean(pickerError)"
        :skeleton-rows="4"
        @retry="loadPicker"
      >
        <ElTable :data="pickerItems" height="360" row-key="id">
          <ElTableColumn label="标题" min-width="220" prop="title" />
          <ElTableColumn label="题型" width="100">
            <template #default="{ row }">{{ QUESTION_TYPE_LABELS[row.questionType as QuestionType] ?? '—' }}</template>
          </ElTableColumn>
          <ElTableColumn label="版本" width="80">
            <template #default="{ row }">v{{ row.currentVersionNo }}</template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="100">
            <template #default="{ row }">
              <ElButton size="small" type="primary" @click="addQuestion(row as QuestionSummary)">添加</ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <ElPagination
          class="paper-edit__picker-pager"
          :current-page="pickerPage"
          :page-size="pickerSize"
          :total="pickerTotal"
          layout="total, prev, pager, next"
          background
          @current-change="(value: number) => { pickerPage = value; loadPicker(); }"
        />
      </StatePanel>
    </ElDialog>

    <!-- 预览对话框 -->
    <ElDialog v-model="previewVisible" title="试卷预览" width="780px" top="6vh">
      <div class="paper-edit__preview">
        <h2 class="paper-edit__preview-title">{{ form.title || '（未命名试卷）' }}</h2>
        <p class="text-muted paper-edit__preview-meta">
          共 {{ questionCount }} 题 · 总分 {{ totalScore }}
          <span v-if="form.description"> · {{ form.description }}</span>
        </p>
        <ol class="paper-edit__preview-list">
          <li v-for="line in lines" :key="line.orderNo" class="paper-edit__preview-item">
            <span class="paper-edit__preview-name">{{ line.title }}</span>
            <ElTag size="small" type="info">{{ QUESTION_TYPE_LABELS[line.questionType] ?? '—' }}</ElTag>
            <ElTag size="small">{{ line.score }} 分</ElTag>
          </li>
        </ol>
      </div>
    </ElDialog>
  </div>
</template>

<style scoped>
.paper-edit__banner {
  margin-bottom: var(--space-4);
}

.paper-edit__meta {
  margin-bottom: var(--space-4);
}

.paper-edit__section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
  margin-bottom: var(--space-4);
}

.paper-edit__section-title {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-semibold);
}

.paper-edit__summary {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.paper-edit__order {
  display: inline-block;
  min-width: 20px;
  font-weight: var(--font-weight-semibold);
}

.paper-edit__title {
  margin-right: var(--space-2);
}

.paper-edit__version-tag {
  vertical-align: middle;
}

.paper-edit__picker-toolbar {
  margin-bottom: var(--space-3);
}

.paper-edit__picker-search {
  width: 240px;
}

.paper-edit__picker-select {
  width: 140px;
}

.paper-edit__picker-hint {
  font-size: var(--font-size-xs);
}

.paper-edit__picker-pager {
  margin-top: var(--space-3);
  justify-content: flex-end;
}

.paper-edit__preview-title {
  margin: 0 0 var(--space-2);
  font-size: var(--font-size-lg);
}

.paper-edit__preview-meta {
  margin: 0 0 var(--space-4);
  font-size: var(--font-size-sm);
}

.paper-edit__preview-list {
  margin: 0;
  padding-left: var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.paper-edit__preview-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.paper-edit__preview-name {
  flex: 1;
}
</style>
