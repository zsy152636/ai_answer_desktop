import { app, BrowserWindow, dialog, ipcMain, session, shell } from 'electron';
import * as crypto from 'node:crypto';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { Layout, resolveLayout } from './paths';
import { RuntimeSnapshot, ServiceManager } from './service-manager';

/**
 * Electron 主进程（开发文档 §2.1、§2.2）。
 *
 * 安全基线：renderer 不开 Node、开启 contextIsolation 与 sandbox、只通过 preload 白名单通信、
 * 禁止任意导航与新窗口、注入严格 CSP。主进程不实现任何题目业务规则。
 */

const MAX_TEXT_FILE_BYTES = 1024 * 1024;

let layout: Layout;
let services: ServiceManager;
let mainWindow: BrowserWindow | null = null;

function createWindow(): BrowserWindow {
  const window = new BrowserWindow({
    width: 1360,
    height: 900,
    minWidth: 1080,
    minHeight: 700,
    show: false,
    backgroundColor: '#f5f6f8',
    title: 'AI 智能答题系统',
    webPreferences: {
      preload: path.join(__dirname, '..', 'preload', 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      allowRunningInsecureContent: false,
      experimentalFeatures: false,
      spellcheck: false,
    },
  });

  window.once('ready-to-show', () => window.show());

  // 导航与新窗口限制：只允许本地渲染源
  window.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('https://')) {
      void shell.openExternal(url);
    }
    return { action: 'deny' };
  });
  window.webContents.on('will-navigate', (event, url) => {
    if (!isAllowedRendererUrl(url)) {
      event.preventDefault();
      console.warn(`[security] 拒绝导航：${url}`);
    }
  });
  window.webContents.on('will-attach-webview', (event) => {
    event.preventDefault();
  });

  void loadRenderer(window);
  return window;
}

async function loadRenderer(window: BrowserWindow): Promise<void> {
  const devUrl = layout.devRendererUrl;
  if (devUrl && (await isReachable(devUrl))) {
    console.log(`[renderer] 使用开发服务器 ${devUrl}`);
    await window.loadURL(devUrl);
    return;
  }
  if (layout.rendererIndexHtml) {
    console.log(`[renderer] 使用构建产物 ${layout.rendererIndexHtml}`);
    await window.loadFile(layout.rendererIndexHtml);
    return;
  }
  await window.loadURL(
    'data:text/html;charset=utf-8,' +
      encodeURIComponent(
        `<h2>前端尚未构建</h2><p>请先在 apps/renderer 执行 <code>pnpm install &amp;&amp; pnpm build</code>，
         或启动 Vite 开发服务器（pnpm dev）。</p><p>期望产物：${layout.packaged ? 'resources/renderer/index.html' : 'apps/renderer/dist/index.html'}</p>`,
      ),
  );
}

function isAllowedRendererUrl(url: string): boolean {
  if (url.startsWith('file://')) {
    return true;
  }
  if (layout.devRendererUrl && url.startsWith(layout.devRendererUrl)) {
    return true;
  }
  return url.startsWith('http://127.0.0.1:') || url.startsWith('http://localhost:');
}

async function isReachable(url: string): Promise<boolean> {
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(1200) });
    return response.status < 500;
  } catch {
    return false;
  }
}

function applyContentSecurityPolicy(): void {
  const connect = ["'self'", 'http://127.0.0.1:*', 'http://localhost:*'];
  if (layout.devRendererUrl) {
    // Vite 开发服务器的 HMR 使用 WebSocket
    connect.push('ws://127.0.0.1:*', 'ws://localhost:*');
  }
  const policy = [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: blob:",
    "font-src 'self' data:",
    `connect-src ${connect.join(' ')}`,
    "object-src 'none'",
    "frame-ancestors 'none'",
    "base-uri 'none'",
    "form-action 'none'",
  ].join('; ');

  session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
    callback({
      responseHeaders: {
        ...details.responseHeaders,
        'Content-Security-Policy': [policy],
        'X-Content-Type-Options': ['nosniff'],
      },
    });
  });
}

function registerIpc(): void {
  // 渲染层在 preload 里同步取运行时会话：端口与短期令牌只在本进程生命周期内有效
  ipcMain.on('app:runtime', (event) => {
    event.returnValue = services.getRuntime();
  });

  ipcMain.handle('app:get-version', () => app.getVersion());

  ipcMain.handle('app:get-runtime', () => services.getRuntime());

  ipcMain.handle('app:get-health', async () => {
    const snapshot = services.getRuntime();
    return {
      java: await probeHealth(snapshot.javaBaseUrl),
      python: await probeHealth(snapshot.pythonBaseUrl),
      runtime: snapshot,
    };
  });

  ipcMain.handle('dialog:select-json-file', async () => {
    const result = await dialog.showOpenDialog({
      title: '选择 LLM 配置文件',
      filters: [{ name: 'JSON 配置', extensions: ['json'] }],
      properties: ['openFile'],
    });
    const filePath = result.filePaths[0];
    if (result.canceled || !filePath) {
      return { canceled: true as const };
    }
    const stat = fs.statSync(filePath);
    if (stat.size > MAX_TEXT_FILE_BYTES) {
      return { canceled: false as const, filePath, error: '文件超过 1 MiB 上限，请改用更小的配置文件' };
    }
    return { canceled: false as const, filePath, content: fs.readFileSync(filePath, 'utf8'), name: path.basename(filePath) };
  });

  ipcMain.handle('dialog:select-import-file', async () => {
    const result = await dialog.showOpenDialog({
      title: '选择要导入的文件',
      filters: [
        { name: '支持的文档', extensions: ['pdf', 'docx', 'txt', 'md'] },
        { name: '全部文件', extensions: ['*'] },
      ],
      properties: ['openFile'],
    });
    const filePath = result.filePaths[0];
    if (result.canceled || !filePath) {
      return { canceled: true as const };
    }
    const buffer = fs.readFileSync(filePath);
    return {
      canceled: false as const,
      filePath,
      name: path.basename(filePath),
      sizeBytes: buffer.byteLength,
      sha256: crypto.createHash('sha256').update(buffer).digest('hex'),
    };
  });

  ipcMain.handle('file:export-config-file', async (_event, defaultName: unknown, content: unknown) => {
    const safeName = typeof defaultName === 'string' && defaultName.length > 0 ? defaultName : 'llm-config.json';
    const result = await dialog.showSaveDialog({
      title: '导出配置',
      defaultPath: path.join(app.getPath('documents'), path.basename(safeName)),
      filters: [{ name: 'JSON 配置', extensions: ['json'] }],
    });
    if (result.canceled || !result.filePath) {
      return { canceled: true as const };
    }
    fs.writeFileSync(result.filePath, typeof content === 'string' ? content : '', 'utf8');
    return { canceled: false as const, filePath: result.filePath };
  });

  // 只允许打开用户数据目录与日志目录（含子路径），其他路径一律拒绝
  ipcMain.handle('shell:open-path', async (_event, target: unknown) => {
    const candidate = typeof target === 'string' ? path.resolve(target) : '';
    const allowedRoots = [layout.userDataDir, layout.logsDir, layout.runtimeDir].map((root) => path.resolve(root));
    const allowed = candidate.length > 0 && allowedRoots.some((root) => candidate === root || candidate.startsWith(root + path.sep));
    if (!allowed) {
      return { ok: false, message: '只允许打开应用数据目录或日志目录内的路径' };
    }
    if (!fs.existsSync(candidate)) {
      return { ok: false, message: '路径不存在' };
    }
    const error = await shell.openPath(candidate);
    return error ? { ok: false, message: error } : { ok: true };
  });

  ipcMain.handle('app:show-logs', async () => {
    const error = await shell.openPath(layout.logsDir);
    return error ? { ok: false, message: error } : { ok: true };
  });

  ipcMain.handle('app:restart-services', async () => {
    const snapshot = await services.restartAll();
    mainWindow?.webContents.send('service:restarted', snapshot);
    return { ok: true, runtime: snapshot };
  });
}

async function probeHealth(baseUrl: string | null): Promise<{ reachable: boolean; status: string; message: string }> {
  if (!baseUrl) {
    return { reachable: false, status: 'DOWN', message: '未启动' };
  }
  try {
    const response = await fetch(`${baseUrl}/health`, { signal: AbortSignal.timeout(3000) });
    const body = (await response.json()) as { status?: string; version?: string };
    return { reachable: true, status: body.status ?? 'UNKNOWN', message: `版本 ${body.version ?? '未知'}` };
  } catch (error) {
    return { reachable: false, status: 'DOWN', message: error instanceof Error ? error.message : '未知错误' };
  }
}

app.whenReady().then(async () => {
  layout = resolveLayout();
  services = new ServiceManager(layout, app.getVersion());

  applyContentSecurityPolicy();
  registerIpc();

  services.onStatusChange((snapshot: RuntimeSnapshot) => {
    mainWindow?.webContents.send('service:status', snapshot);
  });

  mainWindow = createWindow();

  // 先把窗口显示出来（可以马上看到「正在启动服务」），再拉起子进程
  await services.startAll();
  mainWindow.webContents.send('service:status', services.getRuntime());

  if (layout.warnings.length > 0) {
    console.warn('[startup] ' + layout.warnings.join(' | '));
  }

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      mainWindow = createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  void shutdown(0);
});

app.on('before-quit', () => {
  void services?.stopAll();
});

async function shutdown(code: number): Promise<void> {
  try {
    await services?.stopAll();
  } finally {
    app.exit(code);
  }
}

process.on('SIGINT', () => void shutdown(0));
process.on('SIGTERM', () => void shutdown(0));
