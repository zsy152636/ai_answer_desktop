# Electron 桌面壳

主进程负责**进程编排**与**安全边界**，不实现任何题目业务规则（开发文档 §2）。

## 目录

```
src/
├── main/
│   ├── main.ts             # 应用引导、窗口、IPC、CSP、导航限制
│   ├── paths.ts            # 目录与可执行文件解析（开发态/打包态）
│   └── service-manager.ts  # Python/Java 子进程编排、探活、停止
└── preload/
    └── preload.ts          # contextBridge 白名单（契约 §10）
```

## 构建与运行

```powershell
pnpm install          # 首次需下载 Electron 二进制，可设 $env:ELECTRON_MIRROR
pnpm build            # tsc → dist/
pnpm start            # 构建后经 scripts/start.mjs 启动 Electron
```

> 中文日志若显示为乱码（如「涓枃鏃ュ織」），是**读日志的一端**按 GBK 解码了 UTF-8 输出。
> 用 `scripts/start-app.ps1`（仓库根目录）启动即可：它会把控制台代码页与 PowerShell 的
> 输出编码一起对齐到 UTF-8——两者必须同时改，只 `chcp 65001` 对 PowerShell 5.1 无效。

> 必须用 pnpm：pnpm 的 `node_modules` 是「符号链接 + 内容寻址存储」结构，`npm i` 无法解析它，
> 会以 `Cannot read properties of null (reading 'matches')` 崩溃。`preinstall` 守卫会直接拦下 npm/yarn。

### 不依赖图形界面的验证

GUI 需要可用的桌面会话，而进程编排本身不该因此无法验证。`ServiceManager` 刻意不依赖 Electron：

```powershell
cd apps\desktop
npm run build
$env:JAVA_HOME='C:\path\to\jdk-17'
node scripts/verify-services.mjs
```

它会用真实 jar、真实 Python venv 和 `.devdata/desktop-verify` 数据目录跑完整生命周期，
断言：两个服务都能起来、端点文件与运行时令牌一致、`/health` 可达、无令牌访问业务接口返回 401、
**Java `/meta` 报告 AI 服务可用**、停止后状态与地址被清除。当前实测 **15 项全部通过**。

> 可用 `AIA_VERIFY_DATA_DIR` 把数据目录指到别处，复现「子进程写不了用户数据目录」这类环境问题：
> 例如指向 `%APPDATA%\ai-answer-desktop` 时 Python 会报 `[Errno 13]`（端点改由主进程代写），
> 断言仍应全部通过。

## 启动顺序

1. 解析目录（`<userData>/runtime`、`<userData>/logs`）并清掉上一次残留的端点文件；
2. 启动 Python AI 服务（随机端口），等待 **stdout 中继的端点**或 `python-endpoint.json`（先到者为准，
   最长 30s；超时后仍在后台复查 2 分钟，服务晚到会自动恢复为「可用」）；
3. 启动 Java 业务服务（随机端口），传入数据目录、端点文件与 Python 端点文件，等待端点文件 + `/health`（最长 90s）；
4. 创建窗口，通过同步 IPC 把 `{javaBaseUrl, sessionToken, pythonBaseUrl, dataDir, logDir}` 交给 preload；
5. 关闭窗口或退出时按 Java → Python 顺序终止子进程（Windows 上用 `taskkill /T /F` 结束整棵进程树）。

> 为什么由 Electron 而不是 Java 拉起 Python：见 [docs/architecture/decisions.md](../../docs/architecture/decisions.md) ADR-002。

## 安全基线

| 项 | 取值 |
| --- | --- |
| `contextIsolation` | `true` |
| `nodeIntegration` | `false` |
| `sandbox` | `true` |
| `webSecurity` | `true` |
| preload 白名单 | 契约 §10 的固定函数，禁止暴露 `ipcRenderer`/Node 全局对象 |
| 导航 | `will-navigate` 只允许本地渲染源；`setWindowOpenHandler` 一律 deny（https 交给系统浏览器） |
| `webview` | `will-attach-webview` 一律阻止 |
| CSP | `default-src 'self'`；`connect-src` 仅 `http://127.0.0.1:*`（开发态额外放行 Vite 的 ws） |
| 会话令牌 | 每次启动随机生成，只经同步 IPC 交给渲染层，不落盘、不写日志 |

`shell:open-path` 只接受用户数据目录、日志目录、运行目录内（含子路径）的目标，其他路径直接拒绝。

## 渲染层加载策略

- 设置了 `AIA_RENDERER_URL` → 直接加载该地址；
- 否则若 `http://127.0.0.1:5173`（Vite 开发服务器）可达 → 加载它（便于热更新）；
- 否则加载构建产物 `apps/renderer/dist/index.html`（打包态为 `resources/renderer/index.html`）；
- 都不存在时显示可读的提示页，而不是白屏。

## 环境变量

| 变量 | 用途 |
| --- | --- |
| `AIA_JAVA_BIN` | 显式指定 java 可执行文件 |
| `AIA_JAVA_HOME` / `JAVA_HOME` | JDK 17+ 目录 |
| `AIA_USER_DATA_DIR` | 覆盖用户数据目录（逃生开关：`%APPDATA%` 被安全策略限制时用） |
| `AIA_RENDERER_URL` | 指定渲染层 URL（覆盖开发/生产判定） |
| `ELECTRON_MIRROR` | Electron 二进制下载镜像（安装期生效） |

## 故障排查

| 现象 | 原因与处理 |
| --- | --- |
| 报 `Cannot read properties of undefined (reading 'whenReady')` | 环境里存在 `ELECTRON_RUN_AS_NODE=1`，Electron 退化成纯 Node。`pnpm start` 走的 `scripts/start.mjs` 会自动删除该变量；直接调 `electron .` 时需自行清除 |
| `npm i` 崩在 `Cannot read properties of null (reading 'matches')` | 用错包管理器：必须 `pnpm install`（`preinstall` 守卫已会拦截并提示） |
| Electron 二进制启动即退出、连 `electron --version` 都非零退出 | 当前会话缺少可用的桌面/图形环境（Windows 上表现为 `0xC0000005` 或 `0x80000003`）。请在有交互桌面会话的终端里启动；进程编排可改用 `scripts/verify-services.mjs` 验证 |
| 服务起不来且日志显示无法建目录 | 数据目录不可写。用 `AIA_USER_DATA_DIR` 指到一个可写目录（部分主机的 `%TEMP%`/`%APPDATA%` 受 ACL 限制） |
| 窗口提示「前端尚未构建」 | 先执行 `apps/renderer` 的 `pnpm build`，或启动 Vite 开发服务器并设 `AIA_RENDERER_URL` |
| 控制台里子进程中文日志乱码 | 读日志的一端按 GBK 解码了 UTF-8。用 `scripts/start-app.ps1` 启动（同时对齐 `chcp 65001` 与 `[Console]::OutputEncoding`）；只执行 `chcp 65001` 对 PowerShell 5.1 无效 |
| `.runtime\logs\python-ai.*.log` 内容乱码 | 该文件由 `dev-start.ps1` 重定向产生：修复前 Python 按 ANSI 代码页（GBK）写文件。脚本现已设置 `PYTHONIOENCODING=utf-8`，重跑一次即可，旧文件仍是 GBK |
| AI 面板提示「AI 服务不可用」 | 先看状态消息里附带的子进程最近输出。Python 若报 `endpoint 文件写入失败 … [Errno 13]`，说明它写不了用户数据目录（完整性级别/ACL 限制），此时端点由主进程经 stdout 中继代写，Java 仍应报 `aiService.available=true` |

## 已知限制

- Windows 上终止子进程是强制结束，不是优雅停机；一致性由 SQLite WAL 恢复保证（见 README §8）。
- 打包（electron-builder、运行时 staging、签名）属开发文档阶段 8，第一版未包含。
