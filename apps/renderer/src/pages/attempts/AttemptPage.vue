<script setup lang="ts">
/**
 * 答题页：整卷作答 / 单题作答、自动保存（防抖）、未答题提示、提交确认。
 * 刷新后通过 GET /attempts/{id} 恢复服务端已保存的作答。
 *
 * 注意：未提交时 `questions[].answer` / `explanation` 为 `null`（服务端不泄露答案），
 * 本页只读取 `questions[]` 的题干与选项，作答一律来自 `answers[]`。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ElAlert, ElButton, ElCard, ElDialog, ElTag } from 'element-plus';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';

import AnswerEditor from '@/components/AnswerEditor.vue';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import { useAttemptStore } from '@/stores/attempt';
import { useRuntimeStore } from '@/stores/runtime';
import { confirmAction, toastError, toastInfo, toastSuccess } from '@/utils/confirm';
import { formatDateTime } from '@/utils/format';
import { ATTEMPT_MODE_LABELS, QUESTION_TYPE_LABELS, labelFrom } from '@/utils/labels';

const route = useRoute();
const router = useRouter();
const store = useAttemptStore();
const runtime = useRuntimeStore();

const attemptId = computed(() => {
  const raw = route.params.id;
  return typeof raw === 'string' && raw.length > 0 ? raw : '';
});

const activeIndex = ref(0);
const confirmVisible = ref(false);

const questions = computed(() => store.questions);
const activeQuestion = computed(() => questions.value[activeIndex.value] ?? null);
const activeSnapshotId = computed(() => activeQuestion.value?.snapshotQuestionId ?? '');
const activeAnswer = computed(() => store.answers[activeSnapshotId.value] ?? null);
const activeSaveState = computed(() => store.saveStates[activeSnapshotId.value] ?? null);

const progress = computed(() => {
  const total = questions.value.length;
  if (total === 0) return 0;
  return Math.round((store.answeredCount / total) * 100);
});

const currentMaxScore = computed(() =>
  questions.value.reduce((total, question) => total + question.score, 0)
);

function isAnswered(snapshotQuestionId: string): boolean {
  const question = questions.value.find((item) => item.snapshotQuestionId === snapshotQuestionId);
  if (!question) return false;
  const answer = store.answers[snapshotQuestionId];
  if (!answer) return false;
  if (answer.type === 'multiple_choice') return answer.value.length > 0;
  if (answer.type === 'single_choice') return answer.value.length > 0;
  if (answer.type === 'fill_blank') {
    return answer.blanks.some((blank) => blank.accepted.some((item) => item.trim().length > 0));
  }
  if (answer.type === 'true_false') return true;
  if (answer.type === 'short_answer' || answer.type === 'essay') {
    return (answer.referenceAnswer ?? '').trim().length > 0;
  }
  return false;
}

function goTo(index: number): void {
  if (index < 0 || index >= questions.value.length) return;
  activeIndex.value = index;
}

function updateAnswer(value: NonNullable<typeof activeAnswer.value>): void {
  if (!activeSnapshotId.value) return;
  store.setAnswer(activeSnapshotId.value, value);
}

async function manualSave(): Promise<void> {
  if (!activeSnapshotId.value) return;
  const ok = await store.saveAnswerNow(activeSnapshotId.value);
  if (ok) toastSuccess('本题作答已保存');
  else toastError(store.saveStates[activeSnapshotId.value]?.message ?? '保存失败，请重试。');
}

function openSubmitDialog(): void {
  if (store.unansweredIds.length > 0) {
    confirmVisible.value = true;
    return;
  }
  void doSubmit();
}

async function doSubmit(): Promise<void> {
  confirmVisible.value = false;
  const confirmed = await confirmAction(
    '提交后判分将基于当前试卷快照，客观题立即判分，主观题标记为「AI 建议分待复核」。提交后不可再修改作答。确认提交？',
    { title: '提交作答', confirmText: '提交' }
  );
  if (!confirmed) return;

  const submitted = await store.submit();
  if (submitted) {
    toastSuccess('提交成功');
    await router.replace(`/attempts/${attemptId.value}/result`);
    return;
  }
  toastError(store.submitError?.text ?? '提交失败，请重试。');
}

function retrySubmit(): void {
  void doSubmit();
}

async function abandon(): Promise<void> {
  const confirmed = await confirmAction(
    '放弃后该次作答状态将变为「已放弃」，不能再继续作答。已保存的作答记录仍会保留。确认放弃？',
    { title: '放弃作答', type: 'error', confirmText: '放弃' }
  );
  if (!confirmed) return;
  const ok = await store.abandon();
  if (ok) {
    toastSuccess('已放弃本次作答');
    await router.replace('/attempts');
  } else {
    toastError('放弃失败，请重试。');
  }
}

async function reload(): Promise<void> {
  if (!attemptId.value) return;
  await store.load(attemptId.value);
}

watch(
  () => questions.value.length,
  (length) => {
    if (activeIndex.value >= length) activeIndex.value = Math.max(0, length - 1);
  }
);

/** 服务端对同键重复提交会直接重放首次结果并回 `Idempotent-Replay: true` */
watch(
  () => runtime.idempotentReplayCount,
  (count, previous) => {
    if (count > previous) {
      toastInfo('本次提交已由服务端重放首次结果（幂等键命中），未重复判分。');
    }
  }
);

onBeforeRouteLeave(async () => {
  if (!store.editable) return true;
  if (store.hasUnsavedChanges) {
    const saved = await confirmAction('还有未保存的作答，离开前建议先保存。是否立即保存并离开？', {
      title: '未保存的作答',
      confirmText: '保存并离开',
      cancelText: '留在本页'
    });
    if (saved) {
      await store.flushPendingSaves();
      return true;
    }
    return false;
  }
  return true;
});

function handleBeforeUnload(event: BeforeUnloadEvent): void {
  if (!store.editable || !store.hasUnsavedChanges) return;
  event.preventDefault();
  event.returnValue = '';
}

onMounted(async () => {
  window.addEventListener('beforeunload', handleBeforeUnload);
  if (attemptId.value) await store.load(attemptId.value);
});

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload);
});
</script>

<template>
  <div class="attempt">
    <PageHeader
      :title="store.sheet?.paperTitle || '答题'"
      :description="
        store.sheet
          ? `${labelFrom(ATTEMPT_MODE_LABELS, store.sheet.mode)} · 开始于 ${formatDateTime(store.sheet.startedAt)} · 满分 ${currentMaxScore}`
          : '正在载入答题卡…'
      "
      :breadcrumbs="[{ label: '作答记录', to: '/attempts' }, { label: '答题' }]"
    >
      <template #actions>
        <ElTag v-if="store.sheet" :type="store.editable ? 'primary' : 'success'">
          {{ store.editable ? '作答中' : '已提交' }}
        </ElTag>
        <ElButton v-if="store.editable" :disabled="store.savingCount > 0" @click="abandon">放弃作答</ElButton>
        <ElButton v-if="store.editable" type="primary" :loading="store.submitting" @click="openSubmitDialog">
          提交作答
        </ElButton>
        <ElButton v-else-if="store.sheet" type="primary" @click="router.push(`/attempts/${attemptId}/result`)">
          查看结果
        </ElButton>
      </template>
    </PageHeader>

    <StatePanel
      :state="store.loading ? 'loading' : store.error ? 'error' : 'ready'"
      :description="store.error?.message ?? ''"
      :code="store.error?.code ?? null"
      :request-id="store.error?.requestId ?? null"
      :retryable="store.error?.retryable ?? false"
      :skeleton-rows="6"
      @retry="reload"
    >
      <ElAlert
        v-if="store.submitError"
        class="attempt__banner"
        type="error"
        :closable="false"
        title="提交失败"
        :description="store.submitError.text"
      >
        <template #default>
          <p class="attempt__banner-text">{{ store.submitError.text }}</p>
          <ElButton size="small" type="primary" @click="retrySubmit">重新提交（复用同一幂等键）</ElButton>
        </template>
      </ElAlert>

      <ElAlert
        v-if="!store.editable"
        class="attempt__banner"
        type="info"
        :closable="false"
        title="本次作答已提交"
        description="已提交的作答不可修改，可前往结果页查看逐题反馈。"
      />

      <ElAlert
        v-if="store.failedSaves.length > 0"
        class="attempt__banner"
        type="warning"
        :closable="false"
        title="存在保存失败的作答"
        description="网络或本机服务异常导致部分作答未落库。可点击下方「重试保存」或重新提交。"
      >
        <template #default>
          <p class="attempt__banner-text">
            未保存题目数：{{ store.failedSaves.length }}（{{ store.failedSaves.length }} 处保存失败）
          </p>
          <ElButton size="small" @click="store.flushPendingSaves()">重试保存</ElButton>
        </template>
      </ElAlert>

      <div class="attempt__layout">
        <aside class="attempt__nav">
          <ElCard shadow="never">
            <template #header>
              <div class="attempt__nav-head">
                <span>答题进度</span>
                <span class="text-muted">{{ store.answeredCount }} / {{ questions.length }}</span>
              </div>
            </template>
            <div class="attempt__progress">
              <div class="attempt__progress-bar">
                <div class="attempt__progress-fill" :style="{ width: `${progress}%` }" />
              </div>
              <span class="attempt__progress-text">{{ progress }}%</span>
            </div>
            <div class="attempt__grid">
              <button
                v-for="(question, index) in questions"
                :key="question.snapshotQuestionId"
                type="button"
                class="attempt__cell"
                :class="{
                  'is-active': index === activeIndex,
                  'is-answered': isAnswered(question.snapshotQuestionId),
                  'is-unanswered': !isAnswered(question.snapshotQuestionId)
                }"
                @click="goTo(index)"
              >
                {{ index + 1 }}
              </button>
            </div>
            <p v-if="store.unansweredIds.length > 0" class="attempt__unanswered-hint">
              还有 {{ store.unansweredIds.length }} 题未作答。
            </p>
            <p v-else class="attempt__unanswered-hint attempt__unanswered-hint--ok">全部题目已作答。</p>
          </ElCard>

          <ElCard shadow="never" class="attempt__nav-card">
            <template #header><span>保存状态</span></template>
            <p class="attempt__save-line">
              <span class="text-muted">待保存/保存中</span>
              <span>{{ store.savingCount }}</span>
            </p>
            <p class="attempt__save-line">
              <span class="text-muted">保存失败</span>
              <span :class="{ 'text-danger': store.failedSaves.length > 0 }">{{ store.failedSaves.length }}</span>
            </p>
            <ElButton
              v-if="store.failedSaves.length > 0"
              size="small"
              type="primary"
              @click="store.flushPendingSaves()"
            >
              重试全部失败项
            </ElButton>
          </ElCard>
        </aside>

        <section class="attempt__main">
          <StatePanel
            :state="questions.length === 0 ? 'empty' : 'ready'"
            title="答题卡没有题目"
            description="该次作答没有可作答的题目，可能试卷快照为空。"
          >
            <article v-if="activeQuestion" class="attempt__question">
              <header class="attempt__question-head">
                <span class="attempt__question-order">第 {{ activeIndex + 1 }} / {{ questions.length }} 题</span>
                <ElTag size="small" type="info">
                  {{ labelFrom(QUESTION_TYPE_LABELS, activeQuestion.questionType) }}
                </ElTag>
                <ElTag size="small" type="info">{{ activeQuestion.score }} 分</ElTag>
                <ElTag
                  v-if="activeSaveState && activeSaveState.savedAt"
                  size="small"
                  type="success"
                  effect="plain"
                >
                  已保存 {{ formatDateTime(activeSaveState.savedAt) }}
                </ElTag>
              </header>

              <p class="attempt__stem">{{ activeQuestion.stem }}</p>

              <ElAlert
                v-if="activeSaveState && activeSaveState.state === 'failed'"
                class="attempt__banner"
                type="error"
                :closable="false"
                title="本题保存失败"
                :description="activeSaveState.message ?? ''"
              />

              <AnswerEditor
                v-if="activeAnswer"
                :question-type="activeQuestion.questionType"
                :options="activeQuestion.options"
                :model-value="activeAnswer"
                :disabled="!store.editable"
                :save-state="activeSaveState?.state ?? 'idle'"
                :save-message="activeSaveState?.message ?? null"
                @update:model-value="updateAnswer"
              />

              <div class="attempt__question-actions">
                <ElButton :disabled="activeIndex === 0" @click="goTo(activeIndex - 1)">上一题</ElButton>
                <ElButton
                  v-if="store.editable"
                  :loading="activeSaveState?.state === 'saving'"
                  @click="manualSave"
                >
                  立即保存本题
                </ElButton>
                <ElButton
                  v-if="activeIndex < questions.length - 1"
                  type="primary"
                  @click="goTo(activeIndex + 1)"
                >
                  下一题
                </ElButton>
                <ElButton v-else type="primary" :disabled="!store.editable" @click="openSubmitDialog">
                  提交作答
                </ElButton>
              </div>
            </article>
          </StatePanel>
        </section>
      </div>
    </StatePanel>

    <ElDialog v-model="confirmVisible" title="仍有未作答题目" width="520px">
      <p class="attempt__dialog-text">
        还有 <strong>{{ store.unansweredIds.length }}</strong> 题未作答。未作答题目按 0 分计入结果，并会进入错题本。
      </p>
      <p class="attempt__dialog-text">题号：{{ store.unansweredIds.length > 0 ? '见左侧答题卡中标红题号' : '无' }}</p>
      <template #footer>
        <ElButton @click="confirmVisible = false">返回继续作答</ElButton>
        <ElButton type="primary" @click="doSubmit">仍然提交</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.attempt__banner {
  margin-bottom: var(--space-4);
}

.attempt__banner-text {
  margin: 0 0 var(--space-2);
}

.attempt__layout {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: var(--space-4);
  align-items: start;
}

@media (max-width: 1100px) {
  .attempt__layout {
    grid-template-columns: minmax(0, 1fr);
  }
}

.attempt__nav {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  position: sticky;
  top: var(--space-4);
}

.attempt__nav-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.attempt__progress {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.attempt__progress-bar {
  flex: 1;
  height: 8px;
  border-radius: var(--radius-pill);
  background: var(--color-neutral-200);
  overflow: hidden;
}

.attempt__progress-fill {
  height: 100%;
  background: var(--color-brand-500);
  transition: width var(--transition-base);
}

.attempt__progress-text {
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}

.attempt__grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: var(--space-2);
}

.attempt__cell {
  height: 32px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  background: var(--color-surface-muted);
  color: var(--color-text-secondary);
  cursor: pointer;
  font-size: var(--font-size-sm);
  font-family: inherit;
}

.attempt__cell.is-answered {
  border-color: var(--color-success);
  background: var(--color-success-bg);
  color: var(--color-success);
}

.attempt__cell.is-unanswered {
  border-color: var(--color-danger);
}

.attempt__cell.is-active {
  outline: 2px solid var(--color-brand-500);
  outline-offset: 1px;
  font-weight: var(--font-weight-semibold);
}

.attempt__unanswered-hint {
  margin: var(--space-3) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-danger);
}

.attempt__unanswered-hint--ok {
  color: var(--color-success);
}

.attempt__nav-card {
  font-size: var(--font-size-sm);
}

.attempt__save-line {
  display: flex;
  justify-content: space-between;
  margin: 0 0 var(--space-2);
}

.attempt__main {
  min-width: 0;
}

.attempt__question {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
}

.attempt__question-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}

.attempt__question-order {
  font-weight: var(--font-weight-semibold);
  color: var(--color-brand-600);
}

.attempt__stem {
  margin: 0 0 var(--space-4);
  font-size: var(--font-size-md);
  white-space: pre-wrap;
  word-break: break-word;
}

.attempt__question-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-top: var(--space-5);
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-border);
}

.attempt__dialog-text {
  margin: 0 0 var(--space-2);
}
</style>
