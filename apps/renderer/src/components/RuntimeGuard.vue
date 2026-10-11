<script setup lang="ts">
/**
 * 运行时守卫：在应用挂载前解析 `window.__APP_RUNTIME__` / dev shim，
 * 失败时展示「本机服务未就绪」的明确错误态（而不是白屏）。
 */
import { onMounted } from 'vue';
import { ElAlert, ElButton, ElDescriptions, ElDescriptionsItem, ElTag } from 'element-plus';

import StatePanel from './StatePanel.vue';
import { useRuntimeStore } from '@/stores/runtime';

const runtime = useRuntimeStore();

onMounted(async () => {
  const ok = await runtime.ensureRuntime();
  if (ok) {
    runtime.subscribeServiceStatus();
    await runtime.probeHealth();
  }
});

async function handleRetry(): Promise<void> {
  await runtime.retry();
}
</script>

<template>
  <div class="runtime-guard">
    <StatePanel
      v-if="runtime.status === 'idle' || runtime.status === 'loading'"
      state="loading"
      title="正在连接本机服务…"
      :skeleton-rows="5"
    />

    <div v-else-if="runtime.status === 'error'" class="runtime-guard__error">
      <StatePanel
        state="error"
        title="本机服务未就绪"
        :description="runtime.error?.message ?? '无法获取运行时上下文。'"
        :code="runtime.error?.code ?? null"
        :request-id="runtime.error?.requestId ?? null"
        :retryable="runtime.error?.retryable ?? true"
        retry-text="重试"
        @retry="handleRetry"
      />

      <div class="runtime-guard__detail">
        <ElAlert type="info" :closable="false" title="如何解决">
          <ol class="runtime-guard__steps">
            <li>确认 Java 业务服务已启动，并已生成 <code>.runtime/java-endpoint.json</code>。</li>
            <li>
              浏览器调试时需同时运行 <code>pnpm dev</code>；dev shim 会读取
              <code>GET /__app_runtime</code> 提供 <code>javaBaseUrl</code> 与 <code>sessionToken</code>。
            </li>
            <li>
              如端点文件不在默认位置，请设置环境变量
              <code>AIA_ENDPOINT_FILE</code> 指向该文件后重新启动 dev server。
            </li>
            <li>桌面壳中运行时由 preload 注入；会话令牌仅在该 Java 进程生命周期内有效，重启服务后需重新获取。</li>
          </ol>
        </ElAlert>

        <ElDescriptions :column="1" border size="small" class="runtime-guard__meta">
          <ElDescriptionsItem label="运行环境">
            <ElTag size="small" :type="runtime.isDesktop ? 'success' : 'info'">
              {{ runtime.isDesktop ? 'Electron 桌面壳' : '浏览器调试' }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="运行时来源">
            {{ runtime.context?.source ?? '未解析' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="错误详情">
            {{ runtime.error?.text || '—' }}
          </ElDescriptionsItem>
        </ElDescriptions>

        <div class="runtime-guard__actions">
          <ElButton type="primary" @click="handleRetry">重新解析运行时</ElButton>
        </div>
      </div>
    </div>

    <slot v-else />
  </div>
</template>

<style scoped>
.runtime-guard {
  height: 100%;
}

.runtime-guard__error {
  max-width: 920px;
  margin: var(--space-10) auto;
  padding: 0 var(--space-4);
}

.runtime-guard__detail {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  margin-top: var(--space-4);
}

.runtime-guard__steps {
  margin: 0;
  padding-left: var(--space-5);
  line-height: var(--line-height-base);
}

.runtime-guard__meta {
  background: var(--color-surface);
  border-radius: var(--radius-md);
}

.runtime-guard__actions {
  display: flex;
  justify-content: center;
}
</style>
