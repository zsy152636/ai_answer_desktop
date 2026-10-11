<script setup lang="ts">
/**
 * 错题本：状态/知识点/到期筛选、去练习（wrong_practice attempt）、
 * 提交复习结果、手动标记掌握/归档、版本差异提示、复习记录时间线。
 */
import { computed, onMounted, ref, watch } from 'vue';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElCheckbox,
  ElEmpty,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
  ElTimeline,
  ElTimelineItem
} from 'element-plus';
import { useRoute, useRouter } from 'vue-router';

import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import * as wrongQuestionsApi from '@/api/wrongQuestions';
import { QUESTION_TYPES, WRONG_STATUSES } from '@/types/common';
import { hasVersionDrift, type WrongQuestion } from '@/types/wrongQuestion';
import { useWrongQuestionsStore } from '@/stores/wrongQuestions';
import { confirmAction, toastError, toastSuccess } from '@/utils/confirm';
import { describeError } from '@/utils/error';
import { formatDateTime, formatRelative, formatScore } from '@/utils/format';
import {
  QUESTION_TYPE_LABELS,
  WRONG_STATUS_LABELS,
  WRONG_STATUS_TAG,
  labelFrom,
  tagFrom
} from '@/utils/labels';

const route = useRoute();
const router = useRouter();
const store = useWrongQuestionsStore();

const reviewing = ref(false);
const reviewNote = ref('');
const reviewDurationSeconds = ref<number | undefined>(undefined);
const practiceStarting = ref(false);

/**
 * 版本差异：`WrongQuestion` 直接带 `currentVersionNo` 与 `wrongVersionNo`，
 * 无需再 `GET /questions/{id}`。
 */
const versionDiff = computed(() => {
  const question = store.detail?.question ?? null;
  if (!question) return null;
  return {
    currentVersionNo: question.currentVersionNo,
    wrongVersionNo: question.wrongVersionNo,
    changed: hasVersionDrift(question)
  };
});

const dueCount = computed(() => store.items.filter((item) => isDue(item)).length);

function isDue(item: WrongQuestion): boolean {
  if (!item.nextReviewAt) return false;
  const next = new Date(item.nextReviewAt).getTime();
  return !Number.isNaN(next) && next <= Date.now();
}

async function openDetail(item: WrongQuestion): Promise<void> {
  reviewNote.value = '';
  reviewDurationSeconds.value = undefined;
  await store.loadDetail(item.id);
}

function closeDetail(): void {
  store.detail = null;
}

async function submitReview(result: 'correct' | 'wrong'): Promise<void> {
  const detail = store.detail;
  if (!detail) return;

  const confirmed = await confirmAction(
    result === 'correct'
      ? '记录为「复习正确」：连续 2 次正确后状态变为「已掌握」，否则进入「复习中」并按调度安排下次复习。确认提交？'
      : '记录为「复习错误」：状态回到「待复习」，正确连续次数清零并重新计算下次复习时间。确认提交？',
    { title: '提交复习结果', confirmText: '确认提交' }
  );
  if (!confirmed) return;

  reviewing.value = true;
  try {
    const payload: Parameters<typeof wrongQuestionsApi.reviewWrongQuestion>[1] = { result };
    if (reviewNote.value.trim()) payload.note = reviewNote.value.trim();
    if (reviewDurationSeconds.value && reviewDurationSeconds.value > 0) {
      payload.durationSeconds = reviewDurationSeconds.value;
    }
    await store.review(detail.question.id, payload);
    toastSuccess('复习结果已记录');
    reviewNote.value = '';
    reviewDurationSeconds.value = undefined;
  } catch (caught) {
    toastError(describeError(caught).text);
  } finally {
    reviewing.value = false;
  }
}

async function markStatus(status: 'mastered' | 'archived' | 'active'): Promise<void> {
  const detail = store.detail;
  if (!detail) return;
  const label = status === 'mastered' ? '标记为已掌握' : status === 'archived' ? '归档' : '恢复为待复习';
  const confirmed = await confirmAction(
    `确认将「${detail.question.title}」${label}？手动操作不会被调度自动覆盖。`,
    { title: '状态变更' }
  );
  if (!confirmed) return;
  try {
    await store.patchStatus(detail.question.id, { status });
    toastSuccess(`${label}成功`);
  } catch (caught) {
    toastError(describeError(caught).text);
  }
}

async function startPractice(ids?: string[]): Promise<void> {
  const selected = ids ?? store.selectedIds;
  if (selected.length === 0) {
    toastError('请先勾选要练习的错题。');
    return;
  }
  const confirmed = await confirmAction(
    `将基于选中的 ${selected.length} 道错题创建一次「错题练习」作答。确认开始？`,
    { title: '去练习', confirmText: '开始练习' }
  );
  if (!confirmed) return;

  practiceStarting.value = true;
  try {
    const sheet = await wrongQuestionsApi.createWrongPracticeAttempt({ wrongQuestionIds: selected });
    toastSuccess('已创建错题练习');
    store.clearSelection();
    await router.push(`/attempts/${sheet.id}`);
  } catch (caught) {
    toastError(describeError(caught).text);
  } finally {
    practiceStarting.value = false;
  }
}

async function startDuePractice(): Promise<void> {
  const confirmed = await confirmAction('将按后端调度取全部到期错题创建一次练习（缺省 limit）。确认开始？', {
    title: '练习全部到期错题',
    confirmText: '开始练习'
  });
  if (!confirmed) return;
  practiceStarting.value = true;
  try {
    const sheet = await wrongQuestionsApi.createWrongPracticeAttempt({});
    toastSuccess('已创建到期错题练习');
    await router.push(`/attempts/${sheet.id}`);
  } catch (caught) {
    toastError(describeError(caught).text);
  } finally {
    practiceStarting.value = false;
  }
}

function focusFromQuery(): void {
  const focus = route.query.focus;
  if (typeof focus !== 'string' || focus.length === 0) return;
  const item = store.items.find((entry) => entry.id === focus);
  if (item) void openDetail(item);
}

watch(
  () => store.items.length,
  () => {
    focusFromQuery();
  }
);

onMounted(async () => {
  await store.fetchList();
  focusFromQuery();
});
</script>

<template>
  <div class="wrong">
    <PageHeader
      title="错题本"
      description="按 question_version_id 去重收集错题；复习正确累计 2 次后标记为已掌握。"
    >
      <template #actions>
        <ElButton :disabled="store.selectedIds.length === 0" :loading="practiceStarting" @click="startPractice()">
          去练习（已选 {{ store.selectedIds.length }}）
        </ElButton>
        <ElButton type="primary" :loading="practiceStarting" @click="startDuePractice">
          练习全部到期
        </ElButton>
      </template>
    </PageHeader>

    <section class="panel wrong__filters">
      <div class="toolbar">
        <ElSelect v-model="store.filters.status" placeholder="状态" clearable class="wrong__select" @change="store.search()">
          <ElOption
            v-for="status in WRONG_STATUSES"
            :key="status"
            :label="WRONG_STATUS_LABELS[status]"
            :value="status"
          />
        </ElSelect>
        <ElSelect
          v-model="store.filters.questionType"
          placeholder="题型"
          clearable
          class="wrong__select"
          @change="store.search()"
        >
          <ElOption
            v-for="type in QUESTION_TYPES"
            :key="type"
            :label="QUESTION_TYPE_LABELS[type]"
            :value="type"
          />
        </ElSelect>
        <ElInput
          v-model="store.filters.tag"
          placeholder="知识点标签"
          clearable
          class="wrong__tag"
          @keyup.enter="store.search()"
        />
        <ElCheckbox v-model="store.filters.dueOnly" @change="store.search()">仅显示到期</ElCheckbox>
        <ElButton type="primary" :loading="store.loading" @click="store.search()">查询</ElButton>
        <ElButton @click="store.resetFilters()">重置</ElButton>
        <span class="text-muted wrong__hint">当前页到期：{{ dueCount }} 题</span>
      </div>
    </section>

    <div class="wrong__layout">
      <section class="panel wrong__list">
        <StatePanel
          :state="store.loading && store.items.length === 0 ? 'loading' : store.error ? 'error' : store.isEmpty ? 'empty' : 'ready'"
          :description="store.error?.message ?? '没有符合条件的错题。未到期的错题会在 nextReviewAt 到达后出现在「仅显示到期」筛选中。'"
          :code="store.error?.code ?? null"
          :request-id="store.error?.requestId ?? null"
          :retryable="store.error?.retryable ?? false"
          action-text="练习全部到期"
          @retry="store.fetchList()"
          @action="startDuePractice"
        >
          <ElTable :data="store.items" row-key="id" @row-click="openDetail">
            <ElTableColumn width="46">
              <template #default="{ row }">
                <ElCheckbox
                  :model-value="store.selectedIds.includes(String(row.id))"
                  @click.stop
                  @change="store.toggleSelect(String(row.id))"
                />
              </template>
            </ElTableColumn>
            <ElTableColumn label="题目" min-width="200">
              <template #default="{ row }">
                <span class="wrong__title">{{ row.title }}</span>
                <span class="text-muted wrong__stem">{{ String(row.stem).slice(0, 60) }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="状态" width="100">
              <template #default="{ row }">
                <ElTag size="small" :type="tagFrom(WRONG_STATUS_TAG, row.status)">
                  {{ labelFrom(WRONG_STATUS_LABELS, row.status) }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="错误次数" width="90" prop="wrongCount" />
            <ElTableColumn label="连续正确" width="90" prop="correctStreak" />
            <ElTableColumn label="下次复习" width="130">
              <template #default="{ row }">
                <span :class="{ 'text-danger': isDue(row as WrongQuestion) }">
                  {{ row.nextReviewAt ? formatRelative(String(row.nextReviewAt)) : '—' }}
                </span>
              </template>
            </ElTableColumn>
          </ElTable>
        </StatePanel>

        <div v-if="store.total > 0" class="wrong__pager">
          <ElPagination
            :current-page="store.page"
            :page-size="store.size"
            :page-sizes="[10, 20, 50]"
            :total="store.total"
            layout="total, sizes, prev, pager, next"
            background
            @current-change="store.changePage"
            @size-change="store.changeSize"
          />
        </div>
      </section>

      <aside class="wrong__detail">
        <ElCard shadow="never">
          <template #header>
            <div class="wrong__detail-head">
              <span>错题详情</span>
              <ElButton v-if="store.detail" text size="small" @click="closeDetail">关闭</ElButton>
            </div>
          </template>
          <StatePanel
            :state="store.detailLoading ? 'loading' : store.detailError ? 'error' : store.detail ? 'ready' : 'empty'"
            :description="store.detailError?.message ?? '从左侧列表选择一道错题查看详情、复习记录与操作。'"
            :code="store.detailError?.code ?? null"
            :retryable="store.detailError?.retryable ?? false"
            :skeleton-rows="5"
            @retry="store.detail && store.loadDetail(store.detail.question.id)"
          >
            <template v-if="store.detail">
              <div class="wrong__detail-block">
                <div class="wrong__detail-tags">
                  <ElTag size="small" :type="tagFrom(WRONG_STATUS_TAG, store.detail.question.status)">
                    {{ labelFrom(WRONG_STATUS_LABELS, store.detail.question.status) }}
                  </ElTag>
                  <ElTag size="small" type="info">
                    {{ labelFrom(QUESTION_TYPE_LABELS, store.detail.question.questionType) }}
                  </ElTag>
                  <ElTag size="small" type="danger">错误 {{ store.detail.question.wrongCount }} 次</ElTag>
                  <ElTag size="small">连续正确 {{ store.detail.question.correctStreak }}</ElTag>
                  <ElTag
                    v-for="tag in store.detail.question.knowledgeTags"
                    :key="tag"
                    size="small"
                    effect="plain"
                  >
                    {{ tag }}
                  </ElTag>
                </div>
                <h3 class="wrong__detail-title">{{ store.detail.question.title }}</h3>
                <p class="wrong__detail-stem">{{ store.detail.question.stem }}</p>
                <p class="wrong__detail-meta">
                  上次错误：{{ formatDateTime(store.detail.question.lastWrongAt) }} ·
                  下次复习：{{ store.detail.question.nextReviewAt ? formatDateTime(store.detail.question.nextReviewAt) : '无需复习' }} ·
                  上次得分：{{ formatScore(store.detail.question.lastScore, store.detail.question.maxScore) }}
                </p>
              </div>

              <ElAlert
                v-if="versionDiff"
                class="wrong__diff"
                :type="versionDiff.changed ? 'warning' : 'success'"
                :closable="false"
                :title="versionDiff.changed ? '版本差异提示' : '版本一致'"
                :description="
                  versionDiff.changed
                    ? `该错题绑定的是题目 v${versionDiff.wrongVersionNo}，题目当前已是 v${versionDiff.currentVersionNo}。复习与统计按 question_version_id 去重，请注意题意可能已变化。`
                    : `错题绑定的版本（v${versionDiff.wrongVersionNo}）与题目当前版本（v${versionDiff.currentVersionNo}）一致，题意未发生版本级变化。`
                "
              />

              <div class="wrong__actions">
                <ElButton
                  type="success"
                  size="small"
                  :loading="reviewing"
                  @click="submitReview('correct')"
                >
                  复习正确
                </ElButton>
                <ElButton type="danger" size="small" :loading="reviewing" @click="submitReview('wrong')">
                  复习错误
                </ElButton>
                <ElButton
                  v-if="store.detail.question.status !== 'mastered'"
                  size="small"
                  @click="markStatus('mastered')"
                >
                  手动标记掌握
                </ElButton>
                <ElButton
                  v-if="store.detail.question.status !== 'archived'"
                  size="small"
                  @click="markStatus('archived')"
                >
                  归档
                </ElButton>
                <ElButton
                  v-if="store.detail.question.status !== 'active'"
                  size="small"
                  @click="markStatus('active')"
                >
                  恢复待复习
                </ElButton>
                <ElButton size="small" type="primary" @click="startPractice([store.detail.question.id])">
                  去练习
                </ElButton>
              </div>

              <div class="wrong__review-form">
                <ElInput
                  v-model="reviewNote"
                  type="textarea"
                  :rows="2"
                  maxlength="500"
                  placeholder="复习备注（可选，随复习记录一起保存）"
                  class="wrong__note"
                />
                <ElInputNumber
                  v-model="reviewDurationSeconds"
                  :min="0"
                  :max="86400"
                  placeholder="用时（秒）"
                  class="wrong__duration"
                />
                <span class="text-muted wrong__hint">复习用时（可选，写入复习记录）</span>
              </div>

              <div class="wrong__timeline">
                <p class="wrong__timeline-title">复习记录（最近 20 条）</p>
                <StatePanel
                  :state="store.detail.reviews.length === 0 ? 'empty' : 'ready'"
                  title="暂无复习记录"
                  description="提交一次复习结果后，这里会显示时间线。"
                >
                  <ElTimeline>
                    <ElTimelineItem
                      v-for="review in store.detail.reviews"
                      :key="review.id"
                      :timestamp="formatDateTime(review.reviewedAt)"
                      placement="top"
                      :type="review.result === 'correct' ? 'success' : 'danger'"
                    >
                      <p class="wrong__timeline-result">
                        {{ review.result === 'correct' ? '复习正确' : '复习错误' }}
                        <span v-if="review.durationSeconds" class="text-muted">
                          · 用时 {{ review.durationSeconds }} 秒
                        </span>
                      </p>
                      <p v-if="review.note" class="wrong__timeline-note">{{ review.note }}</p>
                      <p class="wrong__timeline-meta text-muted">
                        复习间隔：
                        {{ review.intervalDaysBefore ?? '—' }} 天 → {{ review.intervalDaysAfter ?? '—' }} 天
                        <template v-if="review.nextReviewAt">
                          · 下次复习 {{ formatDateTime(review.nextReviewAt) }}
                        </template>
                        <template v-else>· 已掌握，无需再复习</template>
                      </p>
                    </ElTimelineItem>
                  </ElTimeline>
                </StatePanel>
              </div>
            </template>
            <ElEmpty v-else description="未选择错题" />
          </StatePanel>
        </ElCard>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.wrong__filters {
  margin-bottom: var(--space-4);
}

.wrong__select {
  width: 140px;
}

.wrong__tag {
  width: 160px;
}

.wrong__hint {
  font-size: var(--font-size-xs);
}

.wrong__layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 420px;
  gap: var(--space-4);
  align-items: start;
}

@media (max-width: 1240px) {
  .wrong__layout {
    grid-template-columns: minmax(0, 1fr);
  }
}

.wrong__title {
  display: block;
  color: var(--color-brand-600);
  cursor: pointer;
}

.wrong__stem {
  display: block;
  font-size: var(--font-size-xs);
}

.wrong__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-4);
}

.wrong__detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.wrong__detail-block {
  margin-bottom: var(--space-4);
}

.wrong__detail-tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.wrong__detail-title {
  margin: 0 0 var(--space-2);
  font-size: var(--font-size-md);
}

.wrong__detail-stem {
  margin: 0 0 var(--space-2);
  white-space: pre-wrap;
  word-break: break-word;
}

.wrong__detail-meta {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}

.wrong__diff {
  margin-bottom: var(--space-4);
}

.wrong__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.wrong__note {
  margin-bottom: var(--space-2);
}

.wrong__review-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}

.wrong__duration {
  width: 180px;
}

.wrong__timeline-title {
  margin: 0 0 var(--space-2);
  font-weight: var(--font-weight-semibold);
  font-size: var(--font-size-sm);
}

.wrong__timeline-result {
  margin: 0;
  font-weight: var(--font-weight-medium);
}

.wrong__timeline-note {
  margin: var(--space-1) 0 0;
  font-size: var(--font-size-xs);
}

.wrong__timeline-meta {
  margin: var(--space-1) 0 0;
  font-size: var(--font-size-xs);
}
</style>
