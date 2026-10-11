<script setup lang="ts">
/** 应用主布局：侧边导航 + 顶部状态栏 + 内容区 */
import { computed, onMounted } from 'vue';
import { ElButton, ElScrollbar, ElTag, ElTooltip } from 'element-plus';
import { RouterLink, RouterView, useRoute } from 'vue-router';

import { useRuntimeStore } from '@/stores/runtime';
import { useDashboardStore } from '@/stores/dashboard';

const route = useRoute();
const runtime = useRuntimeStore();
const dashboard = useDashboardStore();

interface NavItem {
  path: string;
  label: string;
  icon: string;
}

interface NavGroup {
  title: string;
  items: NavItem[];
}

const navGroups: NavGroup[] = [
  {
    title: '学习',
    items: [
      { path: '/', label: '仪表盘', icon: '◉' },
      { path: '/attempts', label: '作答记录', icon: '▤' },
      { path: '/wrong-questions', label: '错题本', icon: '⚑' }
    ]
  },
  {
    title: '内容',
    items: [
      { path: '/questions', label: '题库', icon: '☰' },
      { path: '/papers', label: '试卷', icon: '▦' },
      { path: '/documents', label: '资料库', icon: '❐' }
    ]
  },
  {
    title: '设置',
    items: [
      { path: '/settings/llm', label: 'LLM 设置', icon: '✦' },
      { path: '/settings/system', label: '系统设置', icon: '⚙' },
      { path: '/about', label: '关于', icon: 'ⓘ' }
    ]
  }
];

const javaStateMeta = computed(() => {
  switch (runtime.javaState) {
    case 'up':
      return { text: 'Java 服务正常', type: 'success' as const };
    case 'down':
      return { text: 'Java 服务不可用', type: 'danger' as const };
    default:
      return { text: 'Java 服务状态未知', type: 'info' as const };
  }
});

const pythonStateMeta = computed(() => {
  // pythonState 由 Java `/meta`、`/system/info` 的 aiService.available 推导（渲染层不直接探测 Python）
  switch (runtime.pythonState) {
    case 'up':
      return { text: 'AI 服务可用', type: 'success' as const };
    case 'down':
      return { text: 'AI 服务不可用', type: 'warning' as const };
    default:
      return { text: 'AI 服务状态未知', type: 'info' as const };
  }
});

function isActive(path: string): boolean {
  if (path === '/') return route.path === '/';
  return route.path === path || route.path.startsWith(`${path}/`);
}

const dueWrongCount = computed(() => dashboard.data?.dueWrongQuestionCount ?? 0);

onMounted(() => {
  if (!dashboard.hasData) void dashboard.load();
});
</script>

<template>
  <div class="app-layout">
    <aside class="app-sidebar">
      <div class="app-sidebar__brand">
        <span class="app-sidebar__logo">AI</span>
        <div class="app-sidebar__brand-text">
          <strong>智能答题系统</strong>
          <span class="text-muted">本地一体化 · v1</span>
        </div>
      </div>

      <ElScrollbar class="app-sidebar__nav-scroll">
        <nav class="app-sidebar__nav">
          <div v-for="group in navGroups" :key="group.title" class="app-sidebar__group">
            <p class="app-sidebar__group-title">{{ group.title }}</p>
            <RouterLink
              v-for="item in group.items"
              :key="item.path"
              :to="item.path"
              class="app-sidebar__link"
              :class="{ 'is-active': isActive(item.path) }"
            >
              <span class="app-sidebar__link-icon" aria-hidden="true">{{ item.icon }}</span>
              <span class="app-sidebar__link-label">{{ item.label }}</span>
              <ElTag v-if="item.path === '/wrong-questions' && dueWrongCount > 0" size="small" type="danger">
                {{ dueWrongCount }}
              </ElTag>
            </RouterLink>
          </div>
        </nav>
      </ElScrollbar>

      <div class="app-sidebar__footer">
        <p class="app-sidebar__footer-line">
          <span class="text-muted">运行模式</span>
          <span>{{ runtime.isDesktop ? '桌面壳' : '浏览器调试' }}</span>
        </p>
        <p class="app-sidebar__footer-line">
          <span class="text-muted">应用版本</span>
          <span>{{ runtime.appVersion }}</span>
        </p>
      </div>
    </aside>

    <div class="app-main">
      <header class="app-topbar">
        <div class="app-topbar__service">
          <ElTooltip :content="`javaBaseUrl: ${runtime.javaBaseUrl || '未解析'}`" placement="bottom">
            <ElTag :type="javaStateMeta.type" effect="light">{{ javaStateMeta.text }}</ElTag>
          </ElTooltip>
          <ElTag :type="pythonStateMeta.type" effect="light">{{ pythonStateMeta.text }}</ElTag>
        </div>
        <div class="app-topbar__actions">
          <span v-if="runtime.context?.source === 'dev-shim'" class="app-topbar__hint text-muted">
            运行时来自 dev shim（/__app_runtime）
          </span>
          <ElButton size="small" text @click="runtime.probeHealth()">刷新状态</ElButton>
        </div>
      </header>

      <main class="app-content">
        <div class="app-content__inner">
          <RouterView v-slot="{ Component }">
            <component :is="Component" />
          </RouterView>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--color-bg);
}

.app-sidebar {
  display: flex;
  flex-direction: column;
  width: var(--layout-sidebar-width);
  flex: 0 0 var(--layout-sidebar-width);
  background: var(--color-neutral-900);
  color: var(--color-neutral-100);
  border-right: 1px solid var(--color-neutral-800);
}

.app-sidebar__brand {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-4) var(--space-3);
}

.app-sidebar__logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: var(--radius-md);
  background: var(--color-brand-500);
  color: var(--color-neutral-0);
  font-weight: var(--font-weight-semibold);
  font-size: var(--font-size-sm);
}

.app-sidebar__brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}

.app-sidebar__brand-text strong {
  font-size: var(--font-size-md);
  color: var(--color-neutral-0);
}

.app-sidebar__brand-text .text-muted {
  font-size: var(--font-size-xs);
  color: var(--color-neutral-400);
}

.app-sidebar__nav-scroll {
  flex: 1;
  min-height: 0;
}

.app-sidebar__nav {
  padding: var(--space-2) var(--space-3) var(--space-4);
}

.app-sidebar__group + .app-sidebar__group {
  margin-top: var(--space-5);
}

.app-sidebar__group-title {
  margin: 0 0 var(--space-2) var(--space-2);
  font-size: var(--font-size-xs);
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-neutral-500);
}

.app-sidebar__link {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  color: var(--color-neutral-300);
  text-decoration: none;
  font-size: var(--font-size-base);
  transition: background var(--transition-fast), color var(--transition-fast);
}

.app-sidebar__link:hover {
  background: var(--color-neutral-800);
  color: var(--color-neutral-0);
}

.app-sidebar__link.is-active {
  background: var(--color-brand-600);
  color: var(--color-neutral-0);
  font-weight: var(--font-weight-medium);
}

.app-sidebar__link-icon {
  width: 16px;
  text-align: center;
  font-size: var(--font-size-sm);
}

.app-sidebar__link-label {
  flex: 1;
}

.app-sidebar__footer {
  padding: var(--space-3) var(--space-4) var(--space-4);
  border-top: 1px solid var(--color-neutral-800);
  font-size: var(--font-size-xs);
}

.app-sidebar__footer-line {
  display: flex;
  justify-content: space-between;
  gap: var(--space-2);
  margin: 0 0 var(--space-1);
  color: var(--color-neutral-300);
}

.app-sidebar__footer-line .text-muted {
  color: var(--color-neutral-500);
}

.app-main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.app-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  height: var(--layout-header-height);
  flex: 0 0 var(--layout-header-height);
  padding: 0 var(--space-6);
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
}

.app-topbar__service {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.app-topbar__actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.app-topbar__hint {
  font-size: var(--font-size-xs);
}

.app-content {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: var(--space-6);
}

.app-content__inner {
  max-width: var(--layout-content-max-width);
  margin: 0 auto;
}
</style>
