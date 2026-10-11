/** 仪表盘 store */
import { defineStore } from 'pinia';
import { computed, ref, shallowRef } from 'vue';

import { getDashboard } from '@/api/dashboard';
import type { DashboardData } from '@/types/dashboard';
import { describeError, type UiErrorInfo } from '@/utils/error';

export const useDashboardStore = defineStore('dashboard', () => {
  const data = shallowRef<DashboardData | null>(null);
  const loading = ref(false);
  const error = ref<UiErrorInfo | null>(null);
  const lastLoadedAt = ref<string | null>(null);

  const hasData = computed(() => data.value !== null);

  async function load(force = false): Promise<void> {
    if (loading.value) return;
    if (hasData.value && !force) return;
    loading.value = true;
    error.value = null;
    try {
      data.value = await getDashboard();
      lastLoadedAt.value = new Date().toISOString();
    } catch (caught) {
      error.value = describeError(caught);
    } finally {
      loading.value = false;
    }
  }

  async function refresh(): Promise<void> {
    await load(true);
  }

  return { data, loading, error, lastLoadedAt, hasData, load, refresh };
});
