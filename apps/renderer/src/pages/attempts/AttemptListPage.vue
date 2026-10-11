<script setup lang="ts">
/** 作答记录：列表、筛选、继续作答、查看结果；也从这里开始新的整卷作答 */
import { computed, onMounted, reactive, ref } from 'vue';
import {
  ElButton,
  ElDialog,
  ElPagination,
  ElSelect,
  ElOption,
  ElTable,
  ElTableColumn,
  ElTag
} from 'element-plus';
import { useRouter } from 'vue-router';

import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import * as attemptsApi from '@/api/attempts';
import * as papersApi from '@/api/papers';
import type { AttemptStatus, ContentStatus } from '@/types/common';
import { ATTEMPT_STATUSES } from '@/types/common';
import type { AttemptSummary } from '@/types/attempt';
import type { PaperSummary } from '@/types/paper';
import { confirmAction, toastError, toastSuccess } from '@/utils/confirm';
import { describeError } from '@/utils/error';
import { formatDateTime, formatDuration, formatScore } from '@/utils/format';
import { ATTEMPT_STATUS_LABELS, labelFrom } from '@/utils/labels';

const router = useRouter();

const items = ref<AttemptSummary[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const errorCode = ref<string | null>(null);
const retryable = ref(false);
const loaded = ref(false);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const filters = reactive<{ status: AttemptStatus | ''; paperId: string }>({ status: '', paperId: '' });

const startVisible = ref(false);
const paperLoading = ref(false);
const paperError = ref<string | null>(null);
const papers = ref<PaperSummary[]>([]);
const startingId = ref<string | null>(null);

const isEmpty = computed(() => loaded.value && !loading.value && items.value.length === 0);

async function fetchList(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const query: Parameters<typeof attemptsApi.listAttempts>[0] = { page: page.value, size: size.value };
    if (filters.status) query.status = filters.status;
    if (filters.paperId.trim()) query.paperId = filters.paperId.trim();
    const result = await attemptsApi.listAttempts(query);
    items.value = result.items;
    total.value = result.total;
    loaded.value = true;
  } catch (caught) {
    const info = describeError(caught);
    error.value = info.message;
    errorCode.value = info.code;
    retryable.value = info.retryable;
    items.value = [];
    total.value = 0;
    loaded.value = false;
  } finally {
    loading.value = false;
  }
}

async function loadPapers(): Promise<void> {
  paperLoading.value = true;
  paperError.value = null;
  try {
    const result = await papersApi.listPapers({ page: 1, size: 50, status: 'published' as ContentStatus });
    papers.value = result.items;
  } catch (caught) {
    paperError.value = describeError(caught).text;
    papers.value = [];
  } finally {
    paperLoading.value = false;
  }
}

function openStartDialog(): void {
  startVisible.value = true;
  void loadPapers();
}

async function startAttempt(paper: PaperSummary): Promise<void> {
  const confirmed = await confirmAction(
    `将基于「${paper.title}」的最新已发布快照创建一次新的作答，共 ${paper.questionCount} 题 / 总分 ${paper.totalScore}。确认开始？`,
    { title: '开始答题', confirmText: '开始' }
  );
  if (!confirmed) return;
  startingId.value = paper.id;
  try {
    const sheet = await attemptsApi.createAttempt({ paperId: paper.id });
    toastSuccess('已创建作答，正在进入答题页');
    startVisible.value = false;
    await router.push(`/attempts/${sheet.id}`);
  } catch (caught) {
    toastError(describeError(caught).text);
  } finally {
    startingId.value = null;
  }
}

function openAttempt(attempt: AttemptSummary): void {
  if (attempt.status === 'in_progress') {
    void router.push(`/attempts/${attempt.id}`);
    return;
  }
  void router.push(`/attempts/${attempt.id}/result`);
}

function duration(attempt: AttemptSummary): string {
  if (!attempt.submittedAt) return '—';
  const start = new Date(attempt.startedAt).getTime();
  const end = new Date(attempt.submittedAt).getTime();
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return '—';
  return formatDuration((end - start) / 1000);
}

onMounted(() => {
  void fetchList();
});
</script>

<template>
  <div class="attempt-list">
    <PageHeader title="作答记录" description="查看历史作答、继续未完成的作答，或从已发布试卷开始新的作答。">
      <template #actions>
        <ElButton type="primary" @click="openStartDialog">开始整卷作答</ElButton>
      </template>
    </PageHeader>

    <section class="panel attempt-list__filters">
      <div class="toolbar">
        <ElSelect v-model="filters.status" placeholder="状态" clearable class="attempt-list__select" @change="fetchList">
          <ElOption
            v-for="status in ATTEMPT_STATUSES"
            :key="status"
            :label="ATTEMPT_STATUS_LABELS[status]"
            :value="status"
          />
        </ElSelect>
        <ElButton type="primary" :loading="loading" @click="fetchList">刷新</ElButton>
      </div>
    </section>

    <section class="panel">
      <StatePanel
        :state="loading && items.length === 0 ? 'loading' : error ? 'error' : isEmpty ? 'empty' : 'ready'"
        :description="error ?? '还没有作答记录，可从已发布试卷开始一次作答。'"
        :code="errorCode"
        :retryable="retryable"
        action-text="开始整卷作答"
        @retry="fetchList"
        @action="openStartDialog"
      >
        <ElTable :data="items" row-key="id" @row-click="openAttempt">
          <ElTableColumn label="试卷" min-width="220">
            <template #default="{ row }">
              <span class="attempt-list__link">{{ row.paperTitle || '（未命名试卷）' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="100">
            <template #default="{ row }">
              <ElTag size="small" :type="row.status === 'graded' ? 'success' : row.status === 'in_progress' ? 'primary' : 'info'">
                {{ labelFrom(ATTEMPT_STATUS_LABELS, row.status) }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="得分" width="120">
            <template #default="{ row }">{{ formatScore(row.totalScore, row.maxScore) }}</template>
          </ElTableColumn>
          <ElTableColumn label="题数" width="80" prop="questionCount" />
          <ElTableColumn label="开始时间" width="170">
            <template #default="{ row }">{{ formatDateTime(String(row.startedAt)) }}</template>
          </ElTableColumn>
          <ElTableColumn label="用时" width="110">
            <template #default="{ row }">{{ duration(row as AttemptSummary) }}</template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <ElButton size="small" text type="primary" @click.stop="openAttempt(row as AttemptSummary)">
                {{ row.status === 'in_progress' ? '继续作答' : '查看结果' }}
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
      </StatePanel>

      <div v-if="total > 0" class="attempt-list__pager">
        <ElPagination
          :current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          background
          @current-change="(value: number) => { page = value; fetchList(); }"
        />
      </div>
    </section>

    <ElDialog v-model="startVisible" title="选择试卷开始作答" width="720px" top="8vh">
      <StatePanel
        :state="paperLoading ? 'loading' : paperError ? 'error' : papers.length === 0 ? 'empty' : 'ready'"
        :description="paperError ?? '没有已发布的试卷，请先在试卷页发布一张试卷。'"
        :retryable="Boolean(paperError)"
        :skeleton-rows="4"
        @retry="loadPapers"
      >
        <ElTable :data="papers" height="380" row-key="id">
          <ElTableColumn label="试卷" min-width="220" prop="title" />
          <ElTableColumn label="题数" width="80" prop="questionCount" />
          <ElTableColumn label="总分" width="80" prop="totalScore" />
          <ElTableColumn label="发布时间" width="170">
            <template #default="{ row }">{{ formatDateTime(row.publishedAt ? String(row.publishedAt) : null) }}</template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="110">
            <template #default="{ row }">
              <ElButton
                size="small"
                type="primary"
                :loading="startingId === String(row.id)"
                @click="startAttempt(row as PaperSummary)"
              >
                开始
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
      </StatePanel>
    </ElDialog>
  </div>
</template>

<style scoped>
.attempt-list__filters {
  margin-bottom: var(--space-4);
}

.attempt-list__select {
  width: 160px;
}

.attempt-list__link {
  color: var(--color-brand-600);
  cursor: pointer;
}

.attempt-list__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-4);
}
</style>
