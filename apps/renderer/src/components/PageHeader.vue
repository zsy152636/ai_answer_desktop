<script setup lang="ts">
/** 页面头部：面包屑 + 标题 + 描述 + 右侧操作区 */
import { ElBreadcrumb, ElBreadcrumbItem } from 'element-plus';

export interface BreadcrumbEntry {
  label: string;
  /** 不传则为纯文本（当前页） */
  to?: string;
}

withDefaults(
  defineProps<{
    title: string;
    description?: string;
    breadcrumbs?: BreadcrumbEntry[];
  }>(),
  {
    description: '',
    breadcrumbs: () => []
  }
);
</script>

<template>
  <header class="page-header">
    <div class="page-header__main">
      <ElBreadcrumb v-if="breadcrumbs.length > 0" separator="/" class="page-header__crumbs">
        <ElBreadcrumbItem v-for="(entry, index) in breadcrumbs" :key="`${entry.label}-${index}`" :to="entry.to">
          {{ entry.label }}
        </ElBreadcrumbItem>
      </ElBreadcrumb>
      <h1 class="page-header__title">{{ title }}</h1>
      <p v-if="description" class="page-header__description">{{ description }}</p>
    </div>
    <div class="page-header__actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
  margin-bottom: var(--space-5);
}

.page-header__main {
  min-width: 0;
}

.page-header__crumbs {
  margin-bottom: var(--space-2);
  font-size: var(--font-size-xs);
}

.page-header__title {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-semibold);
  line-height: var(--line-height-tight);
}

.page-header__description {
  margin: var(--space-1) 0 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  max-width: 760px;
}

.page-header__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}
</style>
