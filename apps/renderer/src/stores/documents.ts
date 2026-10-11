/**
 * 资料库 store（契约 §11）：列表筛选分页、导入（含重复文件三选一）、检索测试、
 * 索引状态与重建、详情与分块预览、删除。
 *
 * 状态分五组：列表（items/filters/page/size/total/loading/error/loaded）、
 * 导入（importing/importError/importResult/duplicateDocuments/duplicateHints/duplicateLoading/duplicateError）、
 * 检索（searching/searchError/searchResult）、
 * 索引（indexState/indexLoading/indexError/reindexing/reindexError/reindexResult）、
 * 详情与分块（detail / chunkItems / focusChunkIndex 等）。
 */
import { defineStore } from 'pinia';
import { computed, reactive, ref, shallowRef } from 'vue';

import { ApiError } from '@/api/client';
import * as documentsApi from '@/api/documents';
import type { PageResult } from '@/types/common';
import type {
  ChunkView,
  DocumentImportPayload,
  DocumentListQuery,
  DocumentStatus,
  DocumentSummary,
  DuplicateDocument,
  IndexState,
  ReindexResponse,
  SearchResponse
} from '@/types/document';
import { DOCUMENT_CHUNK_PAGE_SIZE } from '@/types/document';
import { describeError, type UiErrorInfo } from '@/utils/error';

interface DocumentFilters {
  q: string;
  status: DocumentStatus | '';
}

function createFilters(): DocumentFilters {
  return { q: '', status: '' };
}

/** 重复文件反查的分页大小（同一 sha256 的资料条数很少；契约 §0 允许 1-100） */
const DUPLICATE_LOOKUP_SIZE = 20;

/** 导入结果三态：成功 / 命中重复（页面弹三选一对话框）/ 失败 */
export type DocumentImportOutcome =
  | { kind: 'created'; document: DocumentSummary }
  | {
      kind: 'duplicate';
      /** 权威来源：`GET /documents?sha256=` 返回的 typed 列表（「复用」只用这里的 id） */
      documents: DocumentSummary[];
      /** 防御性兼容：details 里若真带了 duplicates，只作补充展示 */
      hints: DuplicateDocument[];
    }
  | { kind: 'failed'; error: UiErrorInfo };

/** 从任意 details 元素里尝试读出一个 `duplicates` 元素（仅作展示，不用于「复用」） */
function toDuplicate(value: unknown): DuplicateDocument | null {
  if (typeof value !== 'object' || value === null) return null;
  const source = value as Record<string, unknown>;
  const documentId = source.documentId ?? source.id;
  if (typeof documentId !== 'string' || documentId.length === 0) return null;
  return {
    documentId,
    title: typeof source.title === 'string' ? source.title : documentId,
    createdAt: typeof source.createdAt === 'string' ? source.createdAt : ''
  };
}

/**
 * **防御性兼容**：Java 实现的 409 `details` 只有 `[{field:"sourcePath", message}]`（契约 §0.2 形状固定），
 * 不会带结构化 duplicates；这里兼容旧契约形状（「一个元素含 duplicates 数组」或「若干元素各自是一个 duplicate」），
 * 结果只用于对话框里的补充信息，**不用它取 documentId**。
 */
function extractDuplicateHints(caught: unknown): DuplicateDocument[] {
  if (!(caught instanceof ApiError)) return [];
  if (caught.code !== 'STATE_CONFLICT') return [];
  const found: DuplicateDocument[] = [];
  for (const detail of caught.details) {
    const list = detail.duplicates;
    if (Array.isArray(list)) {
      for (const entry of list) {
        const parsed = toDuplicate(entry);
        if (parsed && !found.some((item) => item.documentId === parsed.documentId)) found.push(parsed);
      }
      continue;
    }
    const direct = toDuplicate(detail);
    if (direct && !found.some((item) => item.documentId === direct.documentId)) found.push(direct);
  }
  return found;
}

export const useDocumentsStore = defineStore('documents', () => {
  // ---- 列表 ----
  const items = shallowRef<DocumentSummary[]>([]);
  const filters = reactive<DocumentFilters>(createFilters());
  const page = ref(1);
  const size = ref(20);
  const total = ref(0);
  const totalPages = ref(0);
  const loading = ref(false);
  const error = ref<UiErrorInfo | null>(null);
  const loaded = ref(false);

  // ---- 导入 ----
  const importing = ref(false);
  const importError = ref<UiErrorInfo | null>(null);
  const importResult = shallowRef<DocumentSummary | null>(null);
  /**
   * 重复文件对话框的数据源（typed）：409 后由 `GET /documents?sha256=` 取回，
   * 「复用已有资料」只使用这里的 `DocumentSummary.id`。
   */
  const duplicateDocuments = shallowRef<DocumentSummary[]>([]);
  /** 防御性兼容：details 里的 duplicates，仅作补充展示 */
  const duplicateHints = shallowRef<DuplicateDocument[]>([]);
  const duplicateLoading = ref(false);
  const duplicateError = ref<UiErrorInfo | null>(null);
  /** 触发 409 的那次导入的 sha256，供对话框「重新取回列表」重查 */
  const duplicateSha256 = ref<string | null>(null);

  // ---- 检索测试 ----
  const searchQuery = ref('');
  const searchLimit = ref(10);
  const searching = ref(false);
  const searchError = ref<UiErrorInfo | null>(null);
  const searchResult = shallowRef<SearchResponse | null>(null);

  // ---- 索引状态与重建 ----
  const indexState = shallowRef<IndexState | null>(null);
  const indexLoading = ref(false);
  const indexError = ref<UiErrorInfo | null>(null);
  const reindexing = ref(false);
  const reindexError = ref<UiErrorInfo | null>(null);
  const reindexResult = shallowRef<ReindexResponse | null>(null);

  // ---- 详情 ----
  const activeDocumentId = ref<string | null>(null);
  const detail = shallowRef<DocumentSummary | null>(null);
  const detailLoading = ref(false);
  const detailError = ref<UiErrorInfo | null>(null);
  const deleting = ref(false);
  const deleteError = ref<UiErrorInfo | null>(null);

  // ---- 分块预览 ----
  const chunksLoading = ref(false);
  const chunkError = ref<UiErrorInfo | null>(null);
  const chunkItems = shallowRef<ChunkView[]>([]);
  const chunkPage = ref(1);
  const chunkSize = ref(DOCUMENT_CHUNK_PAGE_SIZE);
  const chunkTotal = ref(0);
  const chunkTotalPages = ref(0);
  /** 检索命中的分块序号：打开分块预览时定位到所在页并标记「命中来源」 */
  const focusChunkIndex = ref<number | null>(null);

  const isEmpty = computed(() => loaded.value && !loading.value && items.value.length === 0);
  const hasActiveFilters = computed(() => filters.q.trim() !== '' || Boolean(filters.status));
  const searchEmpty = computed(
    () =>
      !searching.value &&
      searchError.value === null &&
      searchResult.value !== null &&
      searchResult.value.results.length === 0
  );

  function buildQuery(): DocumentListQuery {
    const query: DocumentListQuery = { page: page.value, size: size.value };
    if (filters.q.trim()) query.q = filters.q.trim();
    if (filters.status) query.status = filters.status;
    return query;
  }

  async function fetchList(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const result: PageResult<DocumentSummary> = await documentsApi.listDocuments(buildQuery());
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

  /**
   * 按文件 SHA-256 取回引用该文件的资料（typed `DocumentSummary[]`）。
   * 这是重复导入时唯一可信的 documentId 来源：Java 的 409 `details` 只有 `{field, message}`。
   */
  async function loadDuplicates(sha256: string): Promise<void> {
    const value = sha256.trim();
    duplicateSha256.value = value;
    duplicateDocuments.value = [];
    duplicateError.value = null;
    if (value.length === 0) return;
    duplicateLoading.value = true;
    try {
      const result = await documentsApi.listDocuments({
        sha256: value,
        page: 1,
        size: DUPLICATE_LOOKUP_SIZE
      });
      duplicateDocuments.value = result.items;
    } catch (caught) {
      duplicateError.value = describeError(caught);
    } finally {
      duplicateLoading.value = false;
    }
  }

  /**
   * 导入一份文本资料（页面三条分支的依据）：
   *  - 成功 → `kind: 'created'`；
   *  - 409 `STATE_CONFLICT` → 先 `GET /documents?sha256=` 取 typed 列表，返回 `kind: 'duplicate'`，
   *    由页面弹「复用 / 新建 / 取消」（**复用不再发 import**）；
   *  - 其他失败 → `kind: 'failed'`。
   */
  async function importDocument(payload: DocumentImportPayload): Promise<DocumentImportOutcome> {
    importing.value = true;
    importError.value = null;
    importResult.value = null;
    duplicateDocuments.value = [];
    duplicateHints.value = [];
    duplicateError.value = null;
    duplicateSha256.value = null;
    try {
      const created = await documentsApi.importDocument(payload);
      importResult.value = created;
      await fetchList();
      await loadIndexState();
      return { kind: 'created', document: created };
    } catch (caught) {
      if (caught instanceof ApiError && caught.code === 'STATE_CONFLICT') {
        // 防御性兼容：旧契约形状的 details.duplicates（Java 不发），只作补充展示
        duplicateHints.value = extractDuplicateHints(caught);
        await loadDuplicates(payload.sha256);
        if (duplicateDocuments.value.length > 0 || duplicateHints.value.length > 0) {
          return {
            kind: 'duplicate',
            documents: duplicateDocuments.value,
            hints: duplicateHints.value
          };
        }
      }
      const info = describeError(caught);
      importError.value = info;
      return { kind: 'failed', error: info };
    } finally {
      importing.value = false;
    }
  }

  function clearImportResult(): void {
    importError.value = null;
    importResult.value = null;
    duplicateDocuments.value = [];
    duplicateHints.value = [];
    duplicateError.value = null;
    duplicateSha256.value = null;
  }

  /** 检索测试（`GET /api/v1/search`）；空关键词不发请求 */
  async function runSearch(): Promise<void> {
    const q = searchQuery.value.trim();
    if (q.length === 0) {
      searchError.value = null;
      searchResult.value = null;
      return;
    }
    searching.value = true;
    searchError.value = null;
    try {
      searchResult.value = await documentsApi.searchDocuments({ q, limit: searchLimit.value });
    } catch (caught) {
      searchError.value = describeError(caught);
      searchResult.value = null;
    } finally {
      searching.value = false;
    }
  }

  function clearSearch(): void {
    searchQuery.value = '';
    searchResult.value = null;
    searchError.value = null;
  }

  async function loadIndexState(): Promise<void> {
    indexLoading.value = true;
    indexError.value = null;
    try {
      indexState.value = await documentsApi.getIndexState();
    } catch (caught) {
      indexError.value = describeError(caught);
      indexState.value = null;
    } finally {
      indexLoading.value = false;
    }
  }

  /** 重建索引；返回是否成功（失败时 `reindexError` 有值，旧版本仍可用） */
  async function reindex(): Promise<boolean> {
    reindexing.value = true;
    reindexError.value = null;
    reindexResult.value = null;
    try {
      const result = await documentsApi.reindexDocuments();
      reindexResult.value = result;
      // 响应是 { indexState, chunks, failures }：indexState 为嵌套字段
      indexState.value = result.indexState;
      await fetchList();
      return true;
    } catch (caught) {
      reindexError.value = describeError(caught);
      return false;
    } finally {
      reindexing.value = false;
    }
  }

  /**
   * 打开详情；传入 `chunkIndex` 时按「分块序号升序分页」推算所在页并标记「命中来源」
   * （该页码推算依赖后端按 `chunkIndex` 升序返回，契约 §11.2 未显式规定排序）。
   */
  async function openDetail(id: string, options: { chunkIndex?: number } = {}): Promise<void> {
    activeDocumentId.value = id;
    focusChunkIndex.value = options.chunkIndex ?? null;
    detailLoading.value = true;
    detailError.value = null;
    deleteError.value = null;
    detail.value = null;
    chunkItems.value = [];
    chunkTotal.value = 0;
    chunkTotalPages.value = 0;
    chunkError.value = null;
    const pageSize = chunkSize.value;
    chunkPage.value =
      focusChunkIndex.value === null ? 1 : Math.floor(focusChunkIndex.value / pageSize) + 1;
    try {
      detail.value = await documentsApi.getDocument(id);
      await loadChunks();
    } catch (caught) {
      detailError.value = describeError(caught);
      detail.value = null;
      chunkItems.value = [];
      chunkTotal.value = 0;
      chunkTotalPages.value = 0;
    } finally {
      detailLoading.value = false;
    }
  }

  async function loadChunks(): Promise<void> {
    const id = activeDocumentId.value;
    if (!id) return;
    chunksLoading.value = true;
    chunkError.value = null;
    try {
      const result: PageResult<ChunkView> = await documentsApi.listDocumentChunks(id, {
        page: chunkPage.value,
        size: chunkSize.value
      });
      chunkItems.value = result.items;
      chunkTotal.value = result.total;
      chunkTotalPages.value = result.totalPages;
      chunkPage.value = result.page;
      chunkSize.value = result.size;
    } catch (caught) {
      chunkError.value = describeError(caught);
      chunkItems.value = [];
      chunkTotal.value = 0;
      chunkTotalPages.value = 0;
    } finally {
      chunksLoading.value = false;
    }
  }

  async function changeChunkPage(next: number): Promise<void> {
    chunkPage.value = next;
    await loadChunks();
  }

  async function changeChunkSize(next: number): Promise<void> {
    chunkSize.value = next;
    chunkPage.value = 1;
    await loadChunks();
  }

  function closeDetail(): void {
    activeDocumentId.value = null;
    detail.value = null;
    detailError.value = null;
    deleteError.value = null;
    focusChunkIndex.value = null;
    chunkItems.value = [];
    chunkError.value = null;
    chunkTotal.value = 0;
    chunkTotalPages.value = 0;
  }

  /** 删除资料；成功后刷新列表与索引状态，并关闭已删除资料的详情 */
  async function deleteDocument(id: string): Promise<boolean> {
    deleting.value = true;
    deleteError.value = null;
    try {
      await documentsApi.deleteDocument(id);
      await fetchList();
      await loadIndexState();
      if (activeDocumentId.value === id) closeDetail();
      return true;
    } catch (caught) {
      deleteError.value = describeError(caught);
      return false;
    } finally {
      deleting.value = false;
    }
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
    importing,
    importError,
    importResult,
    duplicateDocuments,
    duplicateHints,
    duplicateLoading,
    duplicateError,
    duplicateSha256,
    searchQuery,
    searchLimit,
    searching,
    searchError,
    searchResult,
    searchEmpty,
    indexState,
    indexLoading,
    indexError,
    reindexing,
    reindexError,
    reindexResult,
    activeDocumentId,
    detail,
    detailLoading,
    detailError,
    deleting,
    deleteError,
    chunksLoading,
    chunkError,
    chunkItems,
    chunkPage,
    chunkSize,
    chunkTotal,
    chunkTotalPages,
    focusChunkIndex,
    fetchList,
    search,
    resetFilters,
    changePage,
    changeSize,
    importDocument,
    loadDuplicates,
    clearImportResult,
    runSearch,
    clearSearch,
    loadIndexState,
    reindex,
    openDetail,
    loadChunks,
    changeChunkPage,
    changeChunkSize,
    closeDetail,
    deleteDocument
  };
});
