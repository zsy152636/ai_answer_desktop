<script setup lang="ts">
/** 关于：版本、技术栈、契约版本 */
import { computed } from 'vue';
import { ElCard, ElDescriptions, ElDescriptionsItem, ElTag } from 'element-plus';

import PageHeader from '@/components/PageHeader.vue';
import { useRuntimeStore } from '@/stores/runtime';

const runtime = useRuntimeStore();

const stack: { name: string; version: string; note: string }[] = [
  { name: 'Vue', version: '3.5.x', note: '组合式 API + <script setup>，严格 TypeScript' },
  { name: 'TypeScript', version: '5.9.x', note: 'strict / noUncheckedIndexedAccess，禁用 any' },
  { name: 'Vite', version: '5.4.x', note: '构建与 dev server，含 dev 期运行时 shim 插件' },
  { name: 'vue-router', version: '4.6.x', note: 'hash 模式，便于 Electron 以 file:// 加载' },
  { name: 'pinia', version: '2.3.x', note: 'runtime / dashboard / questions / papers / attempt / wrongQuestions / llmProfiles / system / documents' },
  { name: 'element-plus', version: '2.14.x', note: 'MIT 许可，中文界面（zh-cn locale）' },
  { name: 'HTTP', version: '原生 fetch', note: '未引入 axios；统一在 src/api/client.ts 处理包络与错误码' }
];

const contractVersion = 'v1（冻结版，docs/api/api-contract.md）';

const runtimeSource = computed(() => {
  if (!runtime.context) return '未解析';
  return runtime.context.source === 'preload' ? 'preload 注入（window.__APP_RUNTIME__）' : 'dev shim（GET /__app_runtime）';
});
</script>

<template>
  <div class="about">
    <PageHeader title="关于" description="版本信息、运行环境与技术栈。" />

    <section class="panel">
      <h2 class="about__section-title">应用信息</h2>
      <ElDescriptions :column="2" border size="small">
        <ElDescriptionsItem label="应用版本">{{ runtime.appVersion }}</ElDescriptionsItem>
        <ElDescriptionsItem label="渲染层版本">1.0.0（apps/renderer）</ElDescriptionsItem>
        <ElDescriptionsItem label="接口契约版本">{{ contractVersion }}</ElDescriptionsItem>
        <ElDescriptionsItem label="数据库 schemaVersion">
          {{ runtime.javaHealth?.schemaVersion ?? '—' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="运行形态">
          <ElTag size="small" :type="runtime.isDesktop ? 'success' : 'info'">
            {{ runtime.isDesktop ? 'Electron 桌面壳' : '浏览器开发调试' }}
          </ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="运行时来源">{{ runtimeSource }}</ElDescriptionsItem>
        <ElDescriptionsItem label="javaBaseUrl">
          <span class="mono">{{ runtime.javaBaseUrl || '—' }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="平台">{{ runtime.platform }}</ElDescriptionsItem>
        <ElDescriptionsItem label="数据目录" :span="2">
          <span class="mono">{{ runtime.context?.dataDir ?? '—' }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="日志目录" :span="2">
          <span class="mono">{{ runtime.context?.logDir ?? '—' }}</span>
        </ElDescriptionsItem>
      </ElDescriptions>

      <p class="about__hint">
        会话令牌（sessionToken）由 Java 服务启动时随机生成，仅在该进程生命周期内有效，退出即失效。
        渲染层不在任何位置持久化该令牌。
      </p>
    </section>

    <section class="panel about__stack">
      <h2 class="about__section-title">技术栈</h2>
      <div class="about__grid">
        <ElCard v-for="item in stack" :key="item.name" shadow="never">
          <template #header>
            <div class="about__card-head">
              <span>{{ item.name }}</span>
              <ElTag size="small" type="info">{{ item.version }}</ElTag>
            </div>
          </template>
          <p class="about__card-text">{{ item.note }}</p>
        </ElCard>
      </div>
    </section>

    <section class="panel">
      <h2 class="about__section-title">页面清单（开发文档 §11 对应关系）</h2>
      <p class="about__note">
        资料库（<code>/documents</code>）按契约 §11 实现列表、导入、检索测试、索引状态与重建、详情与分块核对；
        重复导入走「409 → <code>GET /documents?sha256=</code> 取类型化列表 → 复用 / 新建 / 取消」。
        接口已在隔离数据目录的真实 Java 服务上实测（见 apps/renderer/README.md §8.2）。
      </p>
      <ul class="about__list">
        <li><code>/</code> 仪表盘 —— 继续答题、最近作答、待复习错题、题库/试卷统计、服务状态徽标</li>
        <li><code>/questions</code> 题库列表 —— 搜索、筛选、分页、新建/编辑/发布/归档/删除</li>
        <li><code>/questions/new</code>、<code>/questions/:id/edit</code> 题目编辑 —— 版本历史、未保存提示、乐观锁</li>
        <li><code>/papers</code>、<code>/papers/new</code>、<code>/papers/:id/edit</code> 试卷列表与编辑 —— 选题、排序、分值、预览、发布</li>
        <li><code>/attempts</code> 作答记录、<code>/attempts/:id</code> 答题页、<code>/attempts/:id/result</code> 结果页</li>
        <li><code>/wrong-questions</code> 错题本 —— 筛选、去练习、复习结果、掌握/归档、版本差异、复习时间线</li>
        <li><code>/settings/llm</code> LLM 设置、<code>/settings/system</code> 系统设置</li>
        <li><code>/documents</code> 资料库 —— 列表与筛选、导入（重复文件「复用 / 新建 / 取消」）、检索测试与来源核对、索引状态与重建、删除</li>
        <li><code>/about</code> 关于</li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.about__section-title {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-semibold);
}

.about__hint {
  margin: var(--space-4) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}

.about__note {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}

.about__stack {
  margin-top: var(--space-4);
}

.about__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: var(--space-3);
}

.about__card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.about__card-text {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.about__list {
  margin: 0;
  padding-left: var(--space-5);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-base);
}

.panel + .panel {
  margin-top: var(--space-4);
}
</style>
