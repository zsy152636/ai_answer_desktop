# AI 智能答题系统（学习伙伴 Agent）· 第一版

个人单机使用的智能答题桌面应用。业务数据全部留在本机，AI 能力可接本地模型（默认 Ollama
`qwen3:8b`）或任意 OpenAI 兼容端点。

- **桌面壳**：Electron（主进程编排子进程 + 严格安全基线）
- **界面**：Vue 3 + TypeScript + Vite + Element Plus
- **业务服务**：Java Spring Boot 3（SQLite 唯一写入方、正式业务规则权威）
- **AI 服务**：Python FastAPI（模型适配、提示词、解析；只监听 loopback）
- **数据库**：SQLite（WAL + 外键 + 版本化迁移）

依据文档：`开发文档.docx`（v1.0）、`docs/api/api-contract.md`（接口契约）、
`docs/architecture/decisions.md`（架构决策记录）。

---

## 1. 第一版交付范围

开发文档把工程分成阶段 0-8。**第一版完整交付阶段 1-4，并附带阶段 5 的配置与真实模型调用闭环**：

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 1 工程骨架 | 仓库结构、Electron preload 白名单、Java/Python health、一键启动 | ✅ |
| 2 数据基线 | 全量 DDL、迁移执行器、备份/恢复校验、题目版本与试卷快照 | ✅ |
| 3 核心答题 | 题库 CRUD、试卷构建与发布、作答保存、确定性判分、结果页 | ✅ |
| 4 错题闭环 | 错题去重、状态机、复习调度与记录、错题再练习 | ✅ |
| 5 LLM 配置 | 多 profile、JSON Schema 校验、导入预览/确认、脱敏导出（另设显式含密钥导出）、测试连接（真实模型）；配置为**文件驱动**（`<UserData>/config/setting.json` + 热加载），**不再使用 OS 凭据存储**，旧表数据首启自动迁移 | ✅（见 ADR-014，取代 ADR-004） |
| 6 本地检索/AI | 资料导入、文本分块、FTS5/关键词检索、引用来源、注入防护、资料库页面 | ✅（中文分词与迁移取舍见 ADR-013） |
| 7 试卷导入 | PDF/DOCX 解析、OCR、逐题校对 | ⛔ 第一版不做 |
| 8 稳定发布 | 打包签名、自动更新、诊断包 | ⛔ 第一版不做 |

任务表、租约、幂等键与重试基础设施在第一版即已落地（`async_task`），当前用于主观题 AI 评分。

---

## 2. 目录结构

```
.
├── apps/
│   ├── desktop/                     # Electron 主进程 + preload（子进程编排、安全边界）
│   └── renderer/                    # Vue 3 + TS 渲染层（14 个页面）
├── services/
│   ├── java-api/                    # Spring Boot 3 业务服务（SQLite 唯一写入方）
│   │   └── src/main/resources/db/migration/   # V*.sql 版本化迁移
│   └── python-ai/                   # FastAPI AI 服务（provider 适配、提示词、引用式解释与注入防护）
├── <UserData>/                      # 运行期数据目录（开发环境为 .devdata\java）
│   ├── config/setting.json          # v1.1 唯一 LLM 配置来源（文件驱动 + 热加载）
│   ├── database/app.db              # 业务库（LLM 配置不再读写此库）
│   └── backups/ logs/ runtime/ files/ index/ temp/
├── contracts/
│   ├── json-schema/setting.schema.json        # LLM 配置唯一 Schema 来源（v1.1，ADR-014）
│   ├── json-schema/llm-config.schema.json     # v1.0 历史 Schema（仅供旧配置导入识别）
│   └── openapi/                     # Java API 契约（OpenAPI）
├── docs/
│   ├── api/api-contract.md          # v1 接口契约（冻结）
│   └── architecture/decisions.md    # ADR：与设计文档的差异及理由
├── scripts/
│   ├── dev-start.ps1 / dev-stop.ps1 # 一键启动 / 停止
│   ├── smoke-test.ps1               # 端到端冒烟测试（103 项：阶段 1-5 共 79 + 阶段 6 共 24）
│   ├── db-check.ps1                 # 数据库只读巡检
│   ├── mvn.ps1                      # 解析 JDK 17 + Maven 并调用
│   └── fix-ps1-bom.ps1              # 给 .ps1 补 UTF-8 BOM（PowerShell 5.1 必需）
└── README.md
```

---

## 3. 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **17+** | Spring Boot 3 要求；本机 PATH 上的 JDK 8 不满足 |
| Maven | 3.9+ | 缺失时 `scripts/mvn.ps1` 会复用 `~/.m2/wrapper/dists` 里已下载的发行版 |
| Node.js | 20+ | 前端与 Electron |
| pnpm | 9+ | 前端与 Electron 依赖管理 |
| Python | 3.12 | AI 服务；用 `uv` 建虚拟环境最省事 |
| Ollama（可选） | 任意 | 默认 LLM 端点 `http://127.0.0.1:11434/v1`，模型 `qwen3:8b` |

`scripts/mvn.ps1` 会在 `$env:JAVA_HOME`、`~/.jdks`、`C:\Program Files\Java` 等位置自动寻找 JDK 17+，
找不到才报错；不会静默用 JDK 8 构建。

---

## 4. 快速开始

### 4.1 一次性准备

```powershell
# 1) AI 服务依赖（在 services/python-ai 下）
cd services\python-ai
uv venv --python 3.12 .venv
uv pip install --python .venv\Scripts\python.exe -r requirements.txt
cd ..\..

# 2) 前端依赖
cd apps\renderer
pnpm install
cd ..\..

# 3) Electron 依赖（首次会下载 Electron 二进制；国内网络可设镜像）
$env:ELECTRON_MIRROR='https://npmmirror.com/mirrors/electron/'
cd apps\desktop
pnpm install
cd ..\..
```

> 若 PowerShell 报「未对文件进行数字签名」，所有脚本都用
> `powershell -ExecutionPolicy Bypass -File scripts/xxx.ps1` 方式调用。

> **`apps/renderer` 与 `apps/desktop` 都必须用 pnpm 安装依赖。**
> pnpm 的 `node_modules` 是「符号链接 + 内容寻址存储」结构，`npm i` 无法解析它，
> 会先打一堆 ERESOLVE 警告，再以 `npm error Cannot read properties of null (reading 'matches')`
> 崩溃退出。两个工程都已加 `preinstall` 守卫：用 npm/yarn 安装会被立刻拦下并提示改用 pnpm。

### 4.2 一键启动后端（推荐先跑这个）

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev-start.ps1
```

它会：构建 Java 服务 → 启动 Python AI 服务 → 启动 Java 业务服务（固定端口 18765，便于调试）→
把端点与令牌写到 `.runtime/java-endpoint.json`。加 `-WithUI` 会同时启动前端开发服务器。

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-test.ps1   # 端到端冒烟测试
powershell -ExecutionPolicy Bypass -File scripts/db-check.ps1     # 数据库巡检
powershell -ExecutionPolicy Bypass -File scripts/dev-stop.ps1     # 停止
```

### 4.3 启动桌面应用

```powershell
# 先构建前端产物
cd apps\renderer
pnpm build          # 若所在机器的 %TEMP% 权限受限，用 pnpm build:local-tmp
cd ..\..

# 启动 Electron（主进程会自动拉起 Python 与 Java，随机端口，令牌只在内存中）
cd apps\desktop
pnpm start          # 等价于 pnpm build && node scripts/start.mjs
```

Electron 主进程的行为：以 `<userData>/runtime/` 存放端点文件；若无 `AIA_RENDERER_URL` 且
Vite 开发服务器（127.0.0.1:5173）可达则加载它，否则加载 `apps/renderer/dist/index.html`。

### 4.4 只用浏览器调试前端

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev-start.ps1
cd apps\renderer
pnpm dev
# 浏览器打开 http://127.0.0.1:5173
```

Vite 开发插件提供 `GET /__app_runtime`，从 `.runtime/java-endpoint.json` 读取端口与会话令牌
（可用 `AIA_ENDPOINT_FILE` 覆盖路径），因此浏览器里也能直连本机业务服务。

---

## 5. 验证与测试

| 层 | 位置 | 结果（v1.1 收尾实测，2026-10-11） |
| --- | --- | --- |
| Java 全量测试（单元 + 集成，单次 `mvn test`） | `services/java-api/src/test/` | **90 项通过**：`Tests run: 90, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS |
| ├─ LLM 配置文件驱动（v1.1 新增/重写） | `service/LlmProfileServiceTest` 21、`setting/SettingValidatorTest` 8、`setting/ConfigServiceTest` 7、`setting/SettingStoreTest` 6、`setting/LegacyLlmConfigMigratorTest` 5、`setting/legacy/LegacyCredentialReaderTest` 3、`setting/SettingFileWatcherTest` 3、`setting/LegacyMigrationWithCredentialReaderTest` 2、`service/LlmConfigFileDrivenPersistenceTest` 1 | 56 项通过 |
| └─ 既有主链路（判分/分块/迁移/集成） | `service/GradingServiceTest` 14、`service/search/TextChunkerTest` 6、`db/MigrationRunnerTest` 5、`ApiFlowIntegrationTest` 4、`DocumentFlowIntegrationTest` 5 | 34 项通过 |
| Python 测试（provider/超时/分类/解析/脱敏/鉴权/引用与注入防护） | `services/python-ai/tests/` | **177 项通过**（`177 passed, 1 warning`，1.51s） |
| 渲染层静态检查与构建 | `apps/renderer/` | `vue-tsc --noEmit` exit 0（无诊断）；`vite build` exit 0（1699 modules，4.25s） |
| 端到端冒烟（真实 HTTP + 真实模型） | `scripts/smoke-test.ps1` | 阶段 1-5 共 **79 项全量通过**；阶段 6 新增 **24 项**在真实服务上隔离运行全绿（合计 103）。v1.1 只同步了脚本的 LLM 段落、**未重跑**，原因见 §8 第 9/11 条 |

```powershell
powershell -ExecutionPolicy Bypass -File scripts/mvn.ps1 test          # Java 90 项
cd services\python-ai; .\.venv\Scripts\python.exe -m pytest -q          # Python 177 项
cd apps\renderer; .\node_modules\.bin\vue-tsc.CMD --noEmit              # 类型检查，要求 exit 0
cd apps\renderer; $env:TMP="$PWD\.tmp-build"; .\node_modules\.bin\vite.CMD build   # 构建，要求 exit 0
powershell -ExecutionPolicy Bypass -File scripts/smoke-test.ps1         # 端到端 103 项
```

冒烟测试覆盖：令牌鉴权与 Origin 拒绝、题库与版本（含乐观锁 409）、试卷发布快照、
未提交不泄露答案、作答保存与判分明细、**幂等提交不重复生成作答**、错题闭环（去重/复习/掌握）、
备份与恢复校验、LLM 配置 Schema 校验与导入预览-确认分离、脱敏导出、SSRF 拦截、
以及**真实模型的题目讲解与主观题建议分（含人工复核）**。

阶段 6 的自动化覆盖（不依赖真实模型）：资料导入（含重复文件三分支与文件补偿）、分块确定性、
FTS5 命中与**命中可回溯到具体分块**、短查询降级为关键词、版本化索引重建与旧版本保留、
能力化迁移（缺少 FTS5 时跳过 V3 且不记入 `schema_version`）、提示注入边界转义与引用过滤。
冒烟脚本已补入阶段 6 的 24 条断言（导入、检索可回溯到具体分块、短词降级为关键词、重复导入 409 与
`?sha256=` 反查、`onDuplicate=new`、索引重建、删除时的文件补偿、非文本格式 400）。

---

## 6. 关键设计取舍

完整清单见 [docs/architecture/decisions.md](docs/architecture/decisions.md)，其中影响最大的几条：

1. **题目选项规范化 + 答案单一权威来源**：`question_option.is_correct` 由 `answer.value` 推导，
   不给「两个正确答案来源」留不一致空间。
2. **试卷发布即快照**：判分只读 `paper_snapshot_question.snapshot_json`，题目改版不影响历史成绩。
3. **迁移执行器自研**：以 `schema_version` 记录脚本 SHA-256，脚本被篡改就拒绝启动；迁移前强制备份。
4. **恢复 = 登记请求 + 重启执行**：避免连接池持句柄时替换数据库文件。
5. **主观题两步评分**：AI 只产出建议分（仍是 `pending_review`），必须人工确认才转 `graded`。
6. **错题练习复用快照模型**：`paper_snapshot.kind='practice'`，判分链路完全同构。
7. **LLM 配置改为文件驱动**（ADR-014 取代 ADR-004）：`<UserData>/config/setting.json` 是唯一权威，
   `ConfigService` 缓存并热加载（文件变化去抖后校验，非法则保留上一份有效配置并在 `warnings` 说明）；
   不再有 OS 凭据存储与后端降级链，`POST /{id}/credential` 语义收敛为「只更新该档案的 `apiKey`」。
   旧 `llm_profile` / `llm_config_state` / `llm_import_preview` 三张表**保留结构但不再读写**，
   仅在「`setting.json` 不存在且旧表有数据」时首启一次性迁移（写 `app_meta.llm_config_migrated_at`，
   绝不覆盖已存在的文件），旧表与旧凭据一律不删，保留回退路径。

---

## 7. 安全基线

- 渲染进程：`contextIsolation=true`、`nodeIntegration=false`、`sandbox=true`，仅通过 preload 白名单通信；
  禁止任意导航与新窗口；注入严格 CSP（`connect-src` 只放行 `http://127.0.0.1:*`）。
- 本机服务：Java/Python 只绑定 `127.0.0.1`；业务接口必须带每次启动随机生成的
  `X-Session-Token`（退出即失效）；校验 `Host` 与 `Origin`。
- LLM 出口：URL 协议/凭据/端口校验 + DNS 全量解析 + 私网/链路本地/云元数据拦截 +
  逐跳重定向复检；限制超时与响应体大小；错误按 DNS/TLS/鉴权/模型/额度/超时/协议分类。
- LLM 密钥：**明文存放于 `<UserData>/config/setting.json`**（写入侧原子替换 + 尽力收紧为 0600；
  Windows 无 POSIX 权限位，实际由用户目录的 ACL 继承策略决定，收不紧只记 WARN、不影响写入）。
  该文件、内存缓存与「显式选择含密钥导出」是密钥仅有的三处出现位置；API 响应
  （`apiKeyMasked` 恒为 `******`，未配置时为 `null`）、日志、异常栈、审计事件、任务载荷、诊断包
  一律掩码或剔除（`authorization`、`api_key`、`apikey`、`token`、`secret`、`password`）。
  导出默认不含明文密钥（每个 profile 的 `apiKey` 置 `null`）；含密钥导出必须显式传
  `includeSecrets: true`、客户端二次确认并写审计事件（响应带 `containsSecrets: true`，
  建议文件名 `setting-with-keys.json`）。

---

## 8. 已知限制（第一版有意不做）

1. **资料导入只支持纯文本**：`.txt` / `.md`；PDF/DOCX 文本提取、OCR 与逐题校对属阶段 7（超范围格式返回
   `DOCUMENT_FORMAT_UNSUPPORTED`，界面直接提示，不做半成品解析）。
2. **无试卷导入**：文本型 PDF/DOCX 解析与逐题校对属阶段 7。
3. **无打包与自动更新**：Electron 目前以开发方式运行；运行时分发（jlink/PyInstaller）属阶段 8。
4. **Python 由 Electron 而非 Java 拉起**（ADR-002）；接口不变，阶段 8 可迁回。
5. **Windows 进程终止不走优雅停机**：关闭窗口时子进程被直接终止，一致性由 SQLite WAL 恢复保证。
6. **Element Plus 全量引入**：前端 CSS 约 391 kB（gzip 53 kB），按需引入留待后续优化。
7. 凭据后端在非 Windows 平台为文件降级（Keychain/libsecret 未接入）。
8. **检索只有 FTS5(trigram) 与关键词两条路径**：没有向量索引或重排；召回不足时的向量方案见开发文档 §526。
   中文查询不足 3 字符时无法走 FTS5，会明确降级为关键词并在界面标注 `mode`。
9. **冒烟脚本的单次全量运行未跑完**：`scripts/smoke-test.ps1` 已补入阶段 6 的 24 条断言（合计 103 项），
   但本机一次完整运行会长时间停在 `[10] AI 讲解与主观题建议分（真实模型）` 段落（qwen3:8b 真实调用，耗时不可控）。
   已验证的是：阶段 1-5 的 **79 项全量通过** + 阶段 6 的 **24 项在真实服务上隔离运行全绿**；
   阶段 6 的等价覆盖另外还在 `DocumentFlowIntegrationTest`（Java，真实 HTTP）与 Python 测试里。
10. **渲染层未引入测试框架**：仅做 `vue-tsc --noEmit` 与 `vite build` + README §8 实测记录，
    未覆盖表单校验与页面状态的自动化断言（与开发文档 §12.1 的显式偏差，见 ADR-013）。

---

## 9. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| `mvn` 命令不存在 | 用 `scripts/mvn.ps1`，它会复用 `~/.m2/wrapper/dists` 里的 Maven |
| 构建报「需要 JDK 17」 | 本机同时装了 JDK 8 与 17；设置 `JAVA_HOME` 指向 17，或让 `scripts/mvn.ps1` 自动探测 |
| 脚本报「未对文件进行数字签名」 | 用 `powershell -ExecutionPolicy Bypass -File ...` 调用 |
| `npm i` 打一堆 ERESOLVE 警告后崩在 `Cannot read properties of null (reading 'matches')` | 用错包管理器了：两个前端工程都必须用 `pnpm install`（已加 `preinstall` 守卫拦截，直接提示） |
| 页面提示「未找到 Java 运行时端点文件」 | 前端先于后端启动，shim 把那次失败缓存在内存里。刷新页面或点界面「重试」即可；下次用 `dev-start.ps1 -WithUI` 一次拉起可避免 |
| 脚本里中文变成乱码并报语法错误 | `.ps1` 丢了 UTF-8 BOM；执行 `scripts/fix-ps1-bom.ps1` 修复 |
| `vite build` 报 `remove ...esbuild-xxx: Access is denied` | 主机 `%TEMP%` 权限受限；用 `pnpm build:local-tmp`（把临时目录指到工程内） |
| Electron 启动报找不到二进制 | `pnpm install` 被 pnpm 11 拦下了 postinstall；`apps/desktop/pnpm-workspace.yaml` 已放行 `electron`，重跑安装 |
| 界面提示「本机服务未就绪」 | 业务服务没起来；先跑 `scripts/dev-start.ps1`，或看 `.runtime/logs/` 下的日志 |
| AI 功能不可用但答题正常 | 这是设计行为：AI 服务缺失只降级 AI 能力，不阻塞答题与判分 |

---

## 10. 文档索引

- 接口契约（冻结）：[docs/api/api-contract.md](docs/api/api-contract.md)
- 架构决策记录：[docs/architecture/decisions.md](docs/architecture/decisions.md)
- LLM 配置 Schema：[contracts/json-schema/llm-config.schema.json](contracts/json-schema/llm-config.schema.json)
- AI 服务说明：[services/python-ai/README.md](services/python-ai/README.md)
- 渲染层说明：[apps/renderer/README.md](apps/renderer/README.md)
- 桌面壳说明：[apps/desktop/README.md](apps/desktop/README.md)
