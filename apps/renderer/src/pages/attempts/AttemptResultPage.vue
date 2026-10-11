<script setup lang="ts">
/**
 * 结果页：总分、逐题反馈（对/错、你的答案、标准答案、解析）。
 * 主观题复核闭环（按 Java 实现）：
 *   1. 「请求 AI 评分」→ `POST /attempts/{id}/ai-grade` 排队任务，再轮询 `/tasks/{taskId}`
 *   2. AI 只写 `gradingDetail.suggestedScore`，`gradingStatus` 仍为 `pending_review`
 *   3. 必须点「确认/修改分数」调用 `POST /attempts/{id}/answers/{sqid}/grade` 才生效
 */
import { computed, onMounted, reactive, ref } from 'vue';
import {
  ElAlert,
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElInput,
  ElInputNumber,
  ElTag
} from 'element-plus';
import { useRoute, useRouter } from 'vue-router';

import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import { useAttemptStore } from '@/stores/attempt';
import { toastError, toastInfo, toastSuccess } from '@/utils/confirm';
import { formatDateTime, formatScore, trimNumber } from '@/utils/format';
import {
  GRADING_METHOD_LABELS,
  GRADING_STATUS_LABELS,
  QUESTION_TYPE_LABELS,
  labelFrom
} from '@/utils/labels';
import { describeAnswer, describeChoiceKeys } from '@/utils/answer';
import { readAiGradingDetail, type AttemptResultItem } from '@/types/attempt';

const route = useRoute();
const router = useRouter();
const store = useAttemptStore();

const attemptId = computed(() => {
  const raw = route.params.id;
  return typeof raw === 'string' && raw.length > 0 ? raw : '';
});

const result = computed(() => store.result);
const attempt = computed(() => result.value?.attempt ?? null);
const items = computed(() => result.value?.items ?? []);

const pendingReviewItems = computed(() =>
  items.value.filter((item) => item.gradingStatus === 'pending_review')
);

/** 统计数直接取服务端返回值，不再前端自行计算 */
const correctCount = computed(() => result.value?.correctCount ?? 0);
const wrongCount = computed(() => result.value?.wrongCount ?? 0);
const unansweredCount = computed(() => result.value?.unansweredCount ?? 0);
const pendingReviewCount = computed(() => result.value?.pendingReviewCount ?? 0);

const scoreRate = computed(() => {
  const total = attempt.value?.maxScore ?? 0;
  if (total <= 0) return 0;
  return Math.round(((attempt.value?.totalScore ?? 0) / total) * 100);
});

/** 人工确认分数对话框 */
const gradeDialogVisible = ref(false);
const gradeTarget = ref<AttemptResultItem | null>(null);
const gradeForm = reactive({ score: 0, note: '' });
const gradeSaving = ref(false);

const gradeTargetSuggested = computed(() =>
  gradeTarget.value ? readAiGradingDetail(gradeTarget.value.gradingDetail)?.suggestedScore ?? null : null
);

function aiDetail(item: AttemptResultItem) {
  return readAiGradingDetail(item.gradingDetail);
}

function displayAnswer(
  answer: AttemptResultItem['userAnswer'],
  options: AttemptResultItem['options']
): string {
  if (!answer) return '未作答';
  if (answer.type === 'single_choice') return describeChoiceKeys(answer.value ? [answer.value] : [], options);
  if (answer.type === 'multiple_choice') return describeChoiceKeys(answer.value, options);
  if (answer.type === 'true_false') return answer.value ? '正确' : '错误';
  if (answer.type === 'fill_blank') {
    return answer.blanks
      .slice()
      .sort((a, b) => a.index - b.index)
      .map((blank) => `第 ${blank.index} 空：${blank.accepted.join(' / ') || '未作答'}`)
      .join('；');
  }
  return describeAnswer(answer);
}

function isCorrectChoice(item: AttemptResultItem, key: string): boolean {
  const answer = item.correctAnswer;
  if (!answer) return false;
  if (answer.type === 'single_choice') return answer.value === key;
  if (answer.type === 'multiple_choice') return answer.value.includes(key);
  return false;
}

function isChosen(item: AttemptResultItem, key: string): boolean {
  const answer = item.userAnswer;
  if (!answer) return false;
  if (answer.type === 'single_choice') return answer.value === key;
  if (answer.type === 'multiple_choice') return answer.value.includes(key);
  return false;
}

async function load(): Promise<void> {
  if (!attemptId.value) return;
  await store.loadResult(attemptId.value);
}

/** 为整卷所有待复核主观题排队 AI 评分任务并轮询 */
async function requestAiGradeForAttempt(): Promise<void> {
  if (!attemptId.value) return;
  if (pendingReviewItems.value.length === 0) {
    toastInfo('当前没有待复核的主观题。');
    return;
  }
  const run = await store.requestAiGrade(attemptId.value);
  if (!run) {
    toastError(store.aiGradeMessage ?? 'AI 评分请求失败。');
    return;
  }
  if (run.succeeded > 0) toastSuccess(run.message ?? 'AI 建议分已生成，需人工确认后生效。');
  else if (run.stillRunning > 0) toastInfo(run.message ?? 'AI 评分任务仍在执行，请稍后刷新。');
  else if (run.message) toastInfo(run.message);
}

function openGradeDialog(item: AttemptResultItem): void {
  gradeTarget.value = item;
  const detail = readAiGradingDetail(item.gradingDetail);
  gradeForm.score =
    detail?.suggestedScore ?? (typeof item.score === 'number' ? item.score : 0);
  gradeForm.note = detail?.note ?? '';
  gradeDialogVisible.value = true;
}

async function submitGrade(): Promise<void> {
  const target = gradeTarget.value;
  if (!target || !attemptId.value) return;
  if (gradeForm.score < 0 || gradeForm.score > target.maxScore) {
    toastError(`分数需在 0 到 ${target.maxScore} 之间。`);
    return;
  }
  gradeSaving.value = true;
  try {
    const payload: Parameters<typeof store.gradeManually>[2] = { score: gradeForm.score };
    if (gradeForm.note.trim()) payload.note = gradeForm.note.trim();
    const updated = await store.gradeManually(attemptId.value, target.snapshotQuestionId, payload);
    if (updated) {
      toastSuccess(`已确认得分 ${gradeForm.score}，总分已重算`);
      gradeDialogVisible.value = false;
    } else {
      toastError(store.aiGradeMessage ?? '人工评分失败。');
    }
  } finally {
    gradeSaving.value = false;
  }
}

onMounted(() => {
  void load();
});
</script>

<template>
  <div class="result">
    <PageHeader
      :title="attempt?.paperTitle ? `作答结果 · ${attempt.paperTitle}` : '作答结果'"
      description="历史结果基于当时的试卷快照与判分规则，不会随题目后续修改而变化。"
      :breadcrumbs="[{ label: '作答记录', to: '/attempts' }, { label: '作答结果' }]"
    >
      <template #actions>
        <ElButton @click="router.push(`/attempts/${attemptId}`)">查看答题卡</ElButton>
        <ElButton
          v-if="pendingReviewItems.length > 0"
          :loading="store.aiGrading"
          @click="requestAiGradeForAttempt"
        >
          请求 AI 评分（{{ pendingReviewItems.length }} 题）
        </ElButton>
        <ElButton type="primary" :loading="store.loading" @click="load">刷新</ElButton>
      </template>
    </PageHeader>

    <StatePanel
      :state="store.loading && !result ? 'loading' : store.error ? 'error' : 'ready'"
      :description="store.error?.message ?? ''"
      :code="store.error?.code ?? null"
      :request-id="store.error?.requestId ?? null"
      :retryable="store.error?.retryable ?? false"
      :skeleton-rows="6"
      @retry="load"
    >
      <ElAlert
        v-if="store.aiGradeMessage"
        class="result__banner"
        :type="store.aiGrading ? 'info' : 'warning'"
        :closable="false"
        title="AI 评分流程"
        :description="store.aiGradeMessage"
      />

      <section v-if="attempt" class="panel result__summary">
        <div class="result__score">
          <p class="result__score-value">{{ formatScore(attempt.totalScore, attempt.maxScore) }}</p>
          <p class="result__score-label">总得分（得分率 {{ scoreRate }}%）</p>
        </div>
        <ElDescriptions :column="2" border size="small" class="result__meta">
          <ElDescriptionsItem label="题目数">{{ attempt.questionCount }}</ElDescriptionsItem>
          <ElDescriptionsItem label="答对 / 答错 / 未作答">
            <ElTag size="small" type="success">{{ correctCount }} 题答对</ElTag>
            <ElTag size="small" type="danger" class="result__tag-gap">{{ wrongCount }} 题答错</ElTag>
            <ElTag size="small" type="info" class="result__tag-gap">{{ unansweredCount }} 题未作答</ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="待复核主观题">
            <ElTag size="small" :type="pendingReviewCount > 0 ? 'warning' : 'success'">
              {{ pendingReviewCount }} 题
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="提交时间">{{ formatDateTime(attempt.submittedAt) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="开始时间">{{ formatDateTime(attempt.startedAt) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="判分时间">{{ formatDateTime(attempt.gradedAt) }}</ElDescriptionsItem>
        </ElDescriptions>
      </section>

      <ElAlert
        v-if="pendingReviewItems.length > 0"
        class="result__banner"
        type="warning"
        :closable="false"
        title="存在待复核的主观题"
        :description="`共 ${pendingReviewItems.length} 题标记为「AI 建议分待复核」。点击「请求 AI 评分」排队后台任务，AI 只给出建议分，必须再由你确认或修改后才会计入总分。`"
      />

      <section class="result__items">
        <article
          v-for="(item, index) in items"
          :key="item.snapshotQuestionId"
          class="panel result__item"
          :class="{ 'result__item--pending': item.gradingStatus === 'pending_review' }"
        >
          <header class="result__item-head">
            <span class="result__item-order">第 {{ item.orderNo || index + 1 }} 题</span>
            <ElTag size="small" type="info">{{ labelFrom(QUESTION_TYPE_LABELS, item.questionType) }}</ElTag>
            <ElTag
              v-if="item.correct === true"
              size="small"
              type="success"
            >
              正确
            </ElTag>
            <ElTag v-else-if="item.correct === false" size="small" type="danger">错误</ElTag>
            <ElTag v-else size="small" type="warning">待判分</ElTag>
            <ElTag size="small">{{ formatScore(item.score, item.maxScore) }}</ElTag>
            <ElTag size="small" effect="plain">
              {{ labelFrom(GRADING_METHOD_LABELS, item.gradingMethod) }}
            </ElTag>
            <ElTag
              v-if="item.gradingStatus !== 'graded'"
              size="small"
              :type="item.gradingStatus === 'pending_review' ? 'warning' : 'danger'"
            >
              {{ labelFrom(GRADING_STATUS_LABELS, item.gradingStatus) }}
            </ElTag>
          </header>

          <p class="result__stem">{{ item.stem }}</p>

          <ul v-if="item.options && item.options.length > 0" class="result__options">
            <li
              v-for="option in item.options"
              :key="option.key"
              class="result__option"
              :class="{
                'is-correct': isCorrectChoice(item, option.key),
                'is-chosen': isChosen(item, option.key)
              }"
            >
              <span class="result__option-key">{{ option.key }}</span>
              <span class="result__option-content">{{ option.content }}</span>
              <ElTag v-if="isCorrectChoice(item, option.key)" size="small" type="success">标准答案</ElTag>
              <ElTag v-else-if="isChosen(item, option.key)" size="small" type="danger">你的选择</ElTag>
            </li>
          </ul>

          <div class="result__answers">
            <p class="result__answer-line">
              <span class="result__answer-label">你的答案</span>
              <span :class="{ 'text-danger': item.correct === false }">
                {{ displayAnswer(item.userAnswer, item.options) }}
              </span>
            </p>
            <p class="result__answer-line">
              <span class="result__answer-label">标准答案</span>
              <span>{{ displayAnswer(item.correctAnswer, item.options) }}</span>
            </p>
          </div>

          <ElAlert
            v-if="item.explanation"
            class="result__explanation"
            type="info"
            :closable="false"
            title="解析"
          >
            <p class="result__explanation-text">{{ item.explanation }}</p>
          </ElAlert>

          <div
            v-if="item.gradingStatus === 'pending_review'"
            class="result__pending"
          >
            <div class="result__pending-text">
              <ElTag type="warning" size="small">AI 建议分待复核</ElTag>
              <span class="result__pending-hint">
                <template v-if="aiDetail(item)?.suggestedScore !== undefined">
                  AI 建议分
                  <strong>{{ trimNumber(aiDetail(item)?.suggestedScore ?? 0) }}</strong>
                  / {{ item.maxScore }}
                  <template v-if="aiDetail(item)?.confidence !== undefined">
                    （置信度 {{ trimNumber(aiDetail(item)?.confidence ?? 0) }}）
                  </template>
                  <template v-if="aiDetail(item)?.model"> · 模型 {{ aiDetail(item)?.model }}</template>
                  —— 该分数尚未计入总分，需人工确认后才生效。
                </template>
                <template v-else>
                  尚未生成 AI 建议分。点击「请求 AI 评分」排队后台任务；AI 不可用时成绩保持待复核，主流程不受影响。
                </template>
              </span>
            </div>
            <div class="result__pending-actions">
              <ElButton
                size="small"
                :loading="store.aiGrading"
                @click="requestAiGradeForAttempt"
              >
                {{ aiDetail(item)?.suggestedScore !== undefined ? '重新请求 AI 评分' : '请求 AI 评分' }}
              </ElButton>
              <ElButton
                type="primary"
                size="small"
                :loading="store.gradingInFlight[item.snapshotQuestionId] === true"
                @click="openGradeDialog(item)"
              >
                {{ aiDetail(item)?.suggestedScore !== undefined ? '确认 / 修改分数' : '手动评分' }}
              </ElButton>
            </div>
          </div>

          <p v-if="item.gradingStatus === 'failed'" class="result__grading-error">
            判分失败：{{ aiDetail(item)?.note ?? 'AI 服务不可用或超时，可稍后重试或手动评分。' }}
          </p>
        </article>
      </section>

      <StatePanel
        v-if="items.length === 0 && !store.loading && !store.error"
        state="empty"
        title="没有逐题反馈"
        description="该次作答可能尚未提交，或试卷快照为空。"
        action-text="查看答题卡"
        @action="router.push(`/attempts/${attemptId}`)"
      />
    </StatePanel>

    <!-- 人工确认 / 覆盖主观题分数 -->
    <ElDialog v-model="gradeDialogVisible" title="人工确认主观题分数" width="560px">
      <ElAlert
        type="info"
        :closable="false"
        title="确认后才会写入总分"
        description="AI 建议分只是建议；这里提交的分数会作为最终得分写入作答记录，并立即重算总分。"
      />
      <div class="result__grade-form">
        <p class="result__grade-target">
          第 {{ gradeTarget?.orderNo ?? '—' }} 题（满分 {{ gradeTarget?.maxScore ?? '—' }} 分）
          <template v-if="gradeTargetSuggested !== null">
            · AI 建议分 {{ trimNumber(gradeTargetSuggested) }}
          </template>
        </p>
        <div class="result__grade-row">
          <span class="result__grade-label">最终得分</span>
          <ElInputNumber
            v-model="gradeForm.score"
            :min="0"
            :max="gradeTarget?.maxScore ?? 100"
            :step="0.5"
            :precision="2"
          />
        </div>
        <ElInput
          v-model="gradeForm.note"
          type="textarea"
          :rows="3"
          maxlength="500"
          placeholder="复核说明（可选，写入判分明细）"
        />
      </div>
      <template #footer>
        <ElButton @click="gradeDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="gradeSaving" @click="submitGrade">确认分数</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.result__banner {
  margin-bottom: var(--space-4);
}

.result__summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-6);
  margin-bottom: var(--space-4);
}

.result__score {
  min-width: 200px;
}

.result__score-value {
  margin: 0;
  font-size: var(--font-size-2xl);
  font-weight: var(--font-weight-semibold);
  color: var(--color-brand-600);
}

.result__score-label {
  margin: var(--space-1) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-muted);
}

.result__meta {
  flex: 1;
  min-width: 320px;
}

.result__tag-gap {
  margin-left: var(--space-2);
}

.result__items {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.result__item--pending {
  border-left: 3px solid var(--color-warning);
}

.result__item-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.result__item-order {
  font-weight: var(--font-weight-semibold);
  color: var(--color-brand-600);
}

.result__stem {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-md);
  white-space: pre-wrap;
  word-break: break-word;
}

.result__options {
  list-style: none;
  margin: 0 0 var(--space-3);
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.result__option {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-muted);
}

.result__option.is-correct {
  border-color: var(--color-success);
  background: var(--color-success-bg);
}

.result__option.is-chosen:not(.is-correct) {
  border-color: var(--color-danger);
  background: var(--color-danger-bg);
}

.result__option-key {
  min-width: 20px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-brand-600);
}

.result__option-content {
  flex: 1;
  word-break: break-word;
}

.result__answers {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  margin-bottom: var(--space-3);
}

.result__answer-line {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  word-break: break-word;
}

.result__answer-label {
  display: inline-block;
  min-width: 72px;
  color: var(--color-text-muted);
}

.result__explanation {
  margin-top: var(--space-2);
}

.result__explanation-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.result__pending {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
  margin-top: var(--space-4);
  padding: var(--space-3) var(--space-4);
  border: 1px dashed var(--color-warning);
  border-radius: var(--radius-md);
  background: var(--color-warning-bg);
}

.result__pending-text {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.result__pending-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.result__grading-error {
  margin: var(--space-3) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-danger);
}

.result__pending-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.result__grade-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin-top: var(--space-3);
}

.result__grade-target {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.result__grade-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.result__grade-label {
  min-width: 64px;
  font-size: var(--font-size-sm);
  color: var(--color-text-muted);
}
</style>
