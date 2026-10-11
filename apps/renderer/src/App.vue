<script setup lang="ts">
/** 应用根组件：运行时守卫 → 主布局 */
import { onBeforeUnmount } from 'vue';

import AppLayout from '@/components/AppLayout.vue';
import RuntimeGuard from '@/components/RuntimeGuard.vue';
import { useRuntimeStore } from '@/stores/runtime';

const runtime = useRuntimeStore();

onBeforeUnmount(() => {
  runtime.dispose();
});
</script>

<template>
  <RuntimeGuard>
    <AppLayout />
  </RuntimeGuard>
</template>
