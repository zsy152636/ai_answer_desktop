# apps/renderer —— AI 智能答题系统 · 前端渲染层

Vue 3 + TypeScript + Vite 实现的渲染层。技术选型与依赖已冻结：

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| vue | 3.5.x | 视图层（组合式 API + `<script setup>`） |
| typescript | 5.9.x | 严格类型（`strict` + `noUncheckedIndexedAccess`，禁止 `any`） |
| vite | 5.4.x | dev server 与构建 |
| vue-router | 4.6.x | hash 模式路由（便于 Electron 以 `file://` 加载） |
| pinia | 2.3.x | 状态管理 |
| element-plus | 2.14.x | MIT，中文界面（`zh-cn` locale） |
| HTTP | 原生 `fetch` | **未引入 axios** |

> 运行时依赖仅以上 5 项（`vue`、`vue-router`、`pinia`、`element-plus` 及其传递依赖）。
> 未引入 axios、未引入任何 JSON Schema / 图表 / 工具库；JSON Schema 校验为手写实现（见下文）。

---

## 1. 目录结构

```
apps/renderer/
├── index.html                  # 挂载点 + CSP + 首屏兜底文案
├── package.json                # dev / build / preview / typecheck 脚本
├── pnpm-workspace.yaml         # pnpm 11 的 allowBuilds 白名单（esbuild / vue-demi）
├── tsconfig.json               # 应用侧 TS 配置（含 vite.config.ts）
├── tsconfig.node.json          # 供需要独立校验配置文件的场景使用
├── env.d.ts                    # window.__APP_RUNTIME__ / window.app 白名单声明 + *.vue shim
├── vite.config.ts              # 别名、base './'、manualChunks、仅 dev 的 /__app_runtime 插件
├── scripts/
│   └── with-local-tmp.mjs      # 把 TMP/TEMP 指向工程内目录后再执行构建（见 §7）
└── src/
    ├── main.ts                 # createApp + pinia + router + Element Plus(zh-cn)
    ├── App.vue                 # RuntimeGuard → AppLayout
    ├── api/                    # HTTP 与领域接口层
    │   ├── client.ts           # 统一 fetch 封装：包络解析、ApiError、X-Session-Token、Idempotency-Key
    │   ├── runtime.ts          # 运行时解析优先级：__APP_RUNTIME__ → dev shim → 明确错误态
    │   ├── questions.ts        # §2 题库与题目版本
    │   ├── papers.ts           # §3 试卷与发布快照
    │   ├── attempts.ts         # §4 答题与判分（含 AI 主观题评分调用）
    │   ├── wrongQuestions.ts   # §5 错题闭环
    │   ├── llmProfiles.ts      # §6 LLM 配置（文件驱动 v1.1：setting.json + 热加载）
    │   ├── system.ts           # §1 健康/元信息 + §8 系统设置
    │   ├── dashboard.ts        # §1 仪表盘
    │   ├── tasks.ts            # §9 异步任务
    │   ├── documents.ts        # §11 资料库与本地检索
    │   └── index.ts
    ├── components/
    │   ├── AppLayout.vue       # 侧边导航 + 顶部服务状态栏 + 内容区
    │   ├── RuntimeGuard.vue    # 运行时未就绪时的明确错误态（不白屏）
    │   ├── PageHeader.vue      # 面包屑 + 标题 + 描述 + 操作区
    │   ├── StatePanel.vue      # loading / empty / error / retryable / ready 统一组件
    │   ├── QuestionCard.vue    # 题干、选项、标准答案、解析展示
    │   └── AnswerEditor.vue    # 按题型的作答输入（单选/多选/判断/填空/简答）
    ├── pages/
    │   ├── dashboard/DashboardPage.vue
    │   ├── questions/QuestionListPage.vue
    │   ├── questions/QuestionEditPage.vue
    │   ├── papers/PaperListPage.vue
    │   ├── papers/PaperEditPage.vue
    │   ├── attempts/AttemptListPage.vue
    │   ├── attempts/AttemptPage.vue
    │   ├── attempts/AttemptResultPage.vue
    │   ├── wrongQuestions/WrongQuestionsPage.vue
    │   ├── LlmSettingsPage.vue
    │   ├── SystemSettingsPage.vue
    │   ├── DocumentsPage.vue   # 资料库：列表/导入（重复文件三选一）/检索测试/索引状态与重建/分块核对
    │   ├── AboutPage.vue
    │   └── NotFoundPage.vue
    ├── router/index.ts         # 全部路由（hash 模式）
    ├── stores/                 # pinia：runtime / dashboard / questions / papers /
    │                           #        attempt / wrongQuestions / llmProfiles / system / documents
    ├── styles/tokens.css       # CSS 变量设计令牌 + Element Plus 主题变量对齐
    ├── types/                  # 按契约定义的 interface（camelCase 严格照抄）
    │   ├── common.ts           # 包络、错误码、枚举、分页
    │   ├── question.ts paper.ts attempt.ts wrongQuestion.ts
    │   ├── llm.ts              # §6 ProfileView / ProfileInput（apiKey 三态）/ 配置来源与 warnings
    │   ├── system.ts task.ts dashboard.ts
    │   ├── document.ts         # §11 资料库：DocumentSummary / ChunkView / SearchHit / IndexState
    │   └── index.ts
    └── utils/
        ├── answer.ts           # 空作答构造、作答可读化、选项 key 工具
        ├── confirm.ts          # 确认框 / 强确认 / Element Plus message
        ├── desktop.ts          # window.app 白名单封装 + 浏览器降级
        ├── error.ts            # ApiError → 可读文案 + 字段级错误回填 + schema path 提取
        ├── format.ts           # 日期/字节/时长/分数格式化
        ├── labels.ts           # 枚举中文标签与标签色
        └── llmConfig.ts        # setting.schema.json 的前端校验实现（含 llm 段结构）
```

---

## 2. 开发与构建命令

在 `apps/renderer/` 目录下执行（路径含中文与空格，命令务必加引号）：

```powershell
# 安装依赖
pnpm install

# 开发（默认 http://127.0.0.1:5173）
pnpm dev

# 类型检查（不产出文件）
pnpm typecheck          # 等价于 vue-tsc --noEmit

# 生产构建：先 vue-tsc --noEmit，再 vite build
pnpm build

# 预览已构建产物（默认 http://127.0.0.1:4173）
pnpm preview
```

### 关于 `pnpm build` 与 Windows 临时目录

`pnpm build` 就是要求的 `vue-tsc --noEmit && vite build`，正常环境下直接可用。

在**受控沙箱 / 受限 ACL / 杀软实时防护**的 Windows 环境中，esbuild 无法删除 `%TEMP%` 下的临时文件，
构建会以如下错误失败（与代码无关，属运行环境权限问题）：

```
[vite:esbuild-transpile] remove C:\Users\...\AppData\Local\Temp\esbuild-xxxx: Access is denied.
```

此时使用等价的备用脚本，它把 `TMP`/`TEMP`/`TMPDIR` 指向工程内的 `.tmp-build/` 后再执行同样的构建：

```powershell
pnpm build:local-tmp      # = node scripts/with-local-tmp.mjs build → vue-tsc --noEmit && vite build
```

`pnpm dev` / `pnpm preview` 不受影响（dev 期 esbuild 只做内存转换，不落盘临时文件）。

### pnpm 11 的构建脚本白名单

pnpm 11 起，`package.json` 的 `pnpm` 字段不再生效，构建脚本白名单迁移到 `pnpm-workspace.yaml`：

```yaml
allowBuilds:
  esbuild: true
  vue-demi: true
```

若不批准，`pnpm install` 会以 `ERR_PNPM_IGNORED_BUILDS` 退出。仓库中已提交该文件。

---

## 3. 运行时注入与 dev shim

### 3.1 运行时解析优先级

| 顺序 | 来源 | 说明 |
| --- | --- | --- |
| 1 | `window.__APP_RUNTIME__` | Electron preload 注入，`{ javaBaseUrl, sessionToken, dataDir, logDir, appVersion, platform }` |
| 1b | `window.app.getRuntime()` | 契约 §10 定义的方式；preload 若未注入全局变量则通过该白名单方法异步获取 |
| 2 | `GET /__app_runtime` | **仅 dev 生效**的 Vite 中间件，结果缓存在内存 |
| 3 | 失败 | 页面展示「本机服务未就绪」错误态（`RuntimeGuard.vue`），**不白屏** |

解析实现见 `src/api/runtime.ts`；错误态与「重试」按钮见 `src/components/RuntimeGuard.vue`。

### 3.2 dev shim 行为

`vite.config.ts` 中的 `ai-answer:app-runtime-shim` 插件（`apply: 'serve'`，仅 dev）：

- 端点文件路径取环境变量 `AIA_ENDPOINT_FILE`；未设置时缺省为**仓库根**（`<repo>/apps/renderer` 上溯两级）下的
  `.runtime/java-endpoint.json`
- 读取其中的 `port` / `baseUrl` / `token`，映射为 `{ javaBaseUrl, sessionToken }` 返回
- `baseUrl` 缺省时用 `http://127.0.0.1:<port>` 合成
- 文件不存在、JSON 非法或缺少 `baseUrl`/`port`/`token` 时返回 **503** 与可读的中文提示

示例：

```powershell
$env:AIA_ENDPOINT_FILE = 'D:\dev\data\runtime\java-endpoint.json'
pnpm dev
```

```jsonc
// 请求 GET /__app_runtime
{
  "javaBaseUrl": "http://127.0.0.1:53123",
  "sessionToken": "…",
  "source": "dev-shim",
  "endpointFile": "E:\\...\\java-endpoint.json"
}
```

### 3.3 桌面壳能力（契约 §10 白名单）

渲染层只通过 `window.app` 使用桌面能力，封装在 `src/utils/desktop.ts`：
`selectJsonFile`、`selectImportFile`（资料导入，返回 `{ filePath, name, sizeBytes, sha256 }`）、
`exportConfigFile`、`openPath`、`showLogs`、`restartServices`、`onServiceStatus`、`getHealth`。
浏览器调试时这些能力降级为提示文案，不会抛异常。

> `onServiceStatus` 的订阅在 `runtime` store 的 `onScopeDispose` 中释放；`App.vue` 卸载时调用 `runtime.dispose()`。

---

## 4. HTTP 客户端

`src/api/client.ts`：

- URL 拼接：`<javaBaseUrl>` + `/api/v1` + path（`/health` 通过 `raw: true` 跳过前缀）
- 所有 `/api/v1/**` 请求带 `X-Session-Token`
- 解析契约 §0.1/§0.2 包络；`success === false` 抛出 `ApiError`，携带
  `code` / `message` / `details` / `retryable` / `requestId` / `httpStatus`
- 网络失败 → `NETWORK_UNAVAILABLE`（`retryable: true`）
- HTTP 401 → `UNAUTHORIZED`，并触发 `onUnauthorized` 回调 → runtime store 进入会话失效态，页面提示可「重试」
- 未包裹络的响应（如 `/health`）按原始 JSON 返回
- 幂等：`idempotencyKey: true` 自动生成 `renderer-<uuid>`；提交作答时在 store 中缓存同一键以便重试复用

`ApiError.fieldErrors` 把 `details[].field` / `details[].path` 规整为表单可用的 key，
用于「后端 `details` 回填」到 `ElFormItem` 的 `error`。

---

## 5. 页面与路由

hash 模式（`createWebHashHistory`）。

| 路由 | 页面 | 要点 |
| --- | --- | --- |
| `/` | 仪表盘 | 继续答题、最近作答、待复习错题数、题库/试卷统计、Java 与 AI 服务状态徽标 |
| `/questions` | 题库列表 | 关键字/题型/状态/标签筛选、排序、分页、新建/编辑/发布/归档/删除（均有确认框）、空状态与失败状态 |
| `/questions/new`、`/questions/:id/edit` | 题目编辑 | 题干/题型/难度/选项增删与正确项/答案/解析/知识点标签、版本历史侧栏、`onBeforeRouteLeave` 未保存提示、`expectedVersionNo` 乐观锁冲突提示 |
| `/papers` | 试卷列表 | 筛选、分页、编辑、发布（发布前确认）、归档 |
| `/papers/new`、`/papers/:id/edit` | 试卷编辑 | 从题库挑题（仅 `published`）、排序、逐题分值、总分实时校验、预览、发布 |
| `/attempts` | 作答记录 | 筛选、继续作答、查看结果、从已发布试卷开始新的整卷作答 |
| `/attempts/:id` | 答题页 | 整卷/单题作答、防抖自动保存（保存中/已保存/失败）、未答题提示、提交确认、刷新后恢复未完成作答 |
| `/attempts/:id/result` | 结果页 | 总分与 `correctCount/wrongCount/unansweredCount/pendingReviewCount`；逐题对错、你的答案/标准答案/解析；`pending_review` 主观题标注「AI 建议分待复核」并展示 `gradingDetail.suggestedScore`，提供「请求 AI 评分」（排队 + 轮询任务）与「人工确认 / 修改分数」 |
| `/wrong-questions` | 错题本 | 状态/知识点/到期筛选、去练习（`wrong_practice` attempt）、提交复习结果（含用时）、手动掌握/归档/恢复、版本差异提示（`currentVersionNo` vs `wrongVersionNo`）、复习记录时间线（间隔天数变化 + 下次复习时间） |
| `/settings/llm` | LLM 设置 | **配置来源与热加载区**（`configSource` 固定 `setting.json`、`configPath`、`configRevision`（SHA-256，截断展示 + 全值 `title`）、`configUpdatedAt`、`warnings[]` 逐条 `ElAlert`（标题 `message（path）`）+ 「当前仍在使用上一份有效配置，服务未中断」、**重新加载配置** `POST /settings/llm-profiles/reload`，失败 `CONFIG_FILE_INVALID` 展示 schema path 明细与文件绝对路径）；档案列表与编辑（`temperature` 0-2 选填，随 `parameters` 段保存、超时、允许内网）；**apiKey 随档案保存的三态语义**（留空=保持原值 / 「清除密钥」发 `apiKey: ""` / 填新值=替换，输入框永不回填，placeholder `******` 或「未配置」）；设为默认；测试连接（category/耗时/建议）；JSON 导入（`import/preview` → `import/confirm`，**预览不改文件**、create/update/unchanged/conflict 明细、`previewId` 过期 `STATE_CONFLICT` 提示）；两种导出（默认 `includeSecrets:false` 的完整配置；「导出含密钥（高风险）」走强确认 → `includeSecrets:true`，建议文件名 `setting-with-keys.json`，成功提示「该文件含明文密钥，请自行妥善保管」，响应 `containsSecrets:true` 时显式展示） |
| `/settings/system` | 系统设置 | 数据目录/日志目录、数据库信息（含 **`configSource` + `configPath`**、`fts5Available`、契约版本、`aiService` 状态）、立即备份、备份列表 + **只读校验**、恢复（强确认 + 需重启提示 + `restartServices`）、完整性检查、写入演示数据、日志查看（`/system/logs` + `/system/logs/tail`） |
| `/documents` | 资料库 | 列表（标题搜索 + `status` 筛选 + 分页）、导入（桌面壳 `selectImportFile()` → `POST /documents/import`；409 `STATE_CONFLICT` 时用 `GET /documents?sha256=` 取 typed 列表再弹「复用 / 新建 / 取消」）、检索测试（`GET /search` 展示 `mode/tookMs/total`，命中片段按纯文本高亮，「查看来源」定位到该资料的分块预览）、索引状态与重建（`GET /search/index`、`POST /documents/reindex` 二次确认、`fts5Available=false` 显式降级提示）、详情 + 分块分页预览、删除（强确认） |
| `/about` | 关于 | 版本、技术栈、契约版本、页面清单对照 |
| `/:pathMatch(.*)*` | 404 | 统一的「找不到该页面」 |

**所有列表页**都处理 `loading` / `empty` / `error` / `retryable` 四种状态（统一走 `StatePanel.vue`）。
**所有破坏性操作**都有确认框；恢复备份使用「输入指定文本才可继续」的强确认。
**所有表单**都有字段级校验；后端 `VALIDATION_ERROR` 的 `details` 会回填到对应字段。

---

## 6. 设计令牌

`src/styles/tokens.css` 定义一套 CSS 变量：品牌色阶、中性色阶、语义色、间距（4/8/12/16/20/24/32/40/48）、
圆角（2/4/8/12/16/pill）、字号（12/13/14/15/18/22/28）、行高、字重、阴影、布局尺寸。
同时把 Element Plus 的主题变量（`--el-color-primary*`、`--el-border-radius-*`、`--el-font-size-base` 等）
映射到同一套令牌，保证主题色统一。未做深色模式，仅保证浅色下对比度可读。

---

## 7. 与 Java 实现的对齐状态

> **本节已于 Java 业务服务实现完成后逐条核对。**
> 核对依据：`services/java-api/src/main/java/com/aia/answer/domain/*.java`（DTO）、
> `.../web/*Controller.java`（路由与状态码），并在 `127.0.0.1:18765` 的**运行中服务**上实测响应体。
> 注意：`docs/api/api-contract.md` 目前**尚未回填**这些实现细节（AI 评分路径、日志接口、复习记录字段等），
> 建议后续把实现里已稳定的形状补回契约文档，避免文档与实现再次漂移。

### 7.1 已对齐（原假设已按真实实现修正）

> **契约 §6 已于 v1.1 冻结为「文件驱动 + 热加载」**（配置源 `<UserData>/config/setting.json`），
> 下表第 8、30 条为本次（v1.1）改造后的前端实现。
> 第 30 条的**请求体/响应体形状已按 `services/java-api` 的 `LlmDtos` / `LlmProfileController`
> 逐字段代码核对**（只读代码，未调用接口；服务未启动、新接口未做运行期核对），
> 详见 §7.2 第 9 条与 §8.3。

| # | 项目 | 现在的前端实现 | 核对方式 |
| --- | --- | --- | --- |
| 1 | **AI 主观题评分（原路径不存在，已删除）** | 三步闭环：`POST /api/v1/attempts/{id}/ai-grade`（无 body → `{ taskIds, pendingCount }`）→ 轮询 `GET /api/v1/tasks/{taskId}` → `POST /api/v1/attempts/{id}/answers/{snapshotQuestionId}/grade`，body `{ score, note? }` → `AttemptResult` | 实测：enqueue 返回 `{"taskIds":["task_01M4…"],"pendingCount":1}`；手工评分返回 `totalScore=8.0/10.0`、`pendingReviewCount: 1 → 0`。**已删除** `POST /api/v1/ai/questions/{attemptId}/answers/{snapshotQuestionId}/grade` |
| 2 | AI 只给建议分、不直接生效 | 结果页从 `gradingDetail.suggestedScore / confidence / model / note` 读取建议分并展示，标注「尚未计入总分，需人工确认」，再弹「人工确认分数」对话框提交 | 代码核对：`TaskService#executeAiGrade` 写入 `gradingDetail.suggestedScore` 且保持 `gradingStatus=pending_review` |
| 3 | 题目讲解仍有效 | `POST /api/v1/ai/questions/{id}/explain`，body `{ userAnswer?, profileId? }`（`src/api/attempts.ts#explainQuestion`） | 代码核对 `AiController#explain` |
| 4 | `POST /api/v1/system/seed-demo` | `{ questionsCreated, papersCreated, skipped }` | 实测：`{"questionsCreated":0,"papersCreated":0,"skipped":true}` |
| 5 | 日志接口 | `GET /api/v1/system/logs` → `[{ fileName, sizeBytes, modifiedAt }]`；内容走 **`GET /api/v1/system/logs/tail?file=`** → `{ fileName, lines }` | 实测两个接口的真实响应 |
| 6 | 导入确认 | `{ created, updated, unchanged, configRevision }` | 代码核对 `ImportConfirmResponse` |
| 7 | 导入预览的 `summary` | 实际是 `ValidationSummary`（`profileCount` + `providers`）**而非**按 action 的计数 → 前端从 `changes[]` 自行汇总 create/update/unchanged/conflict（`countImportChanges`） | 代码核对 `ImportPreviewResponse` + `LlmProfileService#previewImport` |
| 8 | **配置来源与热加载状态显式展示（v1.1，取代原「凭据后端」条）** | LLM 设置页顶部展示 `configSource`（固定 `setting.json`）、`configPath`、`configRevision`、`configUpdatedAt`，并用 `ElAlert` 逐条列出 `warnings[]`（标题形如 `message（path）`，描述为错误码）并说明「当前仍在使用上一份有效配置，服务未中断」；「重新加载配置」→ `POST /settings/llm-profiles/reload`，`400 CONFIG_FILE_INVALID` 时展示 `details[].path` 明细 + 配置文件绝对路径。原 `credentialConfigured` / `credentialBackend` / `apiKeyRef` 引用与文案已**全部删除**（含 `types/system.ts` 的 `CREDENTIAL_BACKEND_LABELS`、`isSecureCredentialBackend`） | 按契约 §6/§8 v1.1 冻结文本实现；**未做运行期核对**（后端改造进行中） |
| 9 | 切换默认档案 | `PUT /settings/llm-profiles/default`：`profileId` 为具体值时**不带** `disableAi`；为 `null` 时**必带** `disableAi: true` | 代码核对 `LlmProfileService#setDefault` |
| 10 | 测试连接 | `{ ok, category, latencyMs, httpStatus, modelEcho, message, suggestion }`，`httpStatus/modelEcho/suggestion` 均可为 `null` | 代码核对 `TestConnectionResponse` |
| 11 | 复习记录字段 | `{ id, result, note, intervalDaysBefore, intervalDaysAfter, nextReviewAt, durationSeconds, reviewedAt }`；时间线展示「复习间隔：N 天 → M 天」+ 下次复习时间（无 `statusAfter` / `nextReviewAtAfter`） | 实测 `GET /wrong-questions/{id}` 六条真实记录 |
| 12 | 错题版本差异 | 直接用 `WrongQuestion.currentVersionNo` 与 `wrongVersionNo` 判断，**已删除**额外的 `GET /questions/{id}` 请求 | 实测两字段均返回（当前值均为 1） |
| 13 | 版本列表 | `[{ id, versionNo, createdAt, changeNote }]`（`QuestionVersionBrief`） | 代码核对 `QuestionController#versions` |
| 14 | AI 状态 | 统一为 `aiService: { available, baseUrl, latencyMs, message }`；`fts5Available` / `contractVersion` 只在 `/meta`，改由 `runtime.meta` 提供 | 实测 `/meta` 与 `/system/info` |
| 15 | 结果页统计 | 直接使用服务端 `correctCount / wrongCount / unansweredCount / pendingReviewCount`，前端不再自行计算 | 实测：`0 / 1 / 0 / 1` |
| 16 | 错题状态变更返回 | `PATCH /wrong-questions/{id}` 返回 `WrongQuestionDetail { question, reviews[] }`，store 与页面均按 `detail.question` 读取 | 代码核对 `WrongQuestionController#patchStatus` |
| 17 | 备份只读校验 | 新增「校验」按钮调用 `POST /api/v1/system/backups/verify?fileName=` → `{ ok, integrityCheck, foreignKeyCheck, schemaVersion, databaseSizeBytes }` | 代码核对 `SystemController#verifyBackup` |
| 18 | `sort` 白名单 | questions：`updatedAt\|-updatedAt\|createdAt\|-createdAt`（默认 `-updatedAt`）；wrong-questions：`-updatedAt\|updatedAt\|nextReviewAt\|lastWrongAt\|wrongCount`（默认 `-updatedAt`） | **实测**：`sort=bogus` → 400 `VALIDATION_ERROR`；三个错题排序值均 200 |
| 19 | 201 状态码 | 建题 / 建卷 / 开始作答 / 建 LLM 档案均为 HTTP 201，客户端按包络解析（不依赖 200） | 实测 `POST /attempts` → **201** |
| 20 | 幂等键 | 提交作答、提交复习、发布试卷、创建 attempt/练习、删除等写操作均带 `Idempotency-Key`；客户端识别 `Idempotent-Replay: true` 并提示「已重放首次结果」 | **实测**：同键重复提交返回头 `Idempotent-Replay: true` 且重放首次结果 |
| 21 | 未提交不泄露答案 | 答题页只读 `questions[]` 的题干/选项，作答一律取自 `answers[]`；`questions[].answer` / `explanation` 声明为 `QuestionAnswer \| null` / `string \| null` | **实测**：新 attempt 的 `questions[0].answer=null`、`explanation=null` |
| 22 | 删除题目 | `DELETE /questions/{id}` → `{ deleted: true, id }`；仅从未发布的草稿可删，否则 409 | 代码核对 `QuestionController#delete` |
| 23 | `POST /attempts` 与 wrong_practice | 均返回 `AttemptSheet`；`AttemptSummary` 现含 `paperSnapshotId`、`mode` | 实测列表与新建响应 |
| 24 | AI 评分轮询策略 | 任务在 AI 不可用时会停在 `retry_wait`（退避重试）。前端轮询预算约 18 秒（首轮 1.2s、其后 2.5s × 7），超预算即停止阻塞并提示「任务仍在后台重试，可稍后刷新或直接手动评分」；`LLM_UNAVAILABLE` / `CREDENTIAL_MISSING` 有专门文案 | 实测：任务进入 `retry_wait`（`lastErrorCode=LLM_UNAVAILABLE`）——本机 Ollama 未运行，界面不会卡住 |
| 25 | **资料库（阶段 6）接口路径与状态码** | 严格按契约 §11.2：`GET /documents`（`page/size/q/status/sha256`）、`POST /documents/import`（body `{sourcePath, sha256, title?, onDuplicate}`，**HTTP 200** + `DocumentSummary`，不是 201）、`GET /documents/{id}`、`GET /documents/{id}/chunks`、`DELETE /documents/{id}`、`POST /documents/reindex`、`GET /search/index`、`GET /search?q=&limit=`；`search` 响应按 `{query, mode, tookMs, total, results[]}` 解析 | **实测**（`services/java-api` 的 `DocumentController` + `DocumentService`，连接隔离数据目录的临时实例 `127.0.0.1:18765`）：导入返回 200 + `status=indexed`、`indexVersion=1`、`parserVersion=text-v1+utf8`；检索 `mode=fts5`（3 字以上）/ `keyword`（2 字）；`snippet` 带 `[[ ]]`；分块 `pageNo=null`。完整记录见 §8.2 |
| 26 | **重复导入：409 → `GET /documents?sha256=` 取 typed 列表再让用户选择** | `POST /documents/import` 命中重复且 `onDuplicate='reject'` 时返回 409 `STATE_CONFLICT`，此时**再调** `GET /documents?sha256=<hash>&page=1&size=20` 取回 `DocumentSummary[]`：复用 → 直接用列表里的 `id`（`getDocument(id)` 打开详情，**不再发 import**）；新建 → 带 `onDuplicate='new'` 重新 import；取消 → 关闭对话框。`error.details` 里的 `duplicates` 只作防御性兼容展示（Java 不发这个形状） | **实测** 409 响应体：`{"error":{"code":"STATE_CONFLICT","message":"该文件已导入过：三次握手讲义","details":[{"field":"sourcePath","message":"文件内容与已有资料相同（sha256 一致），请选择复用或新建"}]}}` —— details 里**没有**结构化 duplicates；`GET /documents?sha256=` 返回该资料的 typed 记录（`id`/`title`/`createdAt` 可用） |
| 27 | 资料类错误码 | `types/common.ts` 补齐 §0.3 的 `DOCUMENT_FORMAT_UNSUPPORTED` / `DOCUMENT_TOO_LARGE` / `DOCUMENT_PARSE_FAILED` / `DOCUMENT_INDEX_FAILED`（此前遗漏，页面需要按码区分文案），并在 `utils/error.ts` 增加对应提示 | **实测**：`.pdf` → 400 `DOCUMENT_FORMAT_UNSUPPORTED`；坏编码 `.txt` → 200 + `status=failed`、`errorCode=DOCUMENT_PARSE_FAILED`；sha256 不一致 → 400 `VALIDATION_ERROR`（`details[0].field=sourcePath`）；`status=bogus` → 400 `VALIDATION_ERROR` |
| 28 | reindex 响应形状与可空字段 | `POST /documents/reindex` 返回 **`{ indexState, chunks, failures }`**（`indexState` 是嵌套字段，不是平铺的 IndexState），store 按 `result.indexState` 回填；`DocumentSummary.indexVersion` / `parserVersion` 声明为可空（`number \| null` / `string \| null`），页面用 `versionLabel()` 渲染成 `—` | **实测**：`{"indexState":{...,"activeVersion":2},"chunks":2,"failures":[]}`（重建后 activeVersion 由 1 → 2）；坏编码资料返回 `indexVersion=null`、`parserVersion=null` |
| 29 | 幂等键 | `import` 与 `reindex` 带 `Idempotency-Key`；`DELETE /documents/{id}` **不带**（Java `IdempotencyFilter` 只覆盖 POST/PUT/PATCH，契约 §0.4） | 代码核对 `services/java-api/.../web/IdempotencyFilter.java:47-48` |
| 30 | **LLM 配置 v1.1：`apiKey` 随 profile 保存（三态）+ 两种导出（v1.1 新增）** | 删除「绑定/解绑凭据」整条流程（不再调用 `POST/DELETE /settings/llm-profiles/{id}/credential`，`bindLlmCredential`/`unbindLlmCredential` 已移除）。`ProfileInput.apiKey` 三态：**非空=替换**、**留空=省略该字段（保持原值）**、**「清除密钥」按钮=显式发 `apiKey: ""`（清除）**；表单永不回填明文，placeholder 为 `******`（已配置）或「未配置」。导出：默认 `exportLlmConfig(false)` → `{ includeSecrets: false }` 导出与 setting.json 同构的完整配置（`apiKey` 为 `null`），文件名用响应的 `suggestedFileName`（缺省 `setting.json`）；「导出含密钥（高风险）」→ `confirmDestructive` 强确认（需输入「导出含密钥」）后 `{ includeSecrets: true }`，成功提示「含明文密钥，请自行妥善保管」并显式展示 `containsSecrets: true`。**形状（代码核对 `LlmDtos`，非运行期实测）**：新建 `POST` 体为 `{ profile: ProfileInput }` → `201 ProfileView`；**更新 `PUT` 体是裸 `ProfileInput`（不包 `profile`）**；`DELETE` / `PUT /default` 返回与列表同构的 `ProfileListResponse`；`reload` → `{ configRevision, warnings }`；`export` → `{ config, includeSecrets, containsSecrets, suggestedFileName }`；`configRevision` 是 **SHA-256 字符串**（文件不存在时为 `null`，UI 截断展示 + `title` 放全值）；`warnings[]` 元素 `{ code, message, path }`（`path` 形如 `$.llm.profiles[0].provider`）。类型层 `ProfileView` = `{ id, name, provider, baseUrl, model, apiKeyMasked, apiKeyConfigured, timeoutSeconds, connectTimeoutSeconds, allowLocalNetwork, parameters, isDefault }`；`ProfileInput` = 同字段但以 `apiKey` 取代 `apiKeyMasked`/`apiKeyConfigured`；列表响应含 `configSource/configPath/configRevision/configUpdatedAt/warnings[]`；`ApiErrorCode` 补 `CONFIG_FILE_INVALID`，`CREDENTIAL_MISSING` 文案改为「该档案未配置 apiKey，请在 LLM 设置里填写」 | **代码核对** `services/java-api` 的 `domain/LlmDtos.java`、`web/LlmProfileController.java`、`domain/SystemDtos.java`、`service/setting/SettingValidator.java`；**未做运行期核对**（服务未启动） |

### 7.2 仍是真实差异 / 非契约问题 / 待运行期核对（保留）

1. **`docs/api/api-contract.md` 未回填实现细节**
   契约 §4 仍没有 `ai-grade`、§8 仍是 `GET /system/logs?file=` 与旧的 `seed-demo` 形状，
   §5 仍未给 `wrong_question_review` 字段。前端以 Java 实现为准；**建议后续补回契约**，
   否则下一个渲染层/客户端会再次按旧契约实现。

2. **`window.__APP_RUNTIME__` 是任务约定的注入方式，契约 §10 只定义了 `window.app.getRuntime()`**
   渲染层两条都支持：优先同步读 `window.__APP_RUNTIME__`，缺失时回退 `await window.app.getRuntime()`，
   再回退 dev shim（`src/api/runtime.ts`）。

3. **CSP 需放行本机地址**
   `index.html` 的 `connect-src` 必须包含 `'self' http://127.0.0.1:* http://localhost:*`，
   否则在 `file://`（Electron）来源下无法访问本机 Java 服务。若桌面壳注入更严格的 CSP，以壳为准。

4. **Element Plus 为全量引入**
   `main.ts` 用 `app.use(ElementPlus)` 全量注册（含 `element-plus/dist/index.css`）。
   最近一次构建（阶段 6）产物：`index.html` 0.95 kB、CSS 394.53 kB（gzip 53.41 kB）、
   vue chunk 112.34 kB（gzip 43.80 kB）、index chunk 202.44 kB（gzip 65.40 kB）、
   element-plus chunk 917.53 kB（gzip 295.95 kB）。
   按需引入需新增 `unplugin-vue-components` / `unplugin-auto-import` 构建期依赖，
   与「不引入除冻结列表之外的运行时依赖」的技术选型冲突，故未引入。

5. **题目难度取值 1-5**
   `SaveRequest` 用 `@Min(1) @Max(5)` 约束，但契约 §2 未写明范围。前端按 1-5 呈现文案。

6. **`pnpm-workspace.yaml` 的 `allowBuilds`**
   pnpm 11 要求显式批准 `esbuild` / `vue-demi` 构建脚本，否则 `pnpm install` 退出码 1。
   这是子目录内的单包 workspace 配置，非业务代码。

7. **`runtime.pythonState` 是派生值**
   渲染层不直接探测 Python：该状态由 Java `/meta`、`/system/info` 的 `aiService.available`
   以及桌面壳 `onServiceStatus` 事件推导。UI 文案已统一为「AI 服务 / AI 能力」而非「Python AI」。

8. **资料库（§11）已按 Java 实现核对，但未做浏览器内点击验收**
   `services/java-api` 的 `DocumentController` / `DocumentService` / `V2__resource_library.sql` + `V3__documents_fts.sql`
   已落地，前端按其真实形状对齐（见 §8.2 实测）。仍未做的部分：
   - **未做**浏览器/Electron 内的人工点击验收（无 Playwright，且导入依赖桌面壳 `selectImportFile()`）；
   - 桌面壳 `selectImportFile()` 返回的 `sha256` 与服务端重算值的一致性只能靠「服务端校验失败即 400」间接验证，
     未在 Electron 内实测这条链路；
   - 分块预览的「查看来源」页码按「`chunkIndex` 升序分页」推算（Java 的 `pageChunks` 正是
     `ORDER BY chunk_index LIMIT/OFFSET`，与推算一致，但契约 §11.2 未写明该排序）。

9. **LLM 配置新接口（契约 §6 v1.1）未做运行期核对（只读了 Java 代码）**
   前端已按 §6 冻结文本 + `contracts/json-schema/setting.schema.json` 完成改造（见 §7.1 第 8、30 条）。
   `services/java-api` 侧的新实现已经存在（`service/setting/ConfigService`、`SettingStore`、`SettingValidator`、
   `SettingFileWatcher`、`LlmProfileService`、`web/LlmProfileController`），因此本节的形状清单**已逐字段代码核对**，
   但**没有启动服务、没有调用过任何新接口**，下列行为仍未在运行期验证：
   - `apiKey` **三态语义**的真实行为（`JsonNode` 承载，代码路径是 `applyApiKey`：`null` 引用不碰、`NullNode`/空串走
     `remove("apiKey")`、非空 `trim` 后写入；**未调用验证**，尤其是「省略字段」是否真的不覆盖文件里的密钥）；
   - `POST /settings/llm-profiles/{id}/credential` 与 `DELETE` 同路径**在 Java 侧仍然存在**（语义已改为「只更新/清除该档案的 apiKey」）。
     前端按本任务要求**已完全不再调用**它们（清除密钥改走 `PUT` + `apiKey: ""`），因此这两条接口在前端视角是死代码，
     若后端将来删除它们不会影响本页；
   - `400 CONFIG_FILE_INVALID` 的 `ApiException` details 具体形状（前端同时兼容 `{path}` 与 `{field}`，`WARNING`/`ERROR` 的 `path`
     形如 `$.llm.profiles[0].provider`）；`warnings[].code` 还可能是 `CONFIG_JSON_INVALID` / `CONFIG_WARNING`，前端原样展示；
   - 热加载的真实时序（文件变化去抖 → 校验 → 替换缓存/保留上一份）与 `configUpdatedAt` 的含义（代码注释为「内存快照的加载时刻」）；
   - `reload` 成功/失败的响应与 `configRevision`（SHA-256 字符串，文件不存在时为 `null`）；
   - 导入预览/确认的新语义（`previewId` 过期返回 `STATE_CONFLICT`；预览只在内存、不落表）；
   - **含密钥导出的真实响应**（`ExportResponse{config, includeSecrets, containsSecrets, suggestedFileName}` 的形状已核对，
     但 `config` 里 `apiKey` 是否确实为明文、是否真的只在此分支出现，未实测）；
   - 未配置 apiKey 时 `CREDENTIAL_MISSING` 的真实触发点与文案（代码在 `LlmProfileService` 抛 `ApiException`，
     文案「档案 X 未配置 apiKey，请在 setting.json 中填写后重试」）。
   上述任一项在运行期核对后与实现不符时，以 **Java 实现为准**并回填契约 §6。

10. **`temperature` 只能放在 `parameters` 里（前后端一致的取舍）**
   `setting.schema.json` 把 `temperature` 定义为 **profile 级**字段，但 Java 的 API DTO
   （`LlmDtos.ProfileView` / `ProfileInput`）**没有**该字段，只有 `parameters: Map<String,Object>`；
   服务端 `SettingValidator` 对 profile 走 `additionalProperties=false`，多带一个 profile 级 `temperature`
   会被判成 schema 违规。因此前端把 `temperature` 作为 `parameters.temperature` 读写
   （与 `topP` / `maxTokens` / `stream` 同段）；`LlmProfileService.applyProfileFields` 会整体替换 `parameters`，
   不会丢字段。若后续 Java 把 `temperature` 提升为 profile 级字段，需要同步改
   `types/llm.ts`、`utils/llmConfig.ts` 与编辑表单。

---

## 8. 已实际执行的验证

### 8.1 构建与静态检查

| 命令 | 结果 |
| --- | --- |
| `pnpm install` | 成功（首次因 `ERR_PNPM_IGNORED_BUILDS` 退出 1 → `pnpm approve-builds --all` → 再次 `pnpm install` 退出 0） |
| `pnpm exec vue-tsc --noEmit` | **exit 0**，0 错误 |
| `pnpm build:local-tmp` | **exit 0**（`vue-tsc --noEmit` + `vite build` 均成功），`✓ built in 4.29s`，命令整体耗时 9.10s，产物：`index.html` 0.95 kB / CSS 392.20 kB (gzip 53.17) / vue 112.34 kB (gzip 43.80) / index 175.78 kB (gzip 57.53) / element-plus 917.53 kB (gzip 295.95) |
| `pnpm build` | 直接执行时因运行环境无法删除 `%TEMP%` 下 esbuild 临时文件而失败（详见 §2，属于主机 TEMP 权限，非代码问题）；改用等价的 `build:local-tmp` 通过 |
| `pnpm preview` | 启动成功，`GET /`、`/index.html`、主 JS、CSS 全部 200 |
| `pnpm dev` | 启动成功；`GET /` 200；`GET /__app_runtime` 缺端点文件时 503、命中真实端点文件时 200 且 `javaBaseUrl`/`sessionToken` 与文件**逐字段一致**（实测 True/True） |
| 经 dev server 逐个拉取 `src/**` 全部 59 个 `.ts`/`.vue` 模块 | 全部 200，无转换错误 |
| **阶段 6** `cd apps\renderer; & .\node_modules\.bin\vue-tsc.CMD --noEmit` | **exit 0**（0 错误，含新增 `types/document.ts`、`api/documents.ts`、`stores/documents.ts`、改写后的 `pages/DocumentsPage.vue`；改为「409 → `GET /documents?sha256=`」后重跑仍 **exit 0**） |
| **阶段 6** `node scripts\with-local-tmp.mjs build`（= `pnpm build:local-tmp`） | **exit 1，未能执行**：pnpm 11 在 `runDepsStatusCheck` 阶段判定 `node_modules` 需要重建（`node_modules/.modules.yaml` 记录的 `virtualStoreDir` 仍是改名前的 `E:\SXXM\AI智能答题(学习伙伴 Agent)\apps\renderer\node_modules\.pnpm`），要求 `pnpm install` 清空并重装；非 TTY 下报 `ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY`。为避免破坏现有 `node_modules`，未设置 `CI=true` 触发清空重装 |
| **阶段 6** 等价构建（`TMP/TEMP/TMPDIR` 指向 `apps/renderer/.tmp-build` 后直接调可执行文件）：`& .\node_modules\.bin\vue-tsc.CMD --noEmit` → `& .\node_modules\.bin\vite.CMD build` | **两条均 exit 0**（重复导入改造后复跑：`✓ 1700 modules transformed`、`✓ built in 4.56s`）；最终产物：`index.html` 0.95 kB (gzip 0.55) / CSS 394.60 kB (gzip 53.41) / vue 112.34 kB (gzip 43.80) / index 204.65 kB (gzip 66.10) / element-plus 917.53 kB (gzip 295.95) |
| **阶段 LLM 配置 v1.1** `cd apps\renderer; & .\node_modules\.bin\vue-tsc.CMD --noEmit` | **exit 0**（0 错误；覆盖改写后的 `types/llm.ts`、`types/common.ts`、`types/system.ts`、`utils/llmConfig.ts`、`utils/error.ts`、`api/llmProfiles.ts`、`stores/llmProfiles.ts`、`stores/system.ts`、`pages/LlmSettingsPage.vue`、`pages/SystemSettingsPage.vue`） |
| **阶段 LLM 配置 v1.1** 等价构建（`TMP/TEMP/TMPDIR` 指向 `apps/renderer/.tmp-build` 后直接调 `node_modules/.bin` 下的可执行文件）：`& .\node_modules\.bin\vue-tsc.CMD --noEmit` → `& .\node_modules\.bin\vite.CMD build` | **两条均 exit 0**（`vue-tsc` 无输出 = 0 错误；`✓ 1699 modules transformed`、`✓ built in 4.68s`）；产物：`dist/index.html` 0.95 kB (gzip 0.55) / `dist/assets/index-CmRJ1eBy.css` 394.94 kB (gzip 53.45) / `dist/assets/vue-DLT5ubW1.js` 112.34 kB (gzip 43.80) / `dist/assets/index-DU3vBNkm.js` 209.99 kB (gzip 68.61) / `dist/assets/element-plus-CfVnN07a.js` 917.53 kB (gzip 295.95)。**未执行 `pnpm install`**（会清空重装 `node_modules`） |

### 8.2 针对运行中 Java 服务（`127.0.0.1:18765`）的实测

> ⚠️ 本节是 **LLM 配置 v1.1 改造之前**的实测记录，其中 `GET /api/v1/system/info` 的
> `credentialBackend` 与 `GET /api/v1/settings/llm-profiles` 的 `credentialConfigured` 字段
> 已随 v1.1 一起消失（改为 `configSource`/`configPath`、`apiKeyMasked`/`apiKeyConfigured`）。
> 这两行仅作为「旧实现的实测存档」保留，**不代表当前接口行为**；新接口尚未核对，见 §7.2 第 9 条与 §8.3。

用 `.runtime/java-endpoint.json` 里的 token 直接打真实接口，核对响应体与 `src/types/**` 是否一致：

| 接口 | 实测结果 |
| --- | --- |
| `GET /health` | 200，`{status, service, version, schemaVersion, uptimeSeconds}` |
| `GET /api/v1/meta` | 200，含 `contractVersion`、`fts5Available: true`、`aiService{available,baseUrl,latencyMs,message}` |
| `GET /api/v1/system/info` | 200，含 `backupCount`、12 条 `backups[]`（`kind: manual/auto/unknown`）、`credentialBackend: windows-dpapi` |
| `GET /api/v1/system/logs` | 200，`[{fileName,sizeBytes,modifiedAt}]` |
| `GET /api/v1/settings/llm-profiles` | 200，`{profiles[{…,credentialConfigured,isDefault,allowLocalNetwork,parameters}],defaultProfileId,configRevision,credentialBackend}` |
| `GET /api/v1/questions?sort=-updatedAt` | 200，`QuestionSummary` 全字段一致 |
| `GET /api/v1/questions?sort=bogus` | **400** `VALIDATION_ERROR`（`details[0].field=sort`）→ 前端本地也只提供白名单内的值 |
| `GET /api/v1/wrong-questions?sort=-updatedAt\|nextReviewAt\|wrongCount` | 三个值均 200，含 `currentVersionNo`、`wrongVersionNo`、`knowledgeTags` |
| `GET /api/v1/wrong-questions/{id}` | 200，`{question, reviews[]}`，复习记录字段与 TS 类型完全一致 |
| `POST /api/v1/attempts` | **201**，`AttemptSheet`，`questions[0].answer=null`、`explanation=null`、`answers=[]` |
| `PUT /attempts/{id}/answers/{sqid}` | 200，`AttemptAnswer`，`gradingStatus: pending_review`、`gradingDetail` 含 `ruleVersion/questionType/expected/actual/note` |
| `POST /attempts/{id}/submit`（带幂等键） | 200，`AttemptResult` 带 `correctCount/wrongCount/unansweredCount/pendingReviewCount` |
| 同幂等键重复 submit | 200 + **响应头 `Idempotent-Replay: true`**，重放首次结果 |
| `POST /attempts/{id}/ai-grade` | 200，`{taskIds:["task_01M4…"],pendingCount:1}` |
| `GET /api/v1/tasks/{taskId}`（轮询） | 观察到 `running → retry_wait (lastErrorCode=LLM_UNAVAILABLE) → running(attemptCount=2)`；本机 Ollama 未运行，故任务未成功 —— 这正好验证了前端「不阻塞、提示后台重试」的处理 |
| `POST /attempts/{id}/answers/{sqid}/grade` | 200，`totalScore` 由 0 → **8.0**，`pendingReviewCount` 由 1 → **0**，`gradingMethod: manual`，`gradingDetail.manual: true` |
| `POST /api/v1/system/seed-demo` | 200，`{"questionsCreated":0,"papersCreated":0,"skipped":true}` |

### 8.2.1 阶段 6 资料库实测（隔离数据目录 + 临时实例 `127.0.0.1:18765`）

方式：用 `services/java-api/target/answer-java-api.jar`（含 §11 实现）以
`--aia.data-dir=<临时目录> --server.port=18765 --aia.endpoint-file=<临时文件>` 启动**独立实例**
（不触碰桌面壳使用的 `%APPDATA%\ai-answer-desktop` 数据目录），再用 `.runtime` 令牌直接打真实接口。
样本为自制 UTF-8 `.txt`（5 行中文，514 字节）。

| 步骤 | 实测结果 |
| --- | --- |
| `POST /documents/import`（首次） | **200**（不是 201），`status=indexed`、`chunkCount=1`、`charCount=214`、`indexVersion=1`、`parserVersion=text-v1+utf8`、`sourceKind=text`、`originalFileName=sample-tcp.txt` |
| 同 `sha256` 再 `import`（`reject`） | **409** `STATE_CONFLICT`，`details` 只有 `{field:"sourcePath", message:"文件内容与已有资料相同（sha256 一致），请选择复用或新建"}` —— 无结构化 `duplicates`（这正是前端改用 sha256 反查的原因） |
| `GET /documents?sha256=<hash>&page=1&size=20` | 200，`total=1`，`items[0]` 为完整 `DocumentSummary`（`id`/`title`/`status`/`createdAt`/`chunkCount`/`indexVersion` 齐全，UTF-8 中文标题正确） |
| `import` 带 `onDuplicate=new` | 200，返回**新** `documentId`；同 sha256 资料数变为 2 |
| `import` 带 `onDuplicate=reuse`（前端未采用） | 200，直接返回已有资料（与首次导入同一 `id`） |
| `import` 传错 `sha256` | 400 `VALIDATION_ERROR`，`details[0].field=sourcePath`（防 TOCTOU） |
| `import` 一个 `.pdf` | 400 `DOCUMENT_FORMAT_UNSUPPORTED`（首期仅 `.txt` / `.md`） |
| `import` 坏编码 `.txt`（既非 UTF-8 也非 GB18030） | 200 + `status=failed`、`errorCode=DOCUMENT_PARSE_FAILED`、`indexVersion=null`、`parserVersion=null`（前端已按可空声明） |
| `GET /documents/{id}/chunks?page=1&size=3` | 200，分页包络齐全；`items[0].pageNo=null`（页面显示「—」）、`contentHash` 非空 |
| `GET /search?q=三次握手&limit=5` | 200，`mode=fts5`、`tookMs=2`、`total=2`；`results[0]` 带 `documentId`/`chunkId`/`pageNo=null`/`chunkIndex=0`/`score=1.375E-06`/`matchMode=fts5`；`snippet` 内含 `[[三次握手]]` |
| `GET /search?q=握手&limit=5`（2 字符） | 200，`mode=keyword`、`matchMode=keyword`（降级路径符合 §11.4） |
| `GET /search?q=<不存在的词>` | 200，`mode=keyword`、`total=0`（前端走空态） |
| `GET /search/index` | 200，`indexName=document_fts`、`activeVersion=1`、`buildingVersion=null`、`fts5Available=true`、`updatedAt` 非空、`documentCount=2`、`chunkCount=2` |
| `POST /documents/reindex` | 200，顶层字段 `[indexState, chunks, failures]`（**嵌套**），`chunks=2`、`failures=[]`、`indexState.activeVersion` 由 1 → **2** |
| 重建后再取分块 | 200，`total=1`、`pageNo=null`（旧版本分块已被清理，新版本可读） |
| `DELETE /documents/{id}`（同 sha256 两份） | 200，先删的一份 `fileRemoved=false`，最后一份 `fileRemoved=true`（符合 §11.5.4 的引用计数补偿） |
| 删除后 `GET /documents?sha256=` | 200，`total=0` |
| `GET /documents?status=bogus` | 400 `VALIDATION_ERROR`，`details[0].field=status` |

> 说明：实测用 Windows PowerShell 5.1 发起请求，控制台里中文一度显示为乱码（PS 5.1 的响应解码），
> 第二组改用 .NET `HttpClient` 按 UTF-8 解码字节后中文正确（如标题 `三次握手讲义`）；
> 浏览器 `fetch` 按 JSON 规范以 UTF-8 解码，不受该问题影响。

### 8.3 未执行

- **未做**浏览器内的人工点击验收（无 Playwright / 无 E2E 用例）；
- **未做**逐页面截图比对与视觉走查；
- **未验证** AI 评分成功路径的端到端结果（本机 Ollama 未运行，任务停在 `retry_wait`）；
  但「排队 → 轮询 → 人工确认 → 总分重算」的四个接口响应形状都已实测。
- **未做**浏览器/Electron 内的人工点击验收（无 Playwright / 无 E2E 用例），因此
  「重复文件对话框点『复用』/『新建一份』的真实交互路径」只在**接口层**验证过（见 §8.2.1），
  没有在界面里点过；资料导入在浏览器调试模式下不可用（依赖桌面壳 `selectImportFile()`）。
- **未执行** `pnpm install` / `pnpm build:local-tmp`（见 §8.1 的 pnpm 依赖状态检查失败说明），
  构建改用 `node_modules/.bin` 下的 `vue-tsc` / `vite` 直接执行。

#### 8.3.1 LLM 配置 v1.1（文件驱动 + 热加载）本轮明确未验证的项

- **LLM 配置新接口未做运行期核对**：Java 侧新实现已在代码库中（`ConfigService`/`SettingValidator`/`LlmProfileService`/
  `LlmProfileController`），前端按其 DTO **逐字段代码核对**，但**没有启动服务、没有调用过任何新接口**：
  `/settings/llm-profiles` 系列的请求体/响应体（`configSource`/`configPath`/`warnings`、`apiKey` 三态、`reload`、
  `import/preview|confirm`、`export`）只做了**静态实现 + 类型检查 + 构建**（详见 §7.2 第 9 条清单）。
- **含密钥导出的真实响应未核对**：`ExportResponse` 的字段形状已核对，但 `includeSecrets: true` 时
  `config.llm.profiles[].apiKey` 是否确实为明文、`containsSecrets` 是否恒为 `true`、审计是否落盘，均未实测；
  前端只用强确认 + 文案兜底，即使后端忽略 `includeSecrets` 也不会在前端泄露明文（前端不做任何解密或回填）。
- **`apiKey` 三态的真实行为未验证**：只按契约实现「省略 / `""` / 非空」三种请求体，
  未验证服务端 `applyApiKey` 是否真的按此语义处理（例如省略字段是否保持原值、`""` 是否真的从文件中移除）。
- **热加载未验证**：未手工改动真实 `setting.json` 观察 `warnings[]`、`configUpdatedAt`、
  `configRevision` 的变化，也未验证「校验失败保留上一份有效配置」的实际表现。
- **未做**浏览器/Electron 内的人工点击验收：新建/编辑/清除密钥/两种导出/重新加载这五条交互路径
  都没有在界面里点过（无 Playwright）；`SystemSettingsPage` 的「在文件管理器中定位」依赖桌面壳
  `openPath`，其「选中文件」能力未经实测（未实现时前端退化为打开所在目录，只在代码层覆盖）。
- **未做**服务端 `test-connection` 的实际调用：本页只走 `{ profileId }` 形式（密钥由服务端从 setting.json 取），
  `{ profile }` 内联形式（`stores/llmProfiles.ts#testConnection`）在本页没有入口，未经任何验证。
