/** 试卷列表 store */
import { defineStore } from 'pinia';
import { computed, reactive, ref, shallowRef } from 'vue';

import * as papersApi from '@/api/papers';
import type { ContentStatus, PageResult } from '@/types/common';
import type { PaperListQuery, PaperSummary } from '@/types/paper';
import { describeError, type UiErrorInfo } from '@/utils/error';

interface PaperFilters {
  q: string;
  status: ContentStatus | '';
}

function createFilters(): PaperFilters {
  return { q: '', status: '' };
}

export const usePapersStore = defineStore('papers', () => {
  const items = shallowRef<PaperSummary[]>([]);
  const filters = reactive<PaperFilters>(createFilters());
  const page = ref(1);
  const size = ref(20);
  const total = ref(0);
  const totalPages = ref(0);
  const loading = ref(false);
  const error = ref<UiErrorInfo | null>(null);
  const loaded = ref(false);

  const isEmpty = computed(() => loaded.value && !loading.value && items.value.length === 0);
  const hasActiveFilters = computed(() => filters.q.trim() !== '' || filters.status !== '');

  function buildQuery(): PaperListQuery {
    const query: PaperListQuery = { page: page.value, size: size.value };
    if (filters.q.trim()) query.q = filters.q.trim();
    if (filters.status) query.status = filters.status;
    return query;
  }

  async function fetchList(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const result: PageResult<PaperSummary> = await papersApi.listPapers(buildQuery());
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

  function patchLocal(summary: PaperSummary): void {
    items.value = items.value.map((item) => (item.id === summary.id ? summary : item));
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
    patchLocal
  };
});
