<script setup lang="ts">
/**
 * 统一状态面板：loading / empty / error / retryable / success。
 * 所有列表页与详情页通过该组件统一四种状态的表现。
 */
import { computed } from 'vue';
import { ElButton, ElResult, ElSkeleton, ElSkeletonItem } from 'element-plus';

const props = withDefaults(
  defineProps<{
    /** 四种主状态之一 */
    state: 'loading' | 'empty' | 'error' | 'ready';
    title?: string;
    description?: string;
    /** 错误码徽标，例如 VALIDATION_ERROR */
    code?: string | null;
    /** 请求编号，便于诊断 */
    requestId?: string | null;
    /** 是否可重试；为 true 时展示「重试」按钮 */
    retryable?: boolean;
    /** 重试按钮文案 */
    retryText?: string;
    /** 加载态骨架屏行数 */
    skeletonRows?: number;
    /** 空状态补充操作文案 */
    actionText?: string;
    /** 空/错误态图标，透传给 el-result */
    icon?: 'success' | 'warning' | 'info' | 'error' | undefined;
  }>(),
  {
    title: '',
    description: '',
    code: null,
    requestId: null,
    retryable: false,
    retryText: '重试',
    skeletonRows: 4,
    actionText: '',
    icon: undefined
  }
);

const emit = defineEmits<{
  (event: 'retry'): void;
  (event: 'action'): void;
}>();

const isError = computed(() => props.state === 'error');
const isUnauthorized = computed(() => isError.value && props.code === 'UNAUTHORIZED');

const resolvedIcon = computed<'success' | 'warning' | 'info' | 'error'>(() => {
  if (props.icon) return props.icon;
  return isError.value ? 'error' : 'info';
});

const resolvedTitle = computed(() => {
  if (props.title) return props.title;
  if (isError.value) return isUnauthorized.value ? '会话已失效' : '加载失败';
  return '暂无数据';
});
</script>

<template>
  <div class="state-panel" role="status" aria-live="polite">
    <template v-if="state === 'loading'">
      <div class="state-panel__loading">
        <p class="state-panel__loading-text">{{ title || '正在加载…' }}</p>
        <ElSkeleton :rows="0" animated>
          <template #default>
            <ElSkeletonItem v-for="row in skeletonRows" :key="row" variant="text" class="state-panel__skeleton-row" />
          </template>
        </ElSkeleton>
      </div>
    </template>

    <template v-else-if="state === 'ready'">
      <slot />
    </template>

    <template v-else>
      <ElResult :icon="resolvedIcon" :title="resolvedTitle">
        <template #sub-title>
          <div class="state-panel__body">
            <p v-if="description" class="state-panel__description">{{ description }}</p>
            <p v-if="code" class="state-panel__meta">
              <span class="state-panel__meta-label">错误码</span>
              <code>{{ code }}</code>
            </p>
            <p v-if="requestId" class="state-panel__meta">
              <span class="state-panel__meta-label">请求编号</span>
              <code>{{ requestId }}</code>
            </p>
            <p v-if="state === 'error' && !retryable" class="state-panel__hint">
              该操作不会自动重试，请按提示处理后重新发起。
            </p>
          </div>
        </template>
        <div class="state-panel__actions">
          <ElButton v-if="retryable" type="primary" @click="emit('retry')">{{ retryText }}</ElButton>
          <ElButton v-if="actionText" @click="emit('action')">{{ actionText }}</ElButton>
          <slot name="actions" />
        </div>
      </ElResult>
    </template>
  </div>
</template>

<style scoped>
.state-panel {
  width: 100%;
}

.state-panel__loading {
  padding: var(--space-6);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

.state-panel__loading-text {
  margin: 0 0 var(--space-4);
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

.state-panel__skeleton-row {
  height: 18px;
  margin-bottom: var(--space-3);
}

.state-panel__body {
  max-width: 560px;
  margin: 0 auto;
}

.state-panel__description {
  margin: 0 0 var(--space-2);
  color: var(--color-text-secondary);
  font-size: var(--font-size-base);
  word-break: break-word;
}

.state-panel__meta {
  margin: var(--space-1) 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
}

.state-panel__meta-label {
  margin-right: var(--space-2);
}

.state-panel__hint {
  margin: var(--space-3) 0 0;
  color: var(--color-warning);
  font-size: var(--font-size-xs);
}

.state-panel__actions {
  display: flex;
  justify-content: center;
  gap: var(--space-3);
  margin-top: var(--space-4);
}
</style>
