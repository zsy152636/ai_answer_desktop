/** 错题本 store */
import { defineStore } from 'pinia';
import { computed, reactive, ref, shallowRef } from 'vue';

import * as wrongQuestionsApi from '@/api/wrongQuestions';
import type { PageResult, QuestionType, WrongStatus } from '@/types/common';
import {
  DEFAULT_WRONG_QUESTION_SORT,
  type PatchWrongQuestionPayload,
  type ReviewWrongQuestionPayload,
  type WrongQuestion,
  type WrongQuestionDetail,
  type WrongQuestionListQuery,
  type WrongQuestionSort
} from '@/types/wrongQuestion';
import { describeError, type UiErrorInfo } from '@/utils/error';

interface WrongFilters {
  status: WrongStatus | '';
  questionType: QuestionType | '';
  tag: string;
  dueOnly: boolean;
  sort: WrongQuestionSort;
}

function createFilters(): WrongFilters {
  // 默认 -updatedAt，与后端 WrongQuestionRepository#orderClause 的缺省一致
  return { status: '', questionType: '', tag: '', dueOnly: false, sort: DEFAULT_WRONG_QUESTION_SORT };
}

export const useWrongQuestionsStore = defineStore('wrongQuestions', () => {
  const items = shallowRef<WrongQuestion[]>([]);
  const detail = shallowRef<WrongQuestionDetail | null>(null);
  const filters = reactive<WrongFilters>(createFilters());
  const page = ref(1);
  const size = ref(20);
  const total = ref(0);
  const totalPages = ref(0);
  const loading = ref(false);
  const detailLoading = ref(false);
  const error = ref<UiErrorInfo | null>(null);
  const detailError = ref<UiErrorInfo | null>(null);
  const loaded = ref(false);
  const selectedIds = ref<string[]>([]);

  const isEmpty = computed(() => loaded.value && !loading.value && items.value.length === 0);

  function buildQuery(): WrongQuestionListQuery {
    const query: WrongQuestionListQuery = {
      page: page.value,
      size: size.value,
      sort: filters.sort
    };
    if (filters.status) query.status = filters.status;
    if (filters.questionType) query.questionType = filters.questionType;
    if (filters.tag.trim()) query.tag = filters.tag.trim();
    if (filters.dueOnly) query.dueOnly = true;
    return query;
  }

  async function fetchList(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const result: PageResult<WrongQuestion> = await wrongQuestionsApi.listWrongQuestions(buildQuery());
      items.value = result.items;
      total.value = result.total;
      totalPages.value = result.totalPages;
      page.value = result.page;
      size.value = result.size;
      loaded.value = true;
      selectedIds.value = selectedIds.value.filter((id) => result.items.some((item) => item.id === id));
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

  async function loadDetail(id: string): Promise<void> {
    detailLoading.value = true;
    detailError.value = null;
    try {
      detail.value = await wrongQuestionsApi.getWrongQuestion(id);
    } catch (caught) {
      detailError.value = describeError(caught);
      detail.value = null;
    } finally {
      detailLoading.value = false;
    }
  }

  async function review(id: string, payload: ReviewWrongQuestionPayload): Promise<WrongQuestionDetail> {
    const updated = await wrongQuestionsApi.reviewWrongQuestion(id, payload);
    detail.value = updated;
    patchLocal(updated.question);
    return updated;
  }

  /** 手动标记掌握/归档/恢复；服务端返回 `{ question, reviews[] }` */
  async function patchStatus(id: string, payload: PatchWrongQuestionPayload): Promise<void> {
    const updated = await wrongQuestionsApi.patchWrongQuestion(id, payload);
    detail.value = updated;
    patchLocal(updated.question);
  }

  function patchLocal(updated: WrongQuestion): void {
    items.value = items.value.map((item) => (item.id === updated.id ? updated : item));
  }

  function toggleSelect(id: string): void {
    selectedIds.value = selectedIds.value.includes(id)
      ? selectedIds.value.filter((item) => item !== id)
      : [...selectedIds.value, id];
  }

  function clearSelection(): void {
    selectedIds.value = [];
  }

  return {
    items,
    detail,
    filters,
    page,
    size,
    total,
    totalPages,
    loading,
    detailLoading,
    error,
    detailError,
    loaded,
    isEmpty,
    selectedIds,
    fetchList,
    search,
    resetFilters,
    changePage,
    changeSize,
    loadDetail,
    review,
    patchStatus,
    toggleSelect,
    clearSelection
  };
});
