<script setup lang="ts">
/** 仪表盘：继续答题、最近作答、待复习错题、题库/试卷统计、服务状态 */
import { computed, onMounted } from 'vue';
import { ElButton, ElEmpty, ElTable, ElTableColumn, ElTag } from 'element-plus';
import { useRouter } from 'vue-router';

import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import type { AttemptSummary } from '@/types/attempt';
import type { WrongQuestion } from '@/types/wrongQuestion';
import { useDashboardStore } from '@/stores/dashboard';
import { useRuntimeStore } from '@/stores/runtime';
import {
  ATTEMPT_STATUS_LABELS,
  ATTEMPT_STATUS_TAG,
  WRONG_STATUS_LABELS,
  WRONG_STATUS_TAG,
  labelFrom,
  tagFrom
} from '@/utils/labels';
import { formatDateTime, formatRelative, formatScore } from '@/utils/format';

const router = useRouter();
const dashboard = useDashboardStore();
const runtime = useRuntimeStore();

const data = computed(() => dashboard.data);

const stats = computed(() => [
  {
    key: 'questions',
    label: '题目总数',
    value: data.value?.questionCount ?? 0,
    hint: `已发布 ${data.value?.publishedQuestionCount ?? 0}`
  },
  {
    key: 'papers',
    label: '试卷总数',
    value: data.value?.paperCount ?? 0,
    hint: `已发布 ${data.value?.publishedPaperCount ?? 0}`
  },
  {
    key: 'inProgress',
    label: '进行中作答',
    value: data.value?.inProgressAttemptCount ?? 0,
    hint: '可继续作答'
  },
  {
    key: 'dueWrong',
    label: '待复习错题',
    value: data.value?.dueWrongQuestionCount ?? 0,
    hint: `活跃 ${data.value?.activeWrongQuestionCount ?? 0} / 已掌握 ${data.value?.masteredWrongQuestionCount ?? 0}`
  }
]);

const inProgressAttempt = computed<AttemptSummary | null>(() => {
  const list = data.value?.recentAttempts ?? [];
  return list.find((item) => item.status === 'in_progress') ?? null;
});

const recentAttempts = computed(() => data.value?.recentAttempts ?? []);
const recentWrongQuestions = computed(() => data.value?.recentWrongQuestions ?? []);

const aiAvailable = computed(() => data.value?.aiService.available ?? false);
/** AI 服务补充说明（来自 `/meta`、`/system/info` 的 aiService.message） */
const aiServiceMessage = computed(
  () => runtime.meta?.aiService.message ?? data.value?.aiService.message ?? null
);

async function load(): Promise<void> {
  await dashboard.load(true);
  await runtime.probeHealth();
}

function continueAnswering(): void {
  if (inProgressAttempt.value) {
    void router.push(`/attempts/${inProgressAttempt.value.id}`);
    return;
  }
  void router.push('/papers');
}

function goAttempt(attempt: AttemptSummary): void {
  if (attempt.status === 'in_progress') {
    void router.push(`/attempts/${attempt.id}`);
    return;
  }
  void router.push(`/attempts/${attempt.id}/result`);
}

function goWrongQuestion(item: WrongQuestion): void {
  void router.push({ path: '/wrong-questions', query: { focus: item.id } });
}

onMounted(() => {
  void load();
});
</script>

<template>
  <div class="dashboard">
    <PageHeader title="仪表盘" description="查看学习进度、待复习错题与本机服务状态。">
      <template #actions>
        <ElButton :loading="runtime.status === 'loading'" @click="load">刷新</ElButton>
        <ElButton type="primary" @click="continueAnswering">
          {{ inProgressAttempt ? '继续答题' : '选择试卷开始答题' }}
        </ElButton>
      </template>
    </PageHeader>

    <StatePanel
      :state="dashboard.loading && !dashboard.hasData ? 'loading' : dashboard.error ? 'error' : 'ready'"
      :description="dashboard.error?.message"
      :code="dashboard.error?.code ?? null"
      :request-id="dashboard.error?.requestId ?? null"
      :retryable="dashboard.error?.retryable ?? false"
      @retry="load"
    >
      <section class="dashboard__stats">
        <article v-for="stat in stats" :key="stat.key" class="stat-card">
          <p class="stat-card__label">{{ stat.label }}</p>
          <p class="stat-card__value">{{ stat.value }}</p>
          <p class="stat-card__hint">{{ stat.hint }}</p>
        </article>
      </section>

      <section class="dashboard__service panel">
        <h2 class="dashboard__section-title">本机服务状态</h2>
        <div class="dashboard__service-row">
          <ElTag :type="runtime.javaState === 'up' ? 'success' : runtime.javaState === 'down' ? 'danger' : 'info'">
            Java 业务服务：{{ runtime.javaState === 'up' ? '正常' : runtime.javaState === 'down' ? '不可用' : '未知' }}
          </ElTag>
          <ElTag :type="aiAvailable ? 'success' : 'warning'">
            AI 服务：{{ aiAvailable ? '可用' : '不可用（不影响答题与判分主流程）' }}
          </ElTag>
          <ElTag :type="runtime.pythonState === 'up' ? 'success' : 'warning'">
            AI 能力：{{ runtime.pythonState === 'up' ? '可用' : runtime.pythonState === 'down' ? '不可用' : '未探测' }}
          </ElTag>
          <span v-if="aiServiceMessage" class="text-muted dashboard__service-meta">{{ aiServiceMessage }}</span>
          <span class="text-muted dashboard__service-meta">
            schemaVersion：{{ runtime.javaHealth?.schemaVersion ?? '—' }}
            · 契约版本：{{ runtime.meta?.contractVersion ?? '—' }}
            · 运行时来源：{{ runtime.context?.source ?? '—' }}
            · 最近探测：{{ runtime.lastCheckedAt ? formatDateTime(runtime.lastCheckedAt) : '—' }}
          </span>
        </div>
      </section>

      <section class="dashboard__grid">
        <div class="panel">
          <h2 class="dashboard__section-title">最近作答</h2>
          <StatePanel v-if="recentAttempts.length === 0" state="empty" title="还没有作答记录" description="从试卷列表选择一张已发布试卷开始答题。">
            <template #actions>
              <ElButton type="primary" @click="router.push('/papers')">前往试卷列表</ElButton>
            </template>
          </StatePanel>
          <ElTable v-else :data="recentAttempts" size="small" @row-click="goAttempt">
            <ElTableColumn label="试卷" min-width="200">
              <template #default="{ row }">
                <span class="dashboard__link">{{ row.paperTitle || '（未命名试卷）' }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="状态" width="100">
              <template #default="{ row }">
                <ElTag size="small" :type="tagFrom(ATTEMPT_STATUS_TAG, row.status)">
                  {{ labelFrom(ATTEMPT_STATUS_LABELS, row.status) }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="得分" width="110">
              <template #default="{ row }">
                {{ formatScore(row.totalScore, row.maxScore) }}
              </template>
            </ElTableColumn>
            <ElTableColumn label="开始时间" width="160">
              <template #default="{ row }">
                {{ formatDateTime(row.startedAt) }}
              </template>
            </ElTableColumn>
          </ElTable>
        </div>

        <div class="panel">
          <h2 class="dashboard__section-title">最近错题</h2>
          <StatePanel
            v-if="recentWrongQuestions.length === 0"
            state="empty"
            title="暂时没有错题"
            description="客观题判分后会自动进入错题本。"
          />
          <ElTable v-else :data="recentWrongQuestions" size="small" @row-click="goWrongQuestion">
            <ElTableColumn label="题目" min-width="200">
              <template #default="{ row }">
                <span class="dashboard__link">{{ row.title }}</span>
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
            <ElTableColumn label="下次复习" width="120">
              <template #default="{ row }">
                {{ row.nextReviewAt ? formatRelative(row.nextReviewAt) : '—' }}
              </template>
            </ElTableColumn>
            <template #empty>
              <ElEmpty description="暂无数据" />
            </template>
          </ElTable>
        </div>
      </section>
    </StatePanel>
  </div>
</template>

<style scoped>
.dashboard__stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.stat-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--space-4) var(--space-5);
  box-shadow: var(--shadow-sm);
}

.stat-card__label {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

.stat-card__value {
  margin: var(--space-2) 0 0;
  font-size: var(--font-size-2xl);
  font-weight: var(--font-weight-semibold);
  line-height: var(--line-height-tight);
  color: var(--color-brand-600);
}

.stat-card__hint {
  margin: var(--space-1) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}

.dashboard__service-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
}

.dashboard__service-meta {
  font-size: var(--font-size-xs);
}

.dashboard__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(420px, 1fr));
  gap: var(--space-4);
  margin-top: var(--space-4);
}

.dashboard__section-title {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-semibold);
}

.dashboard__link {
  color: var(--color-brand-600);
  cursor: pointer;
}
</style>
