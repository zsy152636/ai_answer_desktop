<script setup lang="ts">
/** 试卷列表：新建、编辑、发布、归档 */
import { onMounted, ref } from 'vue';
import { ElButton, ElInput, ElOption, ElPagination, ElSelect, ElTable, ElTableColumn, ElTag } from 'element-plus';
import { useRouter } from 'vue-router';

import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import * as papersApi from '@/api/papers';
import type { ContentStatus } from '@/types/common';
import { usePapersStore } from '@/stores/papers';
import { confirmAction, toastError, toastSuccess } from '@/utils/confirm';
import { describeError } from '@/utils/error';
import { formatDateTime } from '@/utils/format';
import { CONTENT_STATUS_LABELS, CONTENT_STATUS_TAG, labelFrom, tagFrom } from '@/utils/labels';

const router = useRouter();
const store = usePapersStore();
const actingId = ref<string | null>(null);

const statusOptions: ContentStatus[] = ['draft', 'published', 'archived'];

async function publishPaper(id: string, title: string, questionCount: number, totalScore: number): Promise<void> {
  if (questionCount < 1) {
    toastError('试卷至少需要 1 道题目才能发布。');
    return;
  }
  if (totalScore <= 0) {
    toastError('试卷总分必须大于 0 才能发布。');
    return;
  }
  const confirmed = await confirmAction(
    `发布「${title}」将生成不可变快照（${questionCount} 题 / 总分 ${totalScore}）。发布后旧快照与既有作答结果永不改变。确认发布？`,
    { title: '发布试卷', confirmText: '发布' }
  );
  if (!confirmed) return;

  actingId.value = id;
  try {
    await papersApi.publishPaper(id);
    toastSuccess('发布成功，已生成快照');
    await store.fetchList();
  } catch (caught) {
    toastError(describeError(caught).text);
  } finally {
    actingId.value = null;
  }
}

async function archivePaper(id: string, title: string): Promise<void> {
  const confirmed = await confirmAction(`确认归档试卷「${title}」？归档后不会再出现在默认列表。`, {
    title: '归档试卷'
  });
  if (!confirmed) return;
  actingId.value = id;
  try {
    await papersApi.archivePaper(id);
    toastSuccess('归档成功');
    await store.fetchList();
  } catch (caught) {
    toastError(describeError(caught).text);
  } finally {
    actingId.value = null;
  }
}

function openEdit(id: string): void {
  void router.push(`/papers/${id}/edit`);
}

function openNew(): void {
  void router.push('/papers/new');
}

onMounted(() => {
  void store.fetchList();
});
</script>

<template>
  <div class="paper-list">
    <PageHeader title="试卷" description="组卷、排序、分值设置与发布；发布生成不可变快照。">
      <template #actions>
        <ElButton type="primary" @click="openNew">新建试卷</ElButton>
      </template>
    </PageHeader>

    <section class="panel paper-list__filters">
      <div class="toolbar">
        <ElInput
          v-model="store.filters.q"
          placeholder="搜索试卷标题"
          clearable
          class="paper-list__search"
          @keyup.enter="store.search()"
          @clear="store.search()"
        />
        <ElSelect v-model="store.filters.status" placeholder="状态" clearable class="paper-list__select">
          <ElOption
            v-for="status in statusOptions"
            :key="status"
            :label="CONTENT_STATUS_LABELS[status]"
            :value="status"
          />
        </ElSelect>
        <ElButton type="primary" :loading="store.loading" @click="store.search()">查询</ElButton>
        <ElButton :disabled="!store.hasActiveFilters" @click="store.resetFilters()">重置</ElButton>
      </div>
    </section>

    <section class="panel">
      <StatePanel
        :state="store.loading && store.items.length === 0 ? 'loading' : store.error ? 'error' : store.isEmpty ? 'empty' : 'ready'"
        :description="store.error?.message ?? (store.hasActiveFilters ? '没有符合条件的试卷，可调整筛选条件。' : '还没有试卷，先新建一张试卷并从题库挑选题目。')"
        :code="store.error?.code ?? null"
        :request-id="store.error?.requestId ?? null"
        :retryable="store.error?.retryable ?? false"
        :action-text="store.error ? '' : store.hasActiveFilters ? '重置筛选' : '新建试卷'"
        @retry="store.fetchList()"
        @action="store.hasActiveFilters ? store.resetFilters() : openNew()"
      >
        <ElTable :data="store.items" row-key="id">
          <ElTableColumn label="标题" min-width="220">
            <template #default="{ row }">
              <button type="button" class="paper-list__link" @click="openEdit(String(row.id))">
                {{ row.title }}
              </button>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="100">
            <template #default="{ row }">
              <ElTag size="small" :type="tagFrom(CONTENT_STATUS_TAG, row.status)">
                {{ labelFrom(CONTENT_STATUS_LABELS, row.status) }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="题数" width="80" prop="questionCount" />
          <ElTableColumn label="总分" width="80" prop="totalScore" />
          <ElTableColumn label="发布时间" width="170">
            <template #default="{ row }">{{ formatDateTime(row.publishedAt ? String(row.publishedAt) : null) }}</template>
          </ElTableColumn>
          <ElTableColumn label="更新时间" width="170">
            <template #default="{ row }">{{ formatDateTime(String(row.updatedAt)) }}</template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <ElButton size="small" text type="primary" @click="openEdit(String(row.id))">编辑</ElButton>
              <ElButton
                v-if="row.status === 'draft'"
                size="small"
                text
                type="success"
                :loading="actingId === String(row.id)"
                @click="publishPaper(String(row.id), String(row.title), Number(row.questionCount), Number(row.totalScore))"
              >
                发布
              </ElButton>
              <ElButton
                v-if="row.status !== 'archived'"
                size="small"
                text
                type="warning"
                :loading="actingId === String(row.id)"
                @click="archivePaper(String(row.id), String(row.title))"
              >
                归档
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
      </StatePanel>

      <div v-if="store.total > 0" class="paper-list__pager">
        <ElPagination
          :current-page="store.page"
          :page-size="store.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="store.total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="store.changePage"
          @size-change="store.changeSize"
        />
      </div>
    </section>
  </div>
</template>

<style scoped>
.paper-list__filters {
  margin-bottom: var(--space-4);
}

.paper-list__search {
  width: 260px;
}

.paper-list__select {
  width: 140px;
}

.paper-list__link {
  padding: 0;
  border: none;
  background: none;
  color: var(--color-brand-600);
  cursor: pointer;
  font-size: inherit;
  font-family: inherit;
  text-align: left;
}

.paper-list__link:hover {
  text-decoration: underline;
}

.paper-list__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-4);
}
</style>
