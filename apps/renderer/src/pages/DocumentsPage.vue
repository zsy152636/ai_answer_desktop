<script setup lang="ts">
/**
 * 资料库（契约 §11）：资料列表与筛选、文本资料导入（含重复文件「复用 / 新建 / 取消」三选一）、
 * 检索测试与「查看来源」回到分块预览、索引状态与重建、详情 + 分块预览、删除。
 *
 * 导入走桌面壳 `window.app.selectImportFile()`（契约 §10）；浏览器环境降级为提示，不发起导入请求。
 * `snippet` 里的 `[[命中]]` 一律按纯文本插值渲染（不使用 v-html），只给命中片段加高亮类。
 */
import { computed, onMounted, ref } from 'vue';
import {
  ElAlert,
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag
} from 'element-plus';

import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import type {
  DocumentImportPayload,
  DocumentSummary,
  SearchHit
} from '@/types/document';
import { DOCUMENT_STATUSES } from '@/types/document';
import { useDocumentsStore } from '@/stores/documents';
import {
  confirmAction,
  confirmDestructive,
  toastError,
  toastInfo,
  toastSuccess,
  toastWarning
} from '@/utils/confirm';
import { isDesktopHost, selectImportFile } from '@/utils/desktop';
import type { SelectedImportFile } from '@/utils/desktop';
import { describeError, type UiErrorInfo } from '@/utils/error';
import { formatBytes, formatDateTime, trimNumber, truncate } from '@/utils/format';
import {
  DOCUMENT_SOURCE_KIND_LABELS,
  DOCUMENT_STATUS_LABELS,
  DOCUMENT_STATUS_TAG,
  SEARCH_MATCH_MODE_LABELS,
  labelFrom,
  tagFrom
} from '@/utils/labels';

const store = useDocumentsStore();
/** 桌面壳能力（文件选择 + SHA-256）只在 Electron 中可用 */
const desktopHost = isDesktopHost();

const statusOptions = DOCUMENT_STATUSES.map((value) => ({
  value,
  label: DOCUMENT_STATUS_LABELS[value]
}));

const actingId = ref<string | null>(null);

// ---- 详情 / 分块预览 ----
const detailVisible = ref(false);

// ---- 导入 ----
const importVisible = ref(false);
const picking = ref(false);
const pickedFile = ref<SelectedImportFile | null>(null);
const importTitle = ref('');
const importErrorText = ref<string | null>(null);
/** 由失败行的「重试导入」进入时记住来源资料：SHA-256 一致则直接按「新建一份」导入 */
const retryTarget = ref<DocumentSummary | null>(null);
const duplicateVisible = ref(false);
/** 首次导入（reject）的请求体，供「新建一份」复用 */
const pendingPayload = ref<DocumentImportPayload | null>(null);

const pickedName = computed(() => {
  const picked = pickedFile.value;
  if (!picked) return '';
  if (picked.name) return picked.name;
  return picked.filePath ? baseName(picked.filePath) : '';
});

const searchDescription = computed(() => {
  if (store.searchError) return store.searchError.message;
  if (store.searchEmpty) {
    return '没有命中的分块。可尝试更长的关键词（少于 3 个有效字符会走关键词降级），或确认资料已完成索引。';
  }
  return '';
});

function baseName(path: string): string {
  const segments = path.split(/[\\/]/);
  return segments[segments.length - 1] ?? path;
}

/** 索引版本展示：`indexVersion` 在 failed 资料上为 null（Java 用 Integer 承载） */
function versionLabel(value: number | null | undefined): string {
  return typeof value === 'number' ? `v${value}` : '—';
}

/** 默认标题 = 原始文件名去掉扩展名 */
function defaultTitle(name: string): string {
  const bare = name.replace(/\.[^.]+$/, '').trim();
  return bare.length > 0 ? bare : name;
}

/** 契约 §0.3 资料类错误码的区分文案 */
function importErrorHint(info: UiErrorInfo): string {
  if (info.code === 'DOCUMENT_FORMAT_UNSUPPORTED') {
    return `格式不支持：首期只接受 .txt / .md 文本资料，PDF / DOCX 文本提取属阶段 7。请更换文件，重复重试不会成功。${info.text}`;
  }
  if (info.code === 'DOCUMENT_TOO_LARGE') {
    return `超过导入体积上限：请拆分或压缩后重试，服务端不会自动重试。${info.text}`;
  }
  if (info.code === 'DOCUMENT_PARSE_FAILED') {
    return `解析失败：文件读取或解码失败（原文文件已保留），可改用 UTF-8 重新保存后重试。${info.text}`;
  }
  return info.text;
}

// ---- 列表 ----
function openImport(): void {
  retryTarget.value = null;
  pickedFile.value = null;
  importTitle.value = '';
  importErrorText.value = null;
  pendingPayload.value = null;
  store.clearImportResult();
  importVisible.value = true;
}

function retryImport(row: DocumentSummary): void {
  retryTarget.value = row;
  pickedFile.value = null;
  importTitle.value = row.title;
  importErrorText.value = null;
  pendingPayload.value = null;
  store.clearImportResult();
  importVisible.value = true;
  toastInfo('请重新选择同一个文件：SHA-256 一致时将以「新建一份」导入，旧的失败记录可在列表中删除。');
}

async function pickFile(): Promise<void> {
  if (!desktopHost) {
    toastInfo('浏览器调试环境无法选择本地文件，请在 Electron 桌面壳中导入资料。');
    return;
  }
  picking.value = true;
  importErrorText.value = null;
  try {
    const picked = await selectImportFile();
    if (picked.canceled) return;
    if (!picked.filePath || !picked.sha256) {
      importErrorText.value = '桌面壳未返回文件路径或 SHA-256，无法导入该文件。';
      return;
    }
    pickedFile.value = picked;
    const name = picked.name ?? baseName(picked.filePath);
    importTitle.value = defaultTitle(name);
    toastInfo(`已选择文件：${name}`);
  } catch (caught) {
    importErrorText.value = describeError(caught).text;
  } finally {
    picking.value = false;
  }
}

function onImported(document: DocumentSummary): void {
  toastSuccess(
    `导入成功：${document.title}（${document.chunkCount} 个分块，索引版本 ${versionLabel(document.indexVersion)}）`
  );
  const id = document.id;
  importVisible.value = false;
  duplicateVisible.value = false;
  pickedFile.value = null;
  retryTarget.value = null;
  importTitle.value = '';
  pendingPayload.value = null;
  void openDetail(id);
}

async function submitImport(): Promise<void> {
  const picked = pickedFile.value;
  if (!picked || !picked.filePath || !picked.sha256) {
    importErrorText.value = '请先选择要导入的文件。';
    return;
  }
  // 失败行重试且文件一致时，直接按「新建一份」导入（旧失败记录保留，可在列表删除）
  const isRetryOfSameFile =
    retryTarget.value !== null && retryTarget.value.sha256 === picked.sha256;
  const payload: DocumentImportPayload = {
    sourcePath: picked.filePath,
    sha256: picked.sha256,
    onDuplicate: isRetryOfSameFile ? 'new' : 'reject'
  };
  const title = importTitle.value.trim();
  if (title.length > 0) payload.title = title;

  pendingPayload.value = payload;
  importErrorText.value = null;
  store.clearImportResult();
  const outcome = await store.importDocument(payload);
  if (outcome.kind === 'created') {
    onImported(outcome.document);
    return;
  }
  if (outcome.kind === 'duplicate') {
    duplicateVisible.value = true;
    return;
  }
  importErrorText.value = importErrorHint(outcome.error);
  toastError(outcome.error.text);
}

/**
 * 重复文件分支二：新建一份。
 * 再次 `POST /documents/import`，带 `onDuplicate: 'new'`（服务端复用同一份磁盘文件，只新增资料记录）。
 */
async function importAsNew(): Promise<void> {
  const payload = pendingPayload.value;
  if (!payload) {
    duplicateVisible.value = false;
    return;
  }
  duplicateVisible.value = false;
  importErrorText.value = null;
  const outcome = await store.importDocument({ ...payload, onDuplicate: 'new' });
  if (outcome.kind === 'created') {
    onImported(outcome.document);
    return;
  }
  if (outcome.kind === 'duplicate') {
    duplicateVisible.value = true;
    return;
  }
  importErrorText.value = importErrorHint(outcome.error);
  toastError(outcome.error.text);
}

/**
 * 重复文件分支一：复用已有资料。
 * documentId 一律来自 `GET /documents?sha256=` 的 typed 列表（store.duplicateDocuments），
 * 之后再 `getDocument(id)` 打开详情核对；**不再发第二次 import**。
 */
async function reuseDuplicate(document: DocumentSummary): Promise<void> {
  duplicateVisible.value = false;
  importVisible.value = false;
  pickedFile.value = null;
  pendingPayload.value = null;
  retryTarget.value = null;
  toastInfo(`已复用已有资料「${document.title}」（${document.id}），未重复导入。`);
  await openDetail(document.id);
}

/** 重复文件分支三：取消（仅关闭对话框，不改变任何状态） */
function cancelDuplicate(): void {
  duplicateVisible.value = false;
}

/** 409 之后重新取回 typed 列表（例如列表拉取失败后重试） */
function reloadDuplicates(): void {
  const sha256 = store.duplicateSha256;
  if (!sha256) return;
  void store.loadDuplicates(sha256);
}

// ---- 检索测试 ----
async function runSearch(): Promise<void> {
  const query = store.searchQuery.trim();
  if (query.length === 0) {
    toastWarning('请输入检索关键词。');
    return;
  }
  if (query.length > 200) {
    toastWarning('检索关键词最多 200 个字符。');
    return;
  }
  await store.runSearch();
}

async function openChunkSource(hit: SearchHit): Promise<void> {
  await openDetail(hit.documentId, hit.chunkIndex);
}

interface SnippetPart {
  text: string;
  hit: boolean;
}

const SNIPPET_OPEN = '[[';
const SNIPPET_CLOSE = ']]';

/**
 * 把契约 §11.1 的 `[[命中]]` 标记切成纯文本片段：模板只用插值渲染（`{{ }}` 自动转义），
 * 命中片段仅加高亮类，绝不把 snippet 当 HTML 注入。
 */
function snippetParts(snippet: string): SnippetPart[] {
  const parts: SnippetPart[] = [];
  let rest = snippet;
  for (;;) {
    const open = rest.indexOf(SNIPPET_OPEN);
    if (open < 0) {
      if (rest.length > 0) parts.push({ text: rest, hit: false });
      return parts;
    }
    if (open > 0) parts.push({ text: rest.slice(0, open), hit: false });
    const afterOpen = rest.slice(open + SNIPPET_OPEN.length);
    const close = afterOpen.indexOf(SNIPPET_CLOSE);
    if (close < 0) {
      // 标记不成对时按纯文本渲染
      parts.push({ text: afterOpen, hit: false });
      return parts;
    }
    parts.push({ text: afterOpen.slice(0, close), hit: true });
    rest = afterOpen.slice(close + SNIPPET_CLOSE.length);
  }
}

// ---- 索引状态与重建 ----
async function reindex(): Promise<void> {
  const confirmed = await confirmAction(
    '将建立新的 indexVersion 并全量重建分块索引，完成后切换 activeVersion。' +
      '重建期间旧版本继续对外服务；任一步失败会保留旧版本并返回 DOCUMENT_INDEX_FAILED。确认重建？',
    { title: '重建索引', confirmText: '重建' }
  );
  if (!confirmed) return;
  const ok = await store.reindex();
  if (ok) {
    toastSuccess(`索引已重建：activeVersion → ${store.indexState?.activeVersion ?? '—'}`);
    return;
  }
  if (store.reindexError) toastError(`${store.reindexError.text} 旧索引版本仍然可用。`);
}

// ---- 详情与删除 ----
async function openDetail(id: string, chunkIndex?: number): Promise<void> {
  detailVisible.value = true;
  const options = chunkIndex === undefined ? {} : { chunkIndex };
  await store.openDetail(id, options);
}

function retryDetail(): void {
  const id = store.activeDocumentId;
  if (!id) return;
  void store.openDetail(id, { chunkIndex: store.focusChunkIndex ?? undefined });
}

async function removeDocument(row: DocumentSummary): Promise<void> {
  const confirmed = await confirmDestructive(
    `将删除资料「${row.title}」及其全部分块与索引行。若同一 sha256 的文件已无其它资料引用，磁盘文件会一并删除。\n` +
      '删除后该资料立即从检索结果中消失，引用它的 AI 解释也无法再核对来源。此操作不可撤销。',
    '删除',
    '删除资料'
  );
  if (!confirmed) return;
  actingId.value = row.id;
  const ok = await store.deleteDocument(row.id);
  actingId.value = null;
  if (ok) {
    toastSuccess('删除成功');
    detailVisible.value = false;
    return;
  }
  if (store.deleteError) toastError(store.deleteError.text);
}

function removeCurrent(): void {
  const current = store.detail;
  if (!current) return;
  void removeDocument(current);
}

onMounted(() => {
  void store.fetchList();
  void store.loadIndexState();
});
</script>

<template>
  <div class="documents">
    <PageHeader
      title="资料库"
      description="导入本机文本资料并建立本地检索：支持列表与筛选、检索测试、索引状态与重建、引用来源逐条核对。"
    >
      <template #actions>
        <ElButton :loading="store.indexLoading" @click="store.loadIndexState()">刷新索引状态</ElButton>
        <ElButton type="primary" @click="openImport">导入资料</ElButton>
      </template>
    </PageHeader>

    <ElAlert
      v-if="!desktopHost"
      class="documents__banner"
      type="info"
      :closable="false"
      title="浏览器调试模式：资料导入已降级"
      description="文件选择与 SHA-256 计算依赖桌面壳 preload 白名单的 window.app.selectImportFile()（契约 §10），浏览器环境会返回 canceled。列表、检索测试、索引状态与重建仍可正常使用；导入请在 Electron 桌面壳中进行。"
    />

    <!-- 索引状态与重建 -->
    <section class="panel">
      <div class="documents__section-head">
        <h2 class="documents__section-title">索引状态</h2>
        <div class="toolbar">
          <ElButton size="small" :loading="store.indexLoading" @click="store.loadIndexState()">刷新状态</ElButton>
          <ElButton size="small" type="primary" :loading="store.reindexing" @click="reindex">重建索引</ElButton>
        </div>
      </div>

      <ElAlert
        v-if="store.indexState && !store.indexState.fts5Available"
        class="documents__banner"
        type="warning"
        :closable="false"
        title="当前 SQLite 构建不支持 FTS5，已降级为关键词检索"
        description="检索会自动走 LIKE 关键词路径（matchMode = keyword），接口形状与排序规则不变，重建索引不会改变该降级。"
      />

      <ElAlert
        v-if="store.reindexError"
        class="documents__banner"
        type="error"
        :closable="false"
        title="索引重建失败（DOCUMENT_INDEX_FAILED）"
        :description="`${store.reindexError.text} 旧索引版本仍然可用，检索不受影响。`"
      />
      <ElAlert
        v-else-if="store.reindexResult"
        class="documents__banner"
        type="success"
        :closable="false"
        title="索引重建完成"
        :description="`activeVersion → ${store.reindexResult.indexState.activeVersion}；本次重建 ${store.reindexResult.chunks} 个分块；失败 ${store.reindexResult.failures.length} 项。`"
      />

      <StatePanel
        :state="store.indexLoading && !store.indexState ? 'loading' : store.indexError ? 'error' : 'ready'"
        :description="store.indexError?.message ?? ''"
        :code="store.indexError?.code ?? null"
        :request-id="store.indexError?.requestId ?? null"
        :retryable="store.indexError?.retryable ?? false"
        :skeleton-rows="3"
        @retry="store.loadIndexState()"
      >
        <ElDescriptions :column="3" border size="small">
          <ElDescriptionsItem label="索引名称">
            <span class="mono">{{ store.indexState?.indexName ?? '—' }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="activeVersion">{{ store.indexState?.activeVersion ?? '—' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="buildingVersion">{{ store.indexState?.buildingVersion ?? '—' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="FTS5">
            <ElTag size="small" :type="store.indexState?.fts5Available ? 'success' : 'warning'">
              {{ store.indexState?.fts5Available ? '可用' : '不可用（降级为关键词检索）' }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="资料数">{{ store.indexState?.documentCount ?? '—' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="分块数">{{ store.indexState?.chunkCount ?? '—' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="更新时间" :span="3">
            {{ formatDateTime(store.indexState?.updatedAt ?? null) }}
          </ElDescriptionsItem>
        </ElDescriptions>
      </StatePanel>
    </section>

    <!-- 检索测试 -->
    <section class="panel">
      <div class="documents__section-head">
        <h2 class="documents__section-title">检索测试</h2>
        <span class="text-muted">每条命中都可「查看来源」，回到该资料的分块预览逐字核对</span>
      </div>

      <div class="toolbar documents__search-toolbar">
        <ElInput
          v-model="store.searchQuery"
          class="documents__search-input"
          placeholder="输入关键词（1-200 字符），按回车检索"
          clearable
          @keyup.enter="runSearch"
          @clear="store.clearSearch()"
        />
        <ElInputNumber v-model="store.searchLimit" :min="1" :max="50" class="documents__limit" />
        <ElButton type="primary" :loading="store.searching" @click="runSearch">检索</ElButton>
        <ElButton :disabled="!store.searchResult && !store.searchError" @click="store.clearSearch()">清空</ElButton>
      </div>

      <p v-if="!store.searchResult && !store.searching && !store.searchError" class="documents__hint">
        尚未检索。<code>limit</code> 默认 10、最大 50；少于 3 个有效字符时会走关键词降级（matchMode = keyword）。
      </p>

      <StatePanel
        v-else
        :state="store.searching ? 'loading' : store.searchError ? 'error' : store.searchEmpty ? 'empty' : 'ready'"
        :description="searchDescription"
        :code="store.searchError?.code ?? null"
        :request-id="store.searchError?.requestId ?? null"
        :retryable="store.searchError?.retryable ?? false"
        :action-text="store.searchError ? '' : '清空检索'"
        :skeleton-rows="3"
        @retry="runSearch"
        @action="store.clearSearch()"
      >
        <div v-if="store.searchResult" class="documents__search-meta">
          <ElTag size="small" :type="store.searchResult.mode === 'fts5' ? 'success' : 'warning'">
            {{ labelFrom(SEARCH_MATCH_MODE_LABELS, store.searchResult.mode) }}
          </ElTag>
          <span class="text-muted">
            耗时 {{ store.searchResult.tookMs }} ms · 命中 {{ store.searchResult.total }} 条 · 查询词「{{
              store.searchResult.query
            }}」
          </span>
        </div>

        <ul class="documents__hits">
          <li v-for="hit in store.searchResult?.results ?? []" :key="hit.chunkId" class="documents__hit-item">
            <div class="documents__hit-head">
              <span class="documents__hit-title">{{ hit.title }}</span>
              <span class="text-muted">
                页码 {{ hit.pageNo ?? '—' }} · 分块 #{{ hit.chunkIndex }} · 相关度
                {{ trimNumber(hit.score) }} · {{ labelFrom(SEARCH_MATCH_MODE_LABELS, hit.matchMode) }}
              </span>
              <ElButton size="small" text type="primary" @click="openChunkSource(hit)">查看来源</ElButton>
            </div>
            <p class="documents__snippet">
              <span
                v-for="(part, index) in snippetParts(hit.snippet)"
                :key="index"
                :class="{ 'documents__snippet-hit': part.hit }"
              >{{ part.text }}</span>
            </p>
          </li>
        </ul>
      </StatePanel>
    </section>

    <!-- 资料列表 -->
    <section class="panel">
      <div class="documents__section-head">
        <h2 class="documents__section-title">资料列表</h2>
        <span class="text-muted">共 {{ store.total }} 份资料</span>
      </div>

      <div class="toolbar documents__filters">
        <ElInput
          v-model="store.filters.q"
          class="documents__search-input"
          placeholder="按标题搜索"
          clearable
          @keyup.enter="store.search()"
          @clear="store.search()"
        />
        <ElSelect v-model="store.filters.status" class="documents__select" placeholder="状态" clearable>
          <ElOption
            v-for="option in statusOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </ElSelect>
        <ElButton type="primary" :loading="store.loading" @click="store.search()">查询</ElButton>
        <ElButton :disabled="!store.hasActiveFilters" @click="store.resetFilters()">重置</ElButton>
      </div>

      <StatePanel
        :state="store.loading && store.items.length === 0 ? 'loading' : store.error ? 'error' : store.isEmpty ? 'empty' : 'ready'"
        :description="store.error?.message ?? (store.hasActiveFilters ? '没有符合条件的资料，可调整筛选条件后重试。' : '资料库还是空的。在桌面壳中选择 .txt / .md 文件导入第一份资料。')"
        :code="store.error?.code ?? null"
        :request-id="store.error?.requestId ?? null"
        :retryable="store.error?.retryable ?? false"
        :action-text="store.error ? '' : store.hasActiveFilters ? '重置筛选' : '导入资料'"
        @retry="store.fetchList()"
        @action="store.hasActiveFilters ? store.resetFilters() : openImport()"
      >
        <ElTable :data="store.items" size="default" row-key="id">
          <ElTableColumn label="标题" min-width="200">
            <template #default="{ row }">
              <button type="button" class="documents__link" @click="openDetail(String(row.id))">
                {{ row.title }}
              </button>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="150">
            <template #default="{ row }">
              <ElTag size="small" :type="tagFrom(DOCUMENT_STATUS_TAG, row.status)">
                {{ labelFrom(DOCUMENT_STATUS_LABELS, row.status) }}
              </ElTag>
              <ElTag
                v-if="row.status === 'failed' && row.errorCode"
                size="small"
                type="danger"
                effect="plain"
                class="documents__code"
              >
                {{ row.errorCode }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="来源类型" width="100">
            <template #default="{ row }">{{ labelFrom(DOCUMENT_SOURCE_KIND_LABELS, row.sourceKind) }}</template>
          </ElTableColumn>
          <ElTableColumn label="原始文件名" min-width="160">
            <template #default="{ row }">{{ String(row.originalFileName) }}</template>
          </ElTableColumn>
          <ElTableColumn label="大小" width="100">
            <template #default="{ row }">{{ formatBytes(Number(row.sizeBytes)) }}</template>
          </ElTableColumn>
          <ElTableColumn label="分块数" width="90">
            <template #default="{ row }">{{ String(row.chunkCount) }}</template>
          </ElTableColumn>
          <ElTableColumn label="索引版本" width="100">
            <template #default="{ row }">{{ versionLabel(row.indexVersion as number | null) }}</template>
          </ElTableColumn>
          <ElTableColumn label="创建时间" width="170">
            <template #default="{ row }">{{ formatDateTime(String(row.createdAt)) }}</template>
          </ElTableColumn>
          <ElTableColumn label="失败原因" min-width="220">
            <template #default="{ row }">
              <template v-if="row.status === 'failed'">
                <span class="text-danger">
                  {{ truncate(String(row.errorMessage ?? '（后端未返回 errorMessage）'), 80) }}
                </span>
                <ElButton size="small" text type="primary" @click="retryImport(row as DocumentSummary)">
                  重试导入
                </ElButton>
              </template>
              <span v-else class="text-muted">—</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <ElButton size="small" text type="primary" @click="openDetail(String(row.id))">详情</ElButton>
              <ElButton
                size="small"
                text
                type="danger"
                :loading="actingId === String(row.id)"
                @click="removeDocument(row as DocumentSummary)"
              >
                删除
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
      </StatePanel>

      <div v-if="store.total > 0" class="documents__pager">
        <ElPagination
          :current-page="store.page"
          :page-size="store.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="store.total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="store.changePage"
          @size-change="store.changeSize"
        />
      </div>
    </section>

    <!-- 导入对话框 -->
    <ElDialog v-model="importVisible" title="导入文本资料" width="640px">
      <ElAlert
        v-if="!desktopHost"
        class="documents__banner"
        type="warning"
        :closable="false"
        title="浏览器调试模式无法选择本地文件"
        description="文件选择与 SHA-256 计算依赖桌面壳 preload 白名单的 window.app.selectImportFile()（契约 §10）。请在 Electron 桌面壳中导入资料。"
      />

      <p class="documents__hint">
        首期只支持 <code>.txt</code> / <code>.md</code> 文本资料；PDF / DOCX 文本提取与 OCR 属阶段 7。
        服务端会重算 SHA-256 与桌面壳给出的值比对（防 TOCTOU），并按 sha256 去重。
      </p>

      <div class="toolbar documents__import-toolbar">
        <ElButton :disabled="!desktopHost" :loading="picking" @click="pickFile">选择文件</ElButton>
        <span v-if="pickedName" class="text-muted">
          {{ pickedName }} · {{ formatBytes(pickedFile?.sizeBytes ?? null) }}
        </span>
        <span v-else class="text-muted">尚未选择文件</span>
      </div>

      <div v-if="pickedFile" class="documents__picked">
        <p><span class="text-muted">文件路径</span> <code class="mono">{{ pickedFile.filePath ?? '—' }}</code></p>
        <p><span class="text-muted">SHA-256</span> <code class="mono">{{ pickedFile.sha256 ?? '—' }}</code></p>
      </div>

      <ElForm label-position="top">
        <ElFormItem label="标题（可选）">
          <ElInput v-model="importTitle" placeholder="留空则由服务端使用原始文件名；默认取文件名去掉扩展名" />
        </ElFormItem>
      </ElForm>

      <ElAlert
        v-if="importErrorText"
        class="documents__banner"
        type="error"
        :closable="false"
        title="导入失败"
        :description="importErrorText"
      />

      <template #footer>
        <ElButton @click="importVisible = false">关闭</ElButton>
        <ElButton
          type="primary"
          :disabled="!desktopHost || !pickedFile"
          :loading="store.importing"
          @click="submitImport"
        >
          导入
        </ElButton>
      </template>
    </ElDialog>

    <!-- 重复文件：复用 / 新建 / 取消（documentId 一律来自 GET /documents?sha256= 的 typed 列表） -->
    <ElDialog
      v-model="duplicateVisible"
      title="该文件已导入过（sha256 重复）"
      width="880px"
      append-to-body
    >
      <ElAlert
        class="documents__banner"
        type="warning"
        :closable="false"
        title="服务端按 sha256 去重并拒绝了本次导入（409 STATE_CONFLICT）"
        description="同一份文件只保留一份磁盘文件。已用 GET /documents?sha256= 取回引用该文件的资料列表，可复用已有资料、新建一份独立资料，或取消本次导入。"
      />

      <StatePanel
        :state="store.duplicateLoading ? 'loading' : store.duplicateError ? 'error' : store.duplicateDocuments.length === 0 ? 'empty' : 'ready'"
        :description="store.duplicateError?.message ?? '该 sha256 目前取不到可复用的资料记录（可能刚被删除）。可重新取回列表，或直接「新建一份」。'"
        :code="store.duplicateError?.code ?? null"
        :request-id="store.duplicateError?.requestId ?? null"
        :retryable="store.duplicateError?.retryable ?? false"
        :action-text="store.duplicateError ? '' : '重新取回列表'"
        :skeleton-rows="3"
        @retry="reloadDuplicates"
        @action="reloadDuplicates"
      >
        <ElTable :data="store.duplicateDocuments" size="small" row-key="id">
          <ElTableColumn label="标题" min-width="200" prop="title" />
          <ElTableColumn label="documentId" min-width="190">
            <template #default="{ row }"><code class="mono">{{ row.id }}</code></template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="110">
            <template #default="{ row }">
              <ElTag size="small" :type="tagFrom(DOCUMENT_STATUS_TAG, row.status)">
                {{ labelFrom(DOCUMENT_STATUS_LABELS, row.status) }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="分块数" width="90">
            <template #default="{ row }">{{ String(row.chunkCount) }}</template>
          </ElTableColumn>
          <ElTableColumn label="创建时间" width="170">
            <template #default="{ row }">{{ formatDateTime(String(row.createdAt)) }}</template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <ElButton size="small" text type="primary" @click="reuseDuplicate(row as DocumentSummary)">
                复用这份资料
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
      </StatePanel>

      <p v-if="store.duplicateHints.length > 0" class="documents__hint">
        服务端 <code>error.details</code> 里还带了 {{ store.duplicateHints.length }} 条 duplicates 提示（旧契约形状，
        仅作补充展示；「复用」一律以上表的 typed 列表为准）：
        <span v-for="hint in store.duplicateHints" :key="hint.documentId" class="documents__hint-item">
          {{ hint.title }}（{{ formatDateTime(hint.createdAt) }}）
        </span>
      </p>

      <template #footer>
        <ElButton @click="cancelDuplicate">取消</ElButton>
        <ElButton :loading="store.duplicateLoading" @click="reloadDuplicates">重新取回列表</ElButton>
        <ElButton type="primary" :loading="store.importing" @click="importAsNew">新建一份</ElButton>
      </template>
    </ElDialog>

    <!-- 详情与分块预览 -->
    <ElDialog
      v-model="detailVisible"
      title="资料详情与分块预览"
      width="920px"
      top="5vh"
      @closed="store.closeDetail()"
    >
      <StatePanel
        :state="store.detailLoading ? 'loading' : store.detailError ? 'error' : 'ready'"
        :description="store.detailError?.message ?? ''"
        :code="store.detailError?.code ?? null"
        :request-id="store.detailError?.requestId ?? null"
        :retryable="store.detailError?.retryable ?? false"
        :skeleton-rows="6"
        @retry="retryDetail"
      >
        <ElDescriptions :column="3" border size="small">
          <ElDescriptionsItem label="标题" :span="2">{{ store.detail?.title ?? '—' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="状态">
            <ElTag size="small" :type="tagFrom(DOCUMENT_STATUS_TAG, store.detail?.status)">
              {{ labelFrom(DOCUMENT_STATUS_LABELS, store.detail?.status) }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="来源类型">
            {{ labelFrom(DOCUMENT_SOURCE_KIND_LABELS, store.detail?.sourceKind) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="原始文件名">{{ store.detail?.originalFileName ?? '—' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="大小">{{ formatBytes(store.detail?.sizeBytes) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="分块数">{{ store.detail?.chunkCount ?? '—' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="字符数">{{ store.detail?.charCount ?? '—' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="索引版本">
            {{ store.detail ? versionLabel(store.detail.indexVersion) : '—' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="解析器版本">{{ store.detail?.parserVersion ?? '—' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="创建时间">{{ formatDateTime(store.detail?.createdAt) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="更新时间">{{ formatDateTime(store.detail?.updatedAt) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="资料 id">
            <span class="mono">{{ store.detail?.id ?? '—' }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="sha256" :span="3">
            <span class="mono">{{ store.detail?.sha256 ?? '—' }}</span>
          </ElDescriptionsItem>
        </ElDescriptions>

        <ElAlert
          v-if="store.detail?.status === 'failed'"
          class="documents__banner"
          type="error"
          :closable="false"
          :title="`导入失败：${store.detail?.errorCode ?? '未提供错误码'}`"
          :description="store.detail?.errorMessage ?? '（后端未返回 errorMessage）'"
        />

        <ElAlert
          v-if="store.deleteError"
          class="documents__banner"
          type="error"
          :closable="false"
          title="删除失败"
          :description="store.deleteError.text"
        />

        <div class="documents__section-head documents__chunks-head">
          <h3 class="documents__subtitle">分块预览</h3>
          <span v-if="store.focusChunkIndex !== null" class="text-muted">
            来自检索命中的分块 #{{ store.focusChunkIndex }}（已定位到所在页）
          </span>
          <ElButton size="small" :disabled="!store.activeDocumentId" @click="store.loadChunks()">
            刷新分块
          </ElButton>
        </div>

        <StatePanel
          :state="store.chunksLoading ? 'loading' : store.chunkError ? 'error' : store.chunkItems.length === 0 ? 'empty' : 'ready'"
          :description="store.chunkError?.message ?? '该资料没有可预览的分块（可能尚未完成解析，可尝试重试导入）。'"
          :code="store.chunkError?.code ?? null"
          :request-id="store.chunkError?.requestId ?? null"
          :retryable="store.chunkError?.retryable ?? false"
          :skeleton-rows="4"
          @retry="store.loadChunks()"
        >
          <ElTable :data="store.chunkItems" size="small" row-key="id" max-height="360">
            <ElTableColumn label="#" width="70">
              <template #default="{ row }">{{ String(row.chunkIndex) }}</template>
            </ElTableColumn>
            <ElTableColumn label="页码" width="80">
              <template #default="{ row }">{{ row.pageNo ?? '—' }}</template>
            </ElTableColumn>
            <ElTableColumn label="内容" min-width="380">
              <template #default="{ row }">
                <span class="documents__chunk-content">{{ String(row.content) }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="来源" width="110">
              <template #default="{ row }">
                <ElTag
                  v-if="store.focusChunkIndex !== null && Number(row.chunkIndex) === store.focusChunkIndex"
                  size="small"
                  type="success"
                >
                  命中来源
                </ElTag>
                <span v-else class="text-muted">—</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="contentHash" width="170">
              <template #default="{ row }">
                <span class="mono">{{ String(row.contentHash).slice(0, 16) }}…</span>
              </template>
            </ElTableColumn>
          </ElTable>

          <div v-if="store.chunkTotal > 0" class="documents__pager">
            <ElPagination
              :current-page="store.chunkPage"
              :page-size="store.chunkSize"
              :page-sizes="[10, 20, 50]"
              :total="store.chunkTotal"
              layout="total, sizes, prev, pager, next"
              background
              @current-change="store.changeChunkPage"
              @size-change="store.changeChunkSize"
            />
          </div>
        </StatePanel>
      </StatePanel>

      <template #footer>
        <ElButton type="danger" :disabled="!store.detail" :loading="store.deleting" @click="removeCurrent">
          删除资料
        </ElButton>
        <ElButton @click="detailVisible = false">关闭</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.documents__banner {
  margin-bottom: var(--space-3);
}

.documents__section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
  margin-bottom: var(--space-4);
}

.documents__section-title {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-semibold);
}

.documents__subtitle {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
}

.documents__chunks-head {
  margin-top: var(--space-5);
  margin-bottom: var(--space-3);
}

.documents__filters {
  margin-bottom: var(--space-4);
}

.documents__search-toolbar {
  margin-bottom: var(--space-4);
}

.documents__search-input {
  width: 320px;
}

.documents__limit {
  width: 130px;
}

.documents__select {
  width: 150px;
}

.documents__hint {
  margin: var(--space-2) 0 var(--space-3);
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}

.documents__hint-item {
  margin-right: var(--space-2);
}

.documents__search-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
  font-size: var(--font-size-sm);
}

.documents__hits {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.documents__hit-item {
  padding: var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-muted);
}

.documents__hit-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-bottom: var(--space-2);
  font-size: var(--font-size-sm);
}

.documents__hit-title {
  font-weight: var(--font-weight-medium);
}

.documents__snippet {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-base);
  word-break: break-word;
  white-space: pre-wrap;
}

.documents__snippet-hit {
  background: var(--color-warning-bg);
  color: var(--color-warning);
  font-weight: var(--font-weight-medium);
}

.documents__link {
  padding: 0;
  border: none;
  background: none;
  color: var(--color-brand-600);
  cursor: pointer;
  font-size: inherit;
  font-family: inherit;
  text-align: left;
}

.documents__link:hover {
  text-decoration: underline;
}

.documents__code {
  margin-left: var(--space-1);
}

.documents__import-toolbar {
  margin-bottom: var(--space-3);
}

.documents__picked {
  margin: var(--space-3) 0;
  font-size: var(--font-size-sm);
}

.documents__picked p {
  margin: 0 0 var(--space-1);
}

.documents__chunk-content {
  display: block;
  font-size: var(--font-size-sm);
  white-space: pre-wrap;
  word-break: break-word;
}

.documents__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-4);
}
</style>
