<script setup lang="ts">
/** 题库列表：搜索、筛选、分页、新建/编辑/发布/归档/删除 */
import { onMounted, ref } from 'vue';
import {
  ElButton,
  ElInput,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag
} from 'element-plus';
import { useRouter } from 'vue-router';

import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import * as questionsApi from '@/api/questions';
import type { ContentStatus } from '@/types/common';
import { QUESTION_TYPES } from '@/types/common';
import type { QuestionSort } from '@/types/question';
import { useQuestionsStore } from '@/stores/questions';
import { confirmAction, toastError, toastSuccess } from '@/utils/confirm';
import { describeError } from '@/utils/error';
import { formatDateTime } from '@/utils/format';
import {
  CONTENT_STATUS_LABELS,
  CONTENT_STATUS_TAG,
  DIFFICULTY_LABELS,
  QUESTION_TYPE_LABELS,
  labelFrom,
  tagFrom
} from '@/utils/labels';

const router = useRouter();
const store = useQuestionsStore();
const actingId = ref<string | null>(null);

const sortOptions: { value: QuestionSort; label: string }[] = [
  { value: '-updatedAt', label: '最近更新' },
  { value: 'updatedAt', label: '最久更新' },
  { value: '-createdAt', label: '最近创建' },
  { value: 'createdAt', label: '最早创建' }
];

const typeOptions = QUESTION_TYPES.map((value) => ({ value, label: QUESTION_TYPE_LABELS[value] }));
const statusOptions: ContentStatus[] = ['draft', 'published', 'archived'];

async function runAction(
  id: string,
  label: string,
  action: () => Promise<unknown>
): Promise<void> {
  actingId.value = id;
  try {
    await action();
    toastSuccess(`${label}成功`);
    await store.fetchList();
  } catch (caught) {
    toastError(describeError(caught).text);
  } finally {
    actingId.value = null;
  }
}

async function publishQuestion(id: string, title: string): Promise<void> {
  const confirmed = await confirmAction(
    `发布后「${title}」的当前版本内容将不可原地修改，如需修改会生成新版本。确认发布？`,
    { title: '发布题目' }
  );
  if (!confirmed) return;
  await runAction(id, '发布', () => questionsApi.publishQuestion(id));
}

async function archiveQuestion(id: string, title: string): Promise<void> {
  const confirmed = await confirmAction(
    `归档后「${title}」不会再出现在默认列表与选题器中；已被试卷或作答引用的题目不会被物理删除。确认归档？`,
    { title: '归档题目' }
  );
  if (!confirmed) return;
  await runAction(id, '归档', () => questionsApi.archiveQuestion(id));
}

async function deleteQuestion(id: string, title: string): Promise<void> {
  const confirmed = await confirmAction(
    `将永久删除草稿「${title}」。仅从未发布、从未被引用的草稿可删除，否则后端会返回状态冲突。此操作不可撤销。`,
    { title: '删除题目', type: 'error', confirmText: '删除' }
  );
  if (!confirmed) return;
  actingId.value = id;
  try {
    await questionsApi.deleteQuestion(id);
    store.removeLocal(id);
    toastSuccess('删除成功');
  } catch (caught) {
    toastError(describeError(caught).text);
  } finally {
    actingId.value = null;
  }
}

function openEdit(id: string): void {
  void router.push(`/questions/${id}/edit`);
}

function openNew(): void {
  void router.push('/questions/new');
}

onMounted(() => {
  void store.fetchList();
});
</script>

<template>
  <div class="question-list">
    <PageHeader title="题库" description="管理题目、题型、知识点标签与版本；发布后版本内容不可原地修改。">
      <template #actions>
        <ElButton type="primary" @click="openNew">新建题目</ElButton>
      </template>
    </PageHeader>

    <section class="panel question-list__filters">
      <div class="toolbar">
        <ElInput
          v-model="store.filters.q"
          placeholder="搜索题干或标题关键字"
          clearable
          class="question-list__search"
          @keyup.enter="store.search()"
          @clear="store.search()"
        />
        <ElSelect v-model="store.filters.questionType" placeholder="题型" clearable class="question-list__select">
          <ElOption v-for="option in typeOptions" :key="option.value" :label="option.label" :value="option.value" />
        </ElSelect>
        <ElSelect v-model="store.filters.status" placeholder="状态" clearable class="question-list__select">
          <ElOption
            v-for="status in statusOptions"
            :key="status"
            :label="CONTENT_STATUS_LABELS[status]"
            :value="status"
          />
        </ElSelect>
        <ElInput v-model="store.filters.tag" placeholder="知识点标签" clearable class="question-list__tag" @keyup.enter="store.search()" />
        <ElSelect v-model="store.filters.sort" class="question-list__select">
          <ElOption v-for="option in sortOptions" :key="option.value" :label="option.label" :value="option.value" />
        </ElSelect>
        <ElButton type="primary" :loading="store.loading" @click="store.search()">查询</ElButton>
        <ElButton :disabled="!store.hasActiveFilters" @click="store.resetFilters()">重置</ElButton>
      </div>
    </section>

    <section class="panel">
      <StatePanel
        :state="store.loading && store.items.length === 0 ? 'loading' : store.error ? 'error' : store.isEmpty ? 'empty' : 'ready'"
        :description="store.error?.message ?? (store.hasActiveFilters ? '没有符合条件的题目，可调整筛选条件后重试。' : '题库还是空的，先创建第一道题目。')"
        :code="store.error?.code ?? null"
        :request-id="store.error?.requestId ?? null"
        :retryable="store.error?.retryable ?? false"
        :action-text="store.error ? '' : store.hasActiveFilters ? '重置筛选' : '新建题目'"
        @retry="store.fetchList()"
        @action="store.hasActiveFilters ? store.resetFilters() : openNew()"
      >
        <ElTable :data="store.items" size="default" row-key="id">
          <ElTableColumn label="标题" min-width="220">
            <template #default="{ row }">
              <button type="button" class="question-list__link" @click="openEdit(String(row.id))">
                {{ row.title }}
              </button>
            </template>
          </ElTableColumn>
          <ElTableColumn label="题型" width="100">
            <template #default="{ row }">
              {{ labelFrom(QUESTION_TYPE_LABELS, row.questionType) }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="100">
            <template #default="{ row }">
              <ElTag size="small" :type="tagFrom(CONTENT_STATUS_TAG, row.status)">
                {{ labelFrom(CONTENT_STATUS_LABELS, row.status) }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="难度" width="90">
            <template #default="{ row }">{{ labelFrom(DIFFICULTY_LABELS, row.difficulty) }}</template>
          </ElTableColumn>
          <ElTableColumn label="版本" width="80">
            <template #default="{ row }">v{{ row.currentVersionNo }}</template>
          </ElTableColumn>
          <ElTableColumn label="知识点" min-width="160">
            <template #default="{ row }">
              <span v-if="!row.knowledgeTags || row.knowledgeTags.length === 0" class="text-muted">—</span>
              <span v-else class="question-list__tags">
                <ElTag v-for="tag in row.knowledgeTags" :key="String(tag)" size="small" effect="plain">
                  {{ tag }}
                </ElTag>
              </span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="更新时间" width="170">
            <template #default="{ row }">{{ formatDateTime(String(row.updatedAt)) }}</template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="250" fixed="right">
            <template #default="{ row }">
              <ElButton size="small" text type="primary" @click="openEdit(String(row.id))">编辑</ElButton>
              <ElButton
                v-if="row.status === 'draft'"
                size="small"
                text
                type="success"
                :loading="actingId === String(row.id)"
                @click="publishQuestion(String(row.id), String(row.title))"
              >
                发布
              </ElButton>
              <ElButton
                v-if="row.status !== 'archived'"
                size="small"
                text
                type="warning"
                :loading="actingId === String(row.id)"
                @click="archiveQuestion(String(row.id), String(row.title))"
              >
                归档
              </ElButton>
              <ElButton
                v-if="row.status === 'draft'"
                size="small"
                text
                type="danger"
                :loading="actingId === String(row.id)"
                @click="deleteQuestion(String(row.id), String(row.title))"
              >
                删除
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
      </StatePanel>

      <div v-if="store.total > 0" class="question-list__pager">
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
.question-list__filters {
  margin-bottom: var(--space-4);
}

.question-list__search {
  width: 260px;
}

.question-list__select {
  width: 140px;
}

.question-list__tag {
  width: 160px;
}

.question-list__link {
  padding: 0;
  border: none;
  background: none;
  color: var(--color-brand-600);
  cursor: pointer;
  font-size: inherit;
  text-align: left;
  font-family: inherit;
}

.question-list__link:hover {
  text-decoration: underline;
}

.question-list__tags {
  display: inline-flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}

.question-list__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-4);
}
</style>
