/** 题库列表 store：筛选条件 + 分页 + 四种 UI 状态 */
import { defineStore } from 'pinia';
import { computed, reactive, ref, shallowRef } from 'vue';

import * as questionsApi from '@/api/questions';
import type { ContentStatus, PageResult, QuestionType } from '@/types/common';
import type {
  Question,
  QuestionListQuery,
  QuestionSort,
  QuestionSummary,
  QuestionUpsertPayload
} from '@/types/question';
import { describeError, type UiErrorInfo } from '@/utils/error';

interface QuestionFilters {
  q: string;
  questionType: QuestionType | '';
  status: ContentStatus | '';
  tag: string;
  sort: QuestionSort;
}

function createFilters(): QuestionFilters {
  return { q: '', questionType: '', status: '', tag: '', sort: '-updatedAt' };
}

export const useQuestionsStore = defineStore('questions', () => {
  const items = shallowRef<QuestionSummary[]>([]);
  const filters = reactive<QuestionFilters>(createFilters());
  const page = ref(1);
  const size = ref(20);
  const total = ref(0);
  const totalPages = ref(0);
  const loading = ref(false);
  const error = ref<UiErrorInfo | null>(null);
  const loaded = ref(false);

  const isEmpty = computed(() => loaded.value && !loading.value && items.value.length === 0);
  const hasActiveFilters = computed(
    () =>
      filters.q.trim() !== '' ||
      filters.questionType !== '' ||
      filters.status !== '' ||
      filters.tag.trim() !== ''
  );

  function buildQuery(): QuestionListQuery {
    const query: QuestionListQuery = {
      page: page.value,
      size: size.value,
      sort: filters.sort
    };
    if (filters.q.trim()) query.q = filters.q.trim();
    if (filters.questionType) query.questionType = filters.questionType;
    if (filters.status) query.status = filters.status;
    if (filters.tag.trim()) query.tag = filters.tag.trim();
    return query;
  }

  async function fetchList(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const result: PageResult<QuestionSummary> = await questionsApi.listQuestions(buildQuery());
      items.value = result.items;
      total.value = result.total;
      totalPages.value = result.totalPages;
      page.value = result.page;
      size.value = result.size;
      loaded.value = true;
    } catch (caught) {
      error.value = describeError(caught);
      items.value = [];
      total.value = 0;
      totalPages.value = 0;
      loaded.value = false;
    } finally {
      loading.value = false;
    }
  }

  async function search(): Promise<void> {
    page.value = 1;
    await fetchList();
  }

  async function resetFilters(): Promise<void> {
    Object.assign(filters, createFilters());
    page.value = 1;
    await fetchList();
  }

  async function changePage(next: number): Promise<void> {
    page.value = next;
    await fetchList();
  }

  async function changeSize(next: number): Promise<void> {
    size.value = next;
    page.value = 1;
    await fetchList();
  }

  function removeLocal(id: string): void {
    items.value = items.value.filter((item) => item.id !== id);
    total.value = Math.max(0, total.value - 1);
  }

  function patchLocal(question: Question): void {
    items.value = items.value.map((item) =>
      item.id === question.id
        ? {
            ...item,
            title: question.title,
            questionType: question.questionType,
            status: question.status,
            currentVersionNo: question.currentVersion.versionNo,
            knowledgeTags: question.currentVersion.knowledgeTags,
            difficulty: question.difficulty,
            updatedAt: question.updatedAt
          }
        : item
    );
  }

  /** 新建/更新（供编辑页复用，统一错误处理交给调用方） */
  async function create(payload: QuestionUpsertPayload): Promise<Question> {
    return questionsApi.createQuestion(payload);
  }

  async function update(id: string, payload: QuestionUpsertPayload): Promise<Question> {
    return questionsApi.updateQuestion(id, payload);
  }

  return {
    items,
    filters,
    page,
    size,
    total,
    totalPages,
    loading,
    error,
    loaded,
    isEmpty,
    hasActiveFilters,
    fetchList,
    search,
    resetFilters,
    changePage,
    changeSize,
    removeLocal,
    patchLocal,
    create,
    update
  };
});
