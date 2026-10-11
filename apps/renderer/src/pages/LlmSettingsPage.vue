<script setup lang="ts">
/**
 * LLM 设置（契约 §6 v1.1，文件驱动 + 热加载）。
 *
 * - 配置源是 `<UserData>/config/setting.json`：顶部展示 configSource / configPath /
 *   configRevision / configUpdatedAt 与热加载 `warnings[]`，并提供「重新加载配置」；
 * - 没有「绑定/解绑凭据」流程：`apiKey` 随 profile 一起保存，三态语义
 *   （留空不修改 / 点「清除密钥」发 `""` / 填入新值替换）在表单里显式说明；
 * - 导入先预览后确认（预览**不改文件**）；导出分「默认不含密钥」与「含密钥（高风险）」两个入口。
 */
import { computed, onMounted, reactive, ref } from 'vue';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag
} from 'element-plus';

import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import {
  LLM_API_KEY_MASK,
  LLM_PROVIDERS,
  SETTING_CONFIG_SOURCE,
  type LlmImportChange,
  type LlmParameters,
  type LlmProfileInput,
  type LlmProfileView,
  type LlmProvider
} from '@/types/llm';
import { useLlmProfilesStore } from '@/stores/llmProfiles';
import {
  confirmAction,
  confirmDestructive,
  toastError,
  toastInfo,
  toastSuccess,
  toastWarning
} from '@/utils/confirm';
import { downloadTextFile, exportConfigFile, isDesktopHost, selectJsonFile } from '@/utils/desktop';
import { describeError } from '@/utils/error';
import { formatDateTime } from '@/utils/format';
import { LLM_TEST_CATEGORY_LABELS, labelFrom } from '@/utils/labels';
import { parseJsonWithPosition, validateLlmConfigFile } from '@/utils/llmConfig';
import { useRuntimeStore } from '@/stores/runtime';

const store = useLlmProfilesStore();
const runtime = useRuntimeStore();

const editingId = ref<string | null>(null);
const editorVisible = ref(false);
const editorMode = ref<'create' | 'edit'>('create');

const form = reactive({
  id: '',
  name: '',
  provider: 'openai-compatible' as LlmProvider,
  baseUrl: '',
  model: '',
  /** 新密钥输入框：**永不回填**已保存的密钥；非空即「替换」 */
  apiKey: '',
  /** 该档案在 setting.json 中已配置 apiKey（只用于 placeholder 与三态提示） */
  keyConfigured: false,
  temperature: undefined as number | undefined,
  timeoutSeconds: 60,
  connectTimeoutSeconds: 5,
  allowLocalNetwork: false,
  topP: undefined as number | undefined,
  maxTokens: undefined as number | undefined,
  stream: false
});
const fieldErrors = ref<Record<string, string>>({});
const editorError = ref<string | null>(null);

const importVisible = ref(false);
const importText = ref('');
const importParseError = ref<string | null>(null);
const importLocalErrors = ref<{ path: string; message: string }[]>([]);
const importPreview = computed(() => store.preview);

const busyProfileId = ref<string | null>(null);

const profiles = computed(() => store.profiles);
const warnings = computed(() => store.warnings);

const providerLabel: Partial<Record<LlmProvider, string>> = {
  'openai-compatible': 'OpenAI 兼容',
  ollama: 'Ollama'
};

function resetForm(): void {
  form.id = '';
  form.name = '';
  form.provider = 'openai-compatible';
  form.baseUrl = '';
  form.model = '';
  form.apiKey = '';
  form.keyConfigured = false;
  form.temperature = undefined;
  form.timeoutSeconds = 60;
  form.connectTimeoutSeconds = 5;
  form.allowLocalNetwork = false;
  form.topP = undefined;
  form.maxTokens = undefined;
  form.stream = false;
  fieldErrors.value = {};
  editorError.value = null;
}

function openCreate(): void {
  resetForm();
  editorMode.value = 'create';
  editingId.value = null;
  editorVisible.value = true;
}

function openEdit(view: LlmProfileView): void {
  resetForm();
  editorMode.value = 'edit';
  editingId.value = view.id;
  form.id = view.id;
  form.name = view.name;
  form.provider = view.provider;
  form.baseUrl = view.baseUrl;
  form.model = view.model;
  // 明文密钥绝不回填：只记录「是否已配置」用于 placeholder 与三态提示
  form.keyConfigured = view.apiKeyConfigured;
  form.timeoutSeconds = view.timeoutSeconds;
  form.connectTimeoutSeconds = view.connectTimeoutSeconds;
  form.allowLocalNetwork = view.allowLocalNetwork;
  // temperature 与 topP/maxTokens/stream 一样属于 parameters（schema 的 parameters 段）
  form.temperature = numberParam(view.parameters.temperature);
  form.topP = numberParam(view.parameters.topP);
  form.maxTokens = numberParam(view.parameters.maxTokens);
  form.stream = view.parameters.stream === true;
  editorVisible.value = true;
}

/** `ProfileView.parameters` 是 `Map<String,Object>`，读回表单时需要收窄为 number */
function numberParam(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

/** 列表里的 temperature 读自 `parameters.temperature` */
function temperatureOf(row: LlmProfileView): string {
  return numberParam(row.parameters.temperature)?.toString() ?? '—';
}

/** `configRevision` 是 SHA-256 字符串，列表/描述项里截断展示，完整值放 title */
function shortRevision(revision: string | null): string {
  if (!revision) return '—';
  return revision.length > 16 ? `${revision.slice(0, 16)}…` : revision;
}

/** apiKey 输入框 placeholder：已配置显示 `******`，未配置显示「未配置」 */
const apiKeyPlaceholder = computed(() => (form.keyConfigured ? LLM_API_KEY_MASK : '未配置'));

/** 三态提示文案 */
const apiKeyHint = computed(() => {
  if (form.apiKey.length > 0) return '将替换为新密钥（写回 setting.json）。';
  if (form.keyConfigured) return '留空 = 保持原值不变；点「清除密钥」按钮才会移除已保存的密钥。';
  return '当前未配置 apiKey；留空保存则保持未配置状态。';
});

/**
 * 构造 `ProfileInput`。
 * `apiKey` 三态：非空=替换；留空且已配置=**省略字段**（保持原值）；留空且未配置=省略。
 * 「清除密钥」由 `clearApiKey()` 显式发出 `apiKey: ''`。
 */
function buildInput(apiKeyOverride?: string): LlmProfileInput {
  const parameters: LlmParameters = {};
  if (form.temperature !== undefined) parameters.temperature = form.temperature;
  if (form.topP !== undefined) parameters.topP = form.topP;
  if (form.maxTokens !== undefined) parameters.maxTokens = form.maxTokens;
  if (form.stream) parameters.stream = true;

  const input: LlmProfileInput = {
    id: form.id.trim(),
    name: form.name.trim(),
    provider: form.provider,
    baseUrl: form.baseUrl.trim().replace(/\/+$/, ''),
    model: form.model.trim(),
    timeoutSeconds: form.timeoutSeconds,
    connectTimeoutSeconds: form.connectTimeoutSeconds,
    allowLocalNetwork: form.allowLocalNetwork,
    parameters
  };

  if (apiKeyOverride !== undefined) {
    input.apiKey = apiKeyOverride;
  } else if (form.apiKey.length > 0) {
    input.apiKey = form.apiKey;
  }
  // 留空且未点「清除密钥」：不设置 apiKey 字段 → 服务端保持原值
  return input;
}

/** 用同一份 setting.schema.json 校验实现做字段级预检 */
function validateForm(): Record<string, string> {
  const input = buildInput();
  const result = validateLlmConfigFile({
    schemaVersion: 1,
    llm: {
      profiles: [
        {
          id: input.id,
          name: input.name,
          provider: input.provider,
          baseUrl: input.baseUrl,
          model: input.model,
          timeoutSeconds: input.timeoutSeconds,
          connectTimeoutSeconds: input.connectTimeoutSeconds,
          allowLocalNetwork: input.allowLocalNetwork,
          parameters: input.parameters
        }
      ]
    }
  });
  const errors: Record<string, string> = {};
  for (const error of result.errors) {
    const key = error.path
      .replace(/^llm\.profiles\[0\]\.?/, '')
      .replace(/^llm\.profiles\[0\]$/, 'id')
      .replace(/^llm\./, '');
    if (!errors[key || 'id']) errors[key || 'id'] = error.message;
  }
  return errors;
}

async function saveProfile(): Promise<void> {
  editorError.value = null;
  const errors = validateForm();
  if (Object.keys(errors).length > 0) {
    fieldErrors.value = errors;
    return;
  }
  fieldErrors.value = {};
  const replacingKey = form.apiKey.length > 0;
  const keepingKey = !replacingKey && form.keyConfigured;
  try {
    await store.saveProfile(editingId.value, buildInput());
    form.apiKey = '';
    toastSuccess(
      editorMode.value === 'create'
        ? '档案已创建并写入 setting.json'
        : `档案已保存${replacingKey ? '（密钥已替换）' : keepingKey ? '（apiKey 保持原值）' : ''}`
    );
    editorVisible.value = false;
  } catch (caught) {
    const info = describeError(caught);
    editorError.value = info.text;
    fieldErrors.value = info.fieldErrors;
  }
}

/** 清除密钥：显式发送 `apiKey: ""`（第三态），服务端从 setting.json 中移除该字段 */
async function clearApiKey(): Promise<void> {
  const view = profiles.value.find((profile) => profile.id === editingId.value) ?? null;
  if (!view) return;
  const confirmed = await confirmAction(
    `将清除档案「${view.name}」在 setting.json 中的 apiKey。清除后该档案无法调用需要密钥的模型（调用会返回 CREDENTIAL_MISSING），需要重新填写才能恢复。确认清除？`,
    { title: '清除密钥', type: 'warning', confirmText: '清除密钥' }
  );
  if (!confirmed) return;
  try {
    await store.saveProfile(view.id, { ...buildInput(''), apiKey: '' });
    form.apiKey = '';
    form.keyConfigured = false;
    toastSuccess('密钥已从 setting.json 中清除');
  } catch (caught) {
    const info = describeError(caught);
    editorError.value = info.text;
    fieldErrors.value = info.fieldErrors;
  }
}

async function deleteProfile(view: LlmProfileView): Promise<void> {
  const confirmed = await confirmAction(
    `将从 setting.json 中删除档案「${view.name}」，该条目连同其 apiKey 一并移除。正在使用的默认档案需先切换，否则后端会返回状态冲突。确认删除？`,
    { title: '删除档案', type: 'error', confirmText: '删除' }
  );
  if (!confirmed) return;
  busyProfileId.value = view.id;
  try {
    await store.removeProfile(view.id);
    toastSuccess('已从 setting.json 中删除该档案');
  } catch (caught) {
    toastError(describeError(caught).text);
  } finally {
    busyProfileId.value = null;
  }
}

async function setDefault(view: LlmProfileView): Promise<void> {
  const confirmed = await confirmAction(`将「${view.name}」设为默认档案？默认档案用于 AI 主观题评分与解析。`, {
    title: '切换默认档案'
  });
  if (!confirmed) return;
  busyProfileId.value = view.id;
  try {
    await store.setDefault(view.id);
    toastSuccess('默认档案已切换');
  } catch (caught) {
    toastError(describeError(caught).text);
  } finally {
    busyProfileId.value = null;
  }
}

async function disableDefault(): Promise<void> {
  const confirmed = await confirmAction(
    '将清空默认档案并显式关闭 AI 能力（disableAi=true）。答题、判分与错题闭环主流程不受影响，AI 相关接口会返回 LLM_UNAVAILABLE。确认继续？',
    { title: '关闭 AI 能力', type: 'warning' }
  );
  if (!confirmed) return;
  try {
    await store.setDefault(null);
    toastSuccess('已清空默认档案并关闭 AI 能力');
  } catch (caught) {
    toastError(describeError(caught).text);
  }
}

/** 重新加载配置：失败（CONFIG_FILE_INVALID）时展示 schema path 与文件位置 */
async function reloadConfig(): Promise<void> {
  const result = await store.reloadConfig();
  if (result) {
    toastSuccess(
      result.warnings.length > 0
        ? `配置已重新加载（configRevision → ${shortRevision(result.configRevision)}），仍有 ${result.warnings.length} 条警告`
        : `配置已重新加载，configRevision → ${shortRevision(result.configRevision)}`
    );
    return;
  }
  if (store.error) {
    toastError(`${store.error.text}${configFileLocationHint.value}`);
  }
}

const configFileLocationHint = computed(() =>
  store.configPath ? `配置文件位置：${store.configPath}` : '配置文件位置见上方「配置文件（configPath）」。'
);

async function testConnection(view: LlmProfileView): Promise<void> {
  store.clearTestResult();
  busyProfileId.value = view.id;
  try {
    await store.testStoredProfile(view.id);
    const result = store.testResult;
    if (result) {
      if (result.ok) toastSuccess(`连接正常，耗时 ${result.latencyMs} ms`);
      else if (!view.apiKeyConfigured && result.category === 'auth') {
        toastError('连接失败：该档案未配置 apiKey，请在 LLM 设置里填写后再试。');
      } else {
        toastError(`连接失败：${labelFrom(LLM_TEST_CATEGORY_LABELS, result.category)} —— ${result.message}`);
      }
    } else if (store.testError) {
      toastError(store.testError.text);
    }
  } finally {
    busyProfileId.value = null;
  }
}

function openImport(): void {
  importVisible.value = true;
  importText.value = '';
  importParseError.value = null;
  importLocalErrors.value = [];
  store.clearPreview();
}

/** 从文件读取 JSON 文本（桌面壳 dialog 或浏览器降级提示） */
async function pickImportFile(): Promise<void> {
  const picked = await selectJsonFile();
  if (picked.canceled) return;
  if (typeof picked.content === 'string') {
    importText.value = picked.content;
    toastInfo(`已读取文件：${picked.filePath ?? ''}`);
    return;
  }
  toastError('未能读取文件内容。');
}

/** 先用本地 schema 校验，再请求后端预览（**不修改 setting.json**） */
async function runImportPreview(): Promise<void> {
  importParseError.value = null;
  importLocalErrors.value = [];

  const parsed = parseJsonWithPosition(importText.value);
  if (!parsed.ok) {
    importParseError.value = `JSON 语法错误${parsed.line !== null ? `（第 ${parsed.line} 行，第 ${parsed.column} 列）` : ''}：${parsed.message}`;
    return;
  }

  const local = validateLlmConfigFile(parsed.value);
  if (!local.valid) {
    importLocalErrors.value = local.errors;
    return;
  }

  try {
    await store.previewImport(parsed.value);
    if (importPreview.value && importPreview.value.errors.length > 0) {
      importLocalErrors.value = importPreview.value.errors;
    }
  } catch (caught) {
    importParseError.value = describeError(caught).text;
  }
}

async function confirmImport(): Promise<void> {
  const preview = importPreview.value;
  if (!preview) return;
  // 后端 summary 是 ValidationSummary；分类计数由 store.previewCounts 从 changes[] 汇总
  const counts = store.previewCounts;
  const confirmed = await confirmAction(
    `即将把预览应用到 setting.json（原子替换）：新增 ${counts?.create ?? 0}、更新 ${counts?.update ?? 0}、不变 ${counts?.unchanged ?? 0}、冲突 ${counts?.conflict ?? 0}。` +
      `\n当前配置共 ${preview.summary.profileCount} 个档案，涉及 provider：${preview.summary.providers.join('、') || '—'}。` +
      '\n冲突项不会被覆盖。确认应用？',
    { title: '确认导入', confirmText: '写入 setting.json' }
  );
  if (!confirmed) return;
  try {
    const applied = await store.confirmImport(preview.previewId);
    toastSuccess(
      `导入已写入 setting.json：新增 ${applied.created}、更新 ${applied.updated}、不变 ${applied.unchanged}，配置版本 → ${shortRevision(applied.configRevision)}`
    );
    importVisible.value = false;
  } catch (caught) {
    const info = describeError(caught);
    if (info.code === 'STATE_CONFLICT') {
      toastError(`${info.text} 预览已过期或不存在（previewId 有效期约 10 分钟），请重新校验并预览。`);
      return;
    }
    toastError(info.text);
  }
}

/** 落盘一份导出的配置文本（桌面壳保存对话框优先，浏览器退化为 Blob 下载） */
async function saveExportedFile(
  fileName: string,
  content: string,
  browserToast: string
): Promise<'saved' | 'canceled'> {
  if (isDesktopHost()) {
    const saved = await exportConfigFile(fileName, content);
    if (saved.canceled) {
      toastInfo('已取消导出');
      return 'canceled';
    }
    toastSuccess(`已导出到 ${saved.filePath ?? '所选位置'}`);
    return 'saved';
  }
  downloadTextFile(fileName, content);
  toastSuccess(browserToast);
  return 'saved';
}

/** 默认导出：完整配置（setting.json 同构），**不含密钥** */
async function exportConfig(): Promise<void> {
  try {
    const response = await store.exportConfig(false);
    const content = `${JSON.stringify(response.config, null, 2)}\n`;
    await saveExportedFile(
      response.suggestedFileName ?? SETTING_CONFIG_SOURCE,
      content,
      '已导出不含密钥的完整配置（浏览器下载）'
    );
  } catch (caught) {
    toastError(describeError(caught).text);
  }
}

/** 高风险导出：含明文密钥，必须二次强确认 */
async function exportConfigWithSecrets(): Promise<void> {
  const confirmed = await confirmDestructive(
    '即将导出**包含明文 apiKey** 的完整配置（setting.json 同构）。\n' +
      '该文件一旦落盘，任何能读取它的进程或用户都能直接拿到密钥；服务端会记录审计事件，且不会包含内部令牌。\n' +
      '仅在你明确需要迁移/备份密钥时使用，导出后请自行妥善保管并及时清理。',
    '导出含密钥',
    '导出含密钥（高风险）'
  );
  if (!confirmed) return;
  try {
    const response = await store.exportConfig(true);
    const content = `${JSON.stringify(response.config, null, 2)}\n`;
    const outcome = await saveExportedFile(
      response.suggestedFileName ?? 'setting-with-keys.json',
      content,
      '已导出含密钥的完整配置（浏览器下载）'
    );
    if (outcome === 'canceled') return;
    if (response.containsSecrets === true) {
      toastWarning('该文件含明文密钥（响应已标记 containsSecrets=true），请自行妥善保管，不要提交到版本库或共享。');
    } else {
      toastWarning('该文件含明文密钥，请自行妥善保管，不要提交到版本库或共享。');
    }
  } catch (caught) {
    toastError(describeError(caught).text);
  }
}

function changeLabel(action: LlmImportChange['action']): string {
  switch (action) {
    case 'create':
      return '新增';
    case 'update':
      return '更新';
    case 'unchanged':
      return '无变化';
    case 'conflict':
      return '冲突';
  }
}

function changeTagType(action: LlmImportChange['action']): 'success' | 'warning' | 'info' | 'danger' {
  if (action === 'create') return 'success';
  if (action === 'update') return 'warning';
  if (action === 'conflict') return 'danger';
  return 'info';
}

onMounted(() => {
  void store.load();
});
</script>

<template>
  <div class="llm">
    <PageHeader
      title="LLM 设置"
      description="模型档案与密钥都保存在设置文件中并支持热加载；导入先预览后确认，导出默认不含密钥。"
    >
      <template #actions>
        <ElButton @click="openImport">JSON 导入</ElButton>
        <ElButton @click="exportConfig">导出完整配置（不含密钥）</ElButton>
        <ElButton type="warning" plain @click="exportConfigWithSecrets">导出含密钥（高风险）</ElButton>
        <ElButton type="primary" @click="openCreate">新建档案</ElButton>
      </template>
    </PageHeader>

    <ElAlert
      class="llm__notice"
      type="info"
      :closable="false"
      title="配置来源：本机设置文件（不再使用 SQLite / OS 凭据存储）"
      description="LLM 配置与用户偏好保存在配置文件中，Java 侧缓存该文件并支持热加载。明文 apiKey 就存放在这个文件里（写权限收紧到 0600），请自行保证本机账户安全；接口响应、日志与审计一律不回显密钥。"
    />

    <!-- 配置来源与热加载状态 -->
    <section class="panel llm__status">
      <div class="llm__section-head">
        <h2 class="llm__section-title">配置来源与热加载</h2>
        <div class="toolbar">
          <ElButton size="small" :loading="store.reloading" @click="reloadConfig">重新加载配置</ElButton>
          <ElButton size="small" @click="store.load()">刷新</ElButton>
        </div>
      </div>

      <ElDescriptions :column="2" border size="small">
        <ElDescriptionsItem label="配置来源（configSource）">
          <ElTag size="small" type="info">{{ store.configSource || SETTING_CONFIG_SOURCE }}</ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="配置文件（configPath）">
          <span class="mono">{{ store.configPath || '—' }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="配置版本（configRevision）">
          <span class="mono" :title="store.configRevision ?? ''">{{ shortRevision(store.configRevision) }}</span>
          <span class="llm__hint">修订号 = setting.json 字节的 SHA-256，写入后变化</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="文件更新时间（configUpdatedAt）">
          {{ store.configUpdatedAt ? formatDateTime(store.configUpdatedAt) : '—' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="默认档案" :span="2">
          {{ store.defaultProfile?.name ?? '未设置（AI 能力关闭或未配置）' }}
        </ElDescriptionsItem>
      </ElDescriptions>

      <div class="llm__warnings">
        <ElAlert
          v-if="store.reloadIssues.length > 0"
          class="llm__notice"
          type="error"
          :closable="false"
          title="配置重新加载失败（CONFIG_FILE_INVALID）"
          :description="`当前仍在使用上一份有效配置，服务未中断。请按下面的 schema path 修正文件后再次点击「重新加载配置」。${configFileLocationHint}`"
        />
        <ul v-if="store.reloadIssues.length > 0" class="llm__issue-list">
          <li v-for="(issue, index) in store.reloadIssues" :key="`reload-${index}`">
            <code>{{ issue }}</code>
          </li>
        </ul>

        <template v-if="warnings.length > 0">
          <ElAlert
            class="llm__notice"
            type="warning"
            :closable="false"
            :title="`配置警告（共 ${warnings.length} 条）`"
            description="当前仍在使用上一份有效配置，服务未中断；修正文件后点击「重新加载配置」即可生效。"
          />
          <ElAlert
            v-for="(warning, index) in warnings"
            :key="`${warning.code}-${index}`"
            class="llm__notice"
            type="warning"
            :closable="false"
            :title="`${warning.message}${warning.path ? `（${warning.path}）` : ''}`"
            :description="`错误码：${warning.code}`"
          />
        </template>
        <ElAlert
          v-else-if="store.loaded && store.reloadIssues.length === 0"
          class="llm__notice"
          type="success"
          :closable="false"
          title="配置已加载，没有热加载警告"
          description="文件变化（去抖后）会被校验并替换内存缓存；校验失败时保留上一份有效配置并把原因放进警告列表。"
        />
      </div>
    </section>

    <section class="panel">
      <div class="llm__section-head">
        <h2 class="llm__section-title">配置档案</h2>
        <div class="toolbar">
          <ElButton size="small" :disabled="store.defaultProfileId === null" @click="disableDefault">
            清空默认并关闭 AI
          </ElButton>
        </div>
      </div>

      <StatePanel
        :state="store.loading && profiles.length === 0 ? 'loading' : store.error ? 'error' : store.isEmpty ? 'empty' : 'ready'"
        :description="store.error?.message ?? '还没有配置档案。新建一个档案并填写 apiKey 后即可使用 AI 评分与解析。'"
        :code="store.error?.code ?? null"
        :request-id="store.error?.requestId ?? null"
        :retryable="store.error?.retryable ?? false"
        action-text="新建档案"
        @retry="store.load()"
        @action="openCreate"
      >
        <ElTable :data="profiles" row-key="id">
          <ElTableColumn label="名称" min-width="150">
            <template #default="{ row }">
              <span class="llm__name">{{ row.name }}</span>
              <ElTag v-if="row.isDefault" size="small" type="success" class="llm__default-tag">默认</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="标识" width="130">
            <template #default="{ row }"><code class="mono">{{ row.id }}</code></template>
          </ElTableColumn>
          <ElTableColumn label="Provider" width="140">
            <template #default="{ row }">{{ providerLabel[row.provider as LlmProvider] ?? row.provider }}</template>
          </ElTableColumn>
          <ElTableColumn label="Base URL" min-width="190">
            <template #default="{ row }"><span class="mono">{{ row.baseUrl }}</span></template>
          </ElTableColumn>
          <ElTableColumn label="模型" min-width="130">
            <template #default="{ row }"><span class="mono">{{ row.model }}</span></template>
          </ElTableColumn>
          <ElTableColumn label="apiKey" width="120">
            <template #default="{ row }">
              <ElTag size="small" :type="row.apiKeyConfigured ? 'success' : 'danger'">
                {{ row.apiKeyConfigured ? (row.apiKeyMasked || LLM_API_KEY_MASK) : '未配置' }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="temperature" width="110">
            <template #default="{ row }">{{ temperatureOf(row as LlmProfileView) }}</template>
          </ElTableColumn>
          <ElTableColumn label="超时（请求/连接）" width="140">
            <template #default="{ row }">{{ row.timeoutSeconds }}s / {{ row.connectTimeoutSeconds }}s</template>
          </ElTableColumn>
          <ElTableColumn label="允许内网" width="100">
            <template #default="{ row }">
              <ElTag size="small" :type="row.allowLocalNetwork ? 'warning' : 'info'">
                {{ row.allowLocalNetwork ? '已允许' : '不允许' }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="280" fixed="right">
            <template #default="{ row }">
              <ElButton size="small" text type="primary" @click="openEdit(row as LlmProfileView)">编辑</ElButton>
              <ElButton
                size="small"
                text
                :disabled="row.isDefault"
                :loading="busyProfileId === String(row.id)"
                @click="setDefault(row as LlmProfileView)"
              >
                设为默认
              </ElButton>
              <ElButton
                size="small"
                text
                :loading="busyProfileId === String(row.id)"
                @click="testConnection(row as LlmProfileView)"
              >
                测试连接
              </ElButton>
              <ElButton
                size="small"
                text
                type="danger"
                :loading="busyProfileId === String(row.id)"
                @click="deleteProfile(row as LlmProfileView)"
              >
                删除
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
      </StatePanel>
    </section>

    <!-- 测试连接结果 -->
    <section v-if="store.testResult || store.testError" class="panel">
      <div class="llm__section-head">
        <h2 class="llm__section-title">测试连接结果</h2>
        <ElButton size="small" text @click="store.clearTestResult()">关闭</ElButton>
      </div>
      <StatePanel
        :state="store.testError ? 'error' : 'ready'"
        :description="store.testError?.text ?? ''"
        :code="store.testError?.code ?? null"
        :retryable="false"
      >
        <ElDescriptions v-if="store.testResult" :column="2" border size="small">
          <ElDescriptionsItem label="结果">
            <ElTag size="small" :type="store.testResult.ok ? 'success' : 'danger'">
              {{ store.testResult.ok ? '连接成功' : '连接失败' }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="错误分类（category）">
            {{ labelFrom(LLM_TEST_CATEGORY_LABELS, store.testResult.category) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="耗时">{{ store.testResult.latencyMs }} ms</ElDescriptionsItem>
          <ElDescriptionsItem label="HTTP 状态码">{{ store.testResult.httpStatus ?? '—' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="模型回显">{{ store.testResult.modelEcho ?? '—' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="建议">{{ store.testResult.suggestion || '—' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="说明" :span="2">{{ store.testResult.message }}</ElDescriptionsItem>
        </ElDescriptions>
      </StatePanel>
    </section>

    <!-- 档案编辑对话框 -->
    <ElDialog
      v-model="editorVisible"
      :title="editorMode === 'create' ? '新建 LLM 档案' : '编辑 LLM 档案'"
      width="760px"
      top="6vh"
    >
      <ElAlert
        v-if="editorError"
        class="llm__notice"
        type="error"
        :closable="false"
        title="保存失败"
        :description="editorError"
      />
      <ElForm label-position="top">
        <div class="llm__form-row">
          <ElFormItem label="档案 id" required :error="fieldErrors.id" class="llm__form-grow">
            <ElInput
              v-model="form.id"
              :disabled="editorMode === 'edit'"
              placeholder="例如：local-ollama（^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$）"
            />
          </ElFormItem>
          <ElFormItem label="名称" required :error="fieldErrors.name" class="llm__form-grow">
            <ElInput v-model="form.name" maxlength="64" placeholder="例如：本地 Ollama" />
          </ElFormItem>
        </div>

        <div class="llm__form-row">
          <ElFormItem label="Provider" required :error="fieldErrors.provider" class="llm__form-grow">
            <ElSelect v-model="form.provider">
              <ElOption
                v-for="provider in LLM_PROVIDERS"
                :key="provider"
                :label="providerLabel[provider] ?? provider"
                :value="provider"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="模型" required :error="fieldErrors.model" class="llm__form-grow">
            <ElInput v-model="form.model" maxlength="128" placeholder="例如：qwen2.5:7b" />
          </ElFormItem>
        </div>

        <ElFormItem label="Base URL" required :error="fieldErrors.baseUrl">
          <ElInput v-model="form.baseUrl" placeholder="例如：http://127.0.0.1:11434/v1（不含尾部斜杠）" />
        </ElFormItem>

        <!-- apiKey 三态 -->
        <ElFormItem label="apiKey" :error="fieldErrors.apiKey">
          <div class="llm__key-row">
            <ElInput
              v-model="form.apiKey"
              type="password"
              show-password
              autocomplete="new-password"
              class="llm__key-input"
              :placeholder="apiKeyPlaceholder"
            />
            <ElButton
              v-if="editorMode === 'edit' && form.keyConfigured"
              type="warning"
              plain
              :loading="store.saving"
              @click="clearApiKey"
            >
              清除密钥
            </ElButton>
          </div>
          <p class="llm__hint">{{ apiKeyHint }}</p>
          <p class="llm__hint">
            明文 apiKey 保存在配置文件中，绝不回显：输入框永不回填已保存的密钥，placeholder 只显示
            <code>{{ LLM_API_KEY_MASK }}</code>（已配置）或「未配置」；接口响应只返回
            <code>apiKeyMasked</code> / <code>apiKeyConfigured</code>。
          </p>
        </ElFormItem>

        <div class="llm__form-row">
          <ElFormItem
            label="temperature（0-2，选填）"
            :error="fieldErrors['parameters.temperature']"
            class="llm__form-grow"
          >
            <ElInputNumber v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="2" />
            <p class="llm__hint">随 parameters 一起保存到 setting.json（与 topP / maxTokens / stream 同段）。</p>
          </ElFormItem>
          <ElFormItem label="请求超时（秒）" :error="fieldErrors.timeoutSeconds" class="llm__form-grow">
            <ElInputNumber v-model="form.timeoutSeconds" :min="1" :max="900" />
          </ElFormItem>
          <ElFormItem label="连接超时（秒）" :error="fieldErrors.connectTimeoutSeconds" class="llm__form-grow">
            <ElInputNumber v-model="form.connectTimeoutSeconds" :min="1" :max="120" />
          </ElFormItem>
        </div>

        <div class="llm__form-row">
          <ElFormItem label="topP（0-1）" :error="fieldErrors['parameters.topP']" class="llm__form-grow">
            <ElInputNumber v-model="form.topP" :min="0" :max="1" :step="0.05" :precision="2" />
          </ElFormItem>
          <ElFormItem label="maxTokens" :error="fieldErrors['parameters.maxTokens']" class="llm__form-grow">
            <ElInputNumber v-model="form.maxTokens" :min="1" :max="131072" />
          </ElFormItem>
          <ElFormItem label="流式输出（stream）" class="llm__form-grow">
            <ElSwitch v-model="form.stream" />
          </ElFormItem>
        </div>

        <ElFormItem label="允许本地/内网地址">
          <ElSwitch v-model="form.allowLocalNetwork" />
          <p class="llm__hint">
            关闭时解析到 loopback、RFC1918 私网、链路本地或云元数据地址会被安全策略拒绝（BASE_URL_BLOCKED）。
          </p>
        </ElFormItem>

        <ElAlert
          type="info"
          :closable="false"
          title="保存即写入配置文件"
          description="保存会原子替换配置文件并更新 configRevision（= 文件字节的 SHA-256）；校验失败不会落盘，服务继续使用上一份有效配置。"
        />
      </ElForm>
      <template #footer>
        <ElButton @click="editorVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="store.saving" @click="saveProfile">保存</ElButton>
      </template>
    </ElDialog>

    <!-- JSON 导入对话框 -->
    <ElDialog v-model="importVisible" title="JSON 导入（先预览再确认）" width="880px" top="5vh">
      <ElAlert
        class="llm__notice"
        type="info"
        :closable="false"
        title="预览不会修改任何文件"
        description="「校验并预览」只在服务端内存中计算变更表（create / update / unchanged / conflict）与 previewId，不会写 setting.json；只有点「确认应用」才会原子替换配置文件。previewId 约 10 分钟过期，过期后需重新预览，否则返回 STATE_CONFLICT。"
      />
      <div class="toolbar llm__import-toolbar">
        <ElButton @click="pickImportFile">选择 JSON 文件</ElButton>
        <span class="text-muted">或直接粘贴 setting.json 内容（含 apiKey 亦可，仅提交给后端预览）</span>
      </div>
      <ElInput
        v-model="importText"
        type="textarea"
        :rows="8"
        placeholder='{"schemaVersion":1,"llm":{"defaultProfileId":null,"profiles":[…]}}'
      />
      <p v-if="importParseError" class="llm__error">{{ importParseError }}</p>

      <div v-if="importLocalErrors.length > 0" class="llm__validation">
        <p class="llm__validation-title">配置校验错误（共 {{ importLocalErrors.length }} 条）</p>
        <ul class="llm__validation-list">
          <li v-for="(error, index) in importLocalErrors" :key="`${error.path}-${index}`">
            <code>{{ error.path || '(根节点)' }}</code>：{{ error.message }}
          </li>
        </ul>
      </div>

      <div v-if="importPreview" class="llm__preview">
        <ElAlert
          type="success"
          :closable="false"
          title="预览结果（尚未修改 setting.json）"
          :description="`预览 id：${importPreview.previewId}（过期时间 ${formatDateTime(importPreview.expiresAt)}，约 10 分钟）。当前配置档案数 ${importPreview.summary.profileCount}，provider：${importPreview.summary.providers.join('、') || '—'}。变更明细：新增 ${store.previewCounts?.create ?? 0} · 更新 ${store.previewCounts?.update ?? 0} · 不变 ${store.previewCounts?.unchanged ?? 0} · 冲突 ${store.previewCounts?.conflict ?? 0}`"
        />
        <ElTable :data="importPreview.changes" size="small" max-height="260">
          <ElTableColumn label="动作" width="100">
            <template #default="{ row }">
              <ElTag size="small" :type="changeTagType(row.action as LlmImportChange['action'])">
                {{ changeLabel(row.action as LlmImportChange['action']) }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="档案 id" width="160">
            <template #default="{ row }"><code class="mono">{{ row.profileId ?? '（自动生成）' }}</code></template>
          </ElTableColumn>
          <ElTableColumn label="名称" width="160" prop="name" />
          <ElTableColumn label="变化字段" min-width="220">
            <template #default="{ row }">
              <span v-if="!row.fields || row.fields.length === 0" class="text-muted">
                {{ row.action === 'create' ? '新增档案' : '无字段差异' }}
              </span>
              <span v-else>{{ (row.fields as string[]).join('、') }}</span>
            </template>
          </ElTableColumn>
        </ElTable>
      </div>

      <template #footer>
        <ElButton @click="importVisible = false">取消</ElButton>
        <ElButton
          :disabled="importText.trim().length === 0"
          :loading="store.previewLoading"
          @click="runImportPreview"
        >
          校验并预览
        </ElButton>
        <ElButton type="primary" :disabled="!importPreview" @click="confirmImport">
          确认应用（写入 setting.json）
        </ElButton>
      </template>
    </ElDialog>

    <ElCard v-if="runtime.ready" shadow="never" class="llm__tips">
      <template #header><span>待与后端对齐</span></template>
      <p class="llm__hint">
        LLM 配置 §6 的新接口（文件驱动 + 热加载）尚未做运行期核对——Java 侧改造进行中，
        本页只按契约 §6 v1.1 冻结文本实现，导出后请核对 <code>schemaVersion</code> /
        <code>llm.profiles[].apiKey</code>（默认导出应为 <code>null</code>）与
        <code>containsSecrets</code> 标记。
      </p>
    </ElCard>
  </div>
</template>

<style scoped>
.llm__notice {
  margin-bottom: var(--space-4);
}

.llm__status {
  margin-bottom: var(--space-4);
}

.llm__section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
  margin-bottom: var(--space-4);
}

.llm__section-title {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-semibold);
}

.llm__name {
  margin-right: var(--space-2);
}

.llm__default-tag {
  vertical-align: middle;
}

.llm__warnings {
  margin-top: var(--space-4);
}

.llm__issue-list {
  margin: 0 0 var(--space-4);
  padding-left: var(--space-5);
  font-size: var(--font-size-sm);
  color: var(--color-danger);
}

.llm__form-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-4);
}

.llm__form-grow {
  flex: 1 1 220px;
}

.llm__key-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
}

.llm__key-input {
  flex: 1 1 auto;
}

.llm__hint {
  margin: var(--space-1) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}

.llm__error {
  margin: var(--space-2) 0 0;
  color: var(--color-danger);
  font-size: var(--font-size-sm);
}

.llm__import-toolbar {
  margin-bottom: var(--space-3);
}

.llm__validation {
  margin-top: var(--space-3);
  padding: var(--space-3);
  border: 1px solid var(--color-danger);
  border-radius: var(--radius-md);
  background: var(--color-danger-bg);
}

.llm__validation-title {
  margin: 0 0 var(--space-2);
  font-weight: var(--font-weight-medium);
  color: var(--color-danger);
}

.llm__validation-list {
  margin: 0;
  padding-left: var(--space-5);
  font-size: var(--font-size-sm);
  max-height: 220px;
  overflow: auto;
}

.llm__preview {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin-top: var(--space-3);
}

.llm__tips {
  margin-top: var(--space-4);
}
</style>
