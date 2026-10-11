<script setup lang="ts">
/**
 * 系统设置：数据目录/日志目录、数据库信息、立即备份、备份列表、恢复（强确认 + 重启提示）、
 * 完整性检查、写入演示数据、日志查看。
 */
import { computed, onMounted, ref } from 'vue';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag
} from 'element-plus';

import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import type { BackupEntry } from '@/types/system';
import { useRuntimeStore } from '@/stores/runtime';
import { useSystemStore } from '@/stores/system';
import { confirmDestructive, toastError, toastInfo, toastSuccess } from '@/utils/confirm';
import { isDesktopHost, openPath, restartServices, showLogs } from '@/utils/desktop';
import { formatBytes, formatDateTime, formatDuration } from '@/utils/format';

const store = useSystemStore();
const runtime = useRuntimeStore();

const logVisible = ref(false);
const restartPromptVisible = ref(false);
const activeBackup = ref<BackupEntry | null>(null);

const info = computed(() => store.info);
const meta = computed(() => runtime.meta);

function openDirectory(target: string | undefined, label: string): void {
  if (!target) {
    toastError(`${label}路径未知。`);
    return;
  }
  if (!isDesktopHost()) {
    toastInfo(`浏览器调试环境无法打开本地路径，路径为：${target}`);
    return;
  }
  void openPath(target).then((result) => {
    if (!result.ok) toastError(result.error ?? `打开${label}失败。`);
  });
}

/** 在系统文件管理器中定位 `<UserData>/config/setting.json`（openPath 只接受数据目录及其子路径） */
function openConfigFile(): void {
  const target = store.configPath;
  if (!target) {
    toastError('配置文件路径未知，请先刷新系统信息。');
    return;
  }
  if (!isDesktopHost()) {
    toastInfo(`浏览器调试环境无法打开本地路径，配置文件为：${target}`);
    return;
  }
  void openPath(target).then((result) => {
    // 桌面壳未实现「选中文件」时退化为打开其所在目录
    if (result.ok) return;
    const parent = target.replace(/[\\/][^\\/]+$/, '');
    if (parent && parent !== target) {
      void openPath(parent).then((fallback) => {
        if (fallback.ok) toastInfo(`已打开配置文件所在目录：${parent}`);
        else toastError(fallback.error ?? '打开配置文件失败，请手动前往数据目录下的 config 子目录。');
      });
      return;
    }
    toastError(result.error ?? '打开配置文件失败。');
  });
}

async function backupNow(): Promise<void> {
  const created = await store.createBackup();
  if (created) {
    toastSuccess(`备份完成：${created.fileName}（${formatBytes(created.sizeBytes)}，完整性 ${created.integrityOk ? '通过' : '异常'}）`);
  } else if (store.backupError) {
    toastError(store.backupError.text);
  }
}

/** 恢复前对备份做只读校验（integrity_check + foreign_key_check + schema_version） */
async function verifyBackup(entry: BackupEntry): Promise<void> {
  const result = await store.verifyBackup(entry.fileName);
  if (result) {
    if (result.ok) toastSuccess(`校验通过：${entry.fileName}`);
    else toastError(`校验未通过：${entry.fileName}，请勿恢复该备份。`);
  } else if (store.verifyError) {
    toastError(store.verifyError.text);
  }
}

async function restore(entry: BackupEntry): Promise<void> {
  activeBackup.value = entry;
  const confirmed = await confirmDestructive(
    `即将用备份「${entry.fileName}」（${formatBytes(entry.sizeBytes)}，创建于 ${formatDateTime(entry.createdAt)}）覆盖当前数据库。\n` +
      '服务端会先校验备份（integrity_check + foreign_key_check + schema_version），随后写入 restore-request.json；' +
      '真正的替换发生在下次启动、打开连接池之前，替换前会自动备份当前库。\n' +
      '此操作会丢失备份时间点之后的所有数据。',
    '恢复',
    '恢复数据库（高风险）'
  );
  if (!confirmed) return;

  const ok = await store.restore(entry.fileName);
  if (ok) {
    restartPromptVisible.value = true;
    toastSuccess('恢复请求已登记，需要重启本机服务后生效');
  } else if (store.restoreError) {
    toastError(store.restoreError.text);
  }
}

async function doRestart(): Promise<void> {
  if (!isDesktopHost()) {
    toastInfo('浏览器调试环境无法重启服务，请手动重启 Java 服务进程。');
    restartPromptVisible.value = false;
    return;
  }
  const result = await restartServices();
  if (result.ok) {
    toastSuccess('已请求重启本机服务');
  } else {
    toastError('重启服务失败，请手动重启。');
  }
  restartPromptVisible.value = false;
}

async function integrityCheck(): Promise<void> {
  const result = await store.runIntegrityCheck();
  if (result) {
    if (result.ok) toastSuccess('完整性检查通过');
    else toastError('完整性检查发现问题，请查看下方结果详情');
  } else if (store.integrityError) {
    toastError(store.integrityError.text);
  }
}

async function seedDemo(): Promise<void> {
  const ok = await store.seedDemo();
  if (ok) toastSuccess(store.seedMessage ?? '演示数据已写入');
  else toastError(store.seedMessage ?? '写入演示数据失败');
}

async function openLogs(): Promise<void> {
  logVisible.value = true;
  await store.loadLogFiles();
  const first = store.logFiles[0];
  if (first) await store.loadLogTail(first.fileName);
}

function refresh(): void {
  void store.load();
  void runtime.probeHealth();
}

onMounted(() => {
  void store.load();
});
</script>

<template>
  <div class="system">
    <PageHeader
      title="系统设置"
      description="查看数据目录与数据库信息、执行备份与恢复、检查完整性并查看脱敏日志。"
    >
      <template #actions>
        <ElButton @click="showLogs()">打开日志目录</ElButton>
        <ElButton :loading="store.loading" @click="refresh">刷新</ElButton>
        <ElButton type="primary" :loading="store.backupRunning" @click="backupNow">立即备份</ElButton>
      </template>
    </PageHeader>

    <StatePanel
      :state="store.loading && !info ? 'loading' : store.error ? 'error' : 'ready'"
      :description="store.error?.message ?? ''"
      :code="store.error?.code ?? null"
      :request-id="store.error?.requestId ?? null"
      :retryable="store.error?.retryable ?? false"
      :skeleton-rows="6"
      @retry="store.load"
    >
      <section class="panel">
        <h2 class="system__section-title">数据与数据库</h2>
        <ElDescriptions :column="2" border size="small">
          <ElDescriptionsItem label="数据目录">
            <span class="mono">{{ info?.dataDir ?? runtime.context?.dataDir ?? '—' }}</span>
            <ElButton text size="small" type="primary" @click="openDirectory(info?.dataDir ?? runtime.context?.dataDir, '数据目录')">
              打开
            </ElButton>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="日志目录">
            <span class="mono">{{ info?.logDir ?? runtime.context?.logDir ?? '—' }}</span>
            <ElButton text size="small" type="primary" @click="openDirectory(info?.logDir ?? runtime.context?.logDir, '日志目录')">
              打开
            </ElButton>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="数据库文件">
            <span class="mono">{{ info?.databasePath ?? '—' }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="数据库大小">{{ formatBytes(info?.databaseSizeBytes) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="schemaVersion">{{ info?.schemaVersion ?? runtime.javaHealth?.schemaVersion ?? '—' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="FTS5 可用">
            <ElTag size="small" :type="meta?.fts5Available ? 'success' : 'warning'">
              {{ meta?.fts5Available ? '可用' : '不可用（降级为关键词检索）' }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="应用版本">{{ info?.appVersion ?? runtime.appVersion }}</ElDescriptionsItem>
          <ElDescriptionsItem label="运行时长">{{ formatDuration(info?.uptimeSeconds) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="备份数量">{{ info?.backupCount ?? store.backups.length }}</ElDescriptionsItem>
          <ElDescriptionsItem label="契约版本">{{ meta?.contractVersion ?? '—' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="配置来源（configSource）">
            <ElTag size="small" type="info">{{ store.configSource || '—' }}</ElTag>
            <span class="system__hint">
              LLM 配置与用户偏好的唯一权威是 setting.json（不再写入 SQLite，也不再使用 OS 凭据存储）。
            </span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="配置文件（configPath）">
            <span class="mono">{{ store.configPath || '—' }}</span>
            <ElButton
              text
              size="small"
              type="primary"
              :disabled="!store.configPath"
              @click="openConfigFile"
            >
              在文件管理器中定位
            </ElButton>
            <span class="system__hint">
              导出/导入/重新加载配置都以该文件为基准；修改后可在 LLM 设置页点击「重新加载配置」立即生效。
            </span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="Java 服务">
            <ElTag size="small" :type="runtime.javaState === 'up' ? 'success' : 'danger'">
              {{ runtime.javaState === 'up' ? '正常' : runtime.javaState === 'down' ? '不可用' : '未知' }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="AI 服务">
            <ElTag size="small" :type="info?.aiService.available ? 'success' : 'warning'">
              {{ info?.aiService.available ? '可用' : '不可用' }}
            </ElTag>
            <span class="system__hint">
              {{ info?.aiService.message || info?.aiService.baseUrl || '' }}
            </span>
          </ElDescriptionsItem>
        </ElDescriptions>
      </section>

      <section class="panel">
        <div class="system__section-head">
          <h2 class="system__section-title">备份与恢复</h2>
          <div class="toolbar">
            <ElButton size="small" :loading="store.backupRunning" @click="backupNow">立即备份</ElButton>
            <ElButton size="small" @click="store.loadBackups()">刷新列表</ElButton>
          </div>
        </div>

        <ElAlert
          v-if="store.lastBackup"
          class="system__banner"
          type="success"
          :closable="false"
          title="最近一次备份"
          :description="`${store.lastBackup.fileName} · ${formatBytes(store.lastBackup.sizeBytes)} · 校验和 ${(store.lastBackup.checksum ?? '—').slice(0, 16)}… · ${formatDateTime(store.lastBackup.createdAt)}`"
        />

        <ElAlert
          v-if="store.verifyResult"
          class="system__banner"
          :type="store.verifyResult.ok ? 'success' : 'error'"
          :closable="false"
          :title="`备份校验：${store.verifiedFileName ?? ''}`"
          :description="`${store.verifyResult.ok ? '校验通过，可安全恢复' : '校验未通过，请勿恢复'}；integrity_check：${store.verifyResult.integrityCheck}；foreign_key_check：${store.verifyResult.foreignKeyCheck}；schemaVersion：${store.verifyResult.schemaVersion}；大小：${formatBytes(store.verifyResult.databaseSizeBytes)}`"
        />
        <ElAlert
          v-else-if="store.verifyError"
          class="system__banner"
          type="error"
          :closable="false"
          title="备份校验失败"
          :description="store.verifyError.text"
        />

        <StatePanel
          :state="store.backups.length === 0 ? 'empty' : 'ready'"
          title="还没有备份"
          description="点击「立即备份」通过 VACUUM INTO 生成一致性快照到 <data>/backups/。"
          action-text="立即备份"
          @action="backupNow"
        >
          <ElTable :data="store.backups" size="small">
            <ElTableColumn label="文件名" min-width="240" prop="fileName" />
            <ElTableColumn label="大小" width="110">
              <template #default="{ row }">{{ formatBytes(Number(row.sizeBytes)) }}</template>
            </ElTableColumn>
            <ElTableColumn label="创建时间" width="180">
              <template #default="{ row }">{{ formatDateTime(String(row.createdAt)) }}</template>
            </ElTableColumn>
            <ElTableColumn label="校验状态" width="110">
              <template #default="{ row }">
                <ElTag size="small" :type="row.integrityOk ? 'success' : 'danger'">
                  {{ row.integrityOk ? '通过' : '异常' }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="200">
              <template #default="{ row }">
                <ElButton
                  size="small"
                  text
                  type="primary"
                  :loading="store.verifying && store.verifiedFileName === String(row.fileName)"
                  @click="verifyBackup(row as BackupEntry)"
                >
                  校验
                </ElButton>
                <ElButton size="small" text type="danger" @click="restore(row as BackupEntry)">恢复</ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
        </StatePanel>
      </section>

      <section class="panel">
        <div class="system__section-head">
          <h2 class="system__section-title">诊断与维护</h2>
          <div class="toolbar">
            <ElButton size="small" :loading="store.integrityRunning" @click="integrityCheck">完整性检查</ElButton>
            <ElButton size="small" :loading="store.seeding" @click="seedDemo">写入演示数据</ElButton>
            <ElButton size="small" @click="openLogs">查看日志</ElButton>
          </div>
        </div>

        <ElAlert
          v-if="store.integrity"
          class="system__banner"
          :type="store.integrity.ok ? 'success' : 'error'"
          :closable="false"
          :title="store.integrity.ok ? '完整性检查通过' : '完整性检查发现问题'"
          :description="`integrity_check：${store.integrity.integrityCheck}；foreign_key_check：${store.integrity.foreignKeyCheck}；schemaVersion：${store.integrity.schemaVersion}`"
        />
        <ElAlert
          v-else-if="store.integrityError"
          class="system__banner"
          type="error"
          :closable="false"
          title="完整性检查失败"
          :description="store.integrityError.text"
        />

        <ElAlert
          v-if="store.seedMessage"
          class="system__banner"
          type="info"
          :closable="false"
          title="演示数据"
          :description="store.seedMessage"
        />

        <p class="system__hint">
          演示数据为幂等写入（6 题 + 1 张已发布试卷），已存在时后端会跳过，不会重复写入。
        </p>
      </section>

      <ElCard v-if="!isDesktopHost()" shadow="never" class="system__card">
        <template #header><span>浏览器调试模式</span></template>
        <p class="system__hint">
          当前运行在浏览器中，<code>window.app</code> 不可用：打开目录、打开日志、重启服务等桌面能力已降级为提示。
          在 Electron 桌面壳中这些操作会调用 preload 白名单中的 <code>openPath</code>、<code>showLogs</code>、
          <code>restartServices</code>。
        </p>
      </ElCard>
    </StatePanel>

    <!-- 日志查看对话框 -->
    <ElDialog v-model="logVisible" title="日志查看（尾部 500 行，已脱敏）" width="900px" top="5vh">
      <div class="toolbar system__log-toolbar">
        <ElSelect
          :model-value="store.activeLogFile"
          placeholder="选择日志文件"
          class="system__log-select"
          @update:model-value="(value: string) => store.loadLogTail(value)"
        >
          <ElOption
            v-for="file in store.logFiles"
            :key="file.fileName"
            :label="`${file.fileName}（${formatBytes(file.sizeBytes)}，${formatDateTime(file.modifiedAt)}）`"
            :value="file.fileName"
          />
        </ElSelect>
        <ElButton size="small" @click="store.loadLogFiles()">刷新文件列表</ElButton>
        <ElButton
          v-if="store.activeLogFile"
          size="small"
          @click="store.loadLogTail(store.activeLogFile)"
        >
          重新载入
        </ElButton>
      </div>

      <StatePanel
        :state="store.logLoading ? 'loading' : store.logError ? 'error' : (store.logTail?.lines.length ?? 0) === 0 ? 'empty' : 'ready'"
        :description="store.logError?.message ?? '该日志文件没有内容。'"
        :code="store.logError?.code ?? null"
        :retryable="store.logError?.retryable ?? false"
        :skeleton-rows="5"
        @retry="store.activeLogFile && store.loadLogTail(store.activeLogFile)"
      >
        <pre class="log-view">{{ (store.logTail?.lines ?? []).join('\n') }}</pre>
        <p class="system__hint">接口固定返回尾部 500 行（脱敏后）。</p>
      </StatePanel>
    </ElDialog>

    <!-- 恢复后重启提示 -->
    <ElDialog v-model="restartPromptVisible" title="需要重启服务" width="560px">
      <ElAlert
        type="warning"
        :closable="false"
        title="恢复将在下次启动时执行"
        description="服务端已写入 restore-request.json。真正的数据库替换发生在下次启动、打开连接池之前，替换前会自动备份当前库。"
      />
      <p class="system__hint">
        已选择的备份：{{ activeBackup?.fileName ?? '—' }}。重启后当前连接会失效，页面需要重新获取运行时上下文。
      </p>
      <template #footer>
        <ElButton @click="restartPromptVisible = false">稍后手动重启</ElButton>
        <ElButton type="primary" @click="doRestart">立即重启服务</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.system__section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
  margin-bottom: var(--space-4);
}

.system__section-title {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-semibold);
}

.system__section-head .system__section-title {
  margin-bottom: 0;
}

.system__banner {
  margin-bottom: var(--space-3);
}

.system__hint {
  margin: var(--space-2) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}

.system__card {
  margin-top: var(--space-4);
}

.system__log-toolbar {
  margin-bottom: var(--space-3);
}

.system__log-select {
  width: 320px;
}

.panel + .panel {
  margin-top: var(--space-4);
}
</style>
