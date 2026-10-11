import { ChildProcess, spawn, spawnSync } from 'node:child_process';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { StringDecoder } from 'node:string_decoder';
// 只做类型引用：本模块必须能在纯 Node 下被测试脚本加载（paths.ts 会 require electron）
import type { Layout } from './paths';
import { readEndpointFile, type EndpointSnapshot } from './endpoints';

export type ServiceState = 'starting' | 'up' | 'down' | 'disabled';

export interface ServiceStatus {
  readonly state: ServiceState;
  readonly message: string;
  readonly baseUrl: string | null;
  readonly pid: number | null;
}

export interface RuntimeSnapshot {
  readonly appVersion: string;
  readonly platform: string;
  readonly dataDir: string;
  readonly logDir: string;
  readonly javaBaseUrl: string | null;
  readonly sessionToken: string | null;
  readonly pythonBaseUrl: string | null;
  readonly status: { java: ServiceStatus; python: ServiceStatus };
  readonly warnings: string[];
}

type Listener = (snapshot: RuntimeSnapshot) => void;

/**
 * Python 端点经 stdout 交给父进程时的行前缀。
 * 必须与 services/python-ai/app/config.py 的 `ENDPOINT_STDOUT_PREFIX` 保持一致。
 * 该行包含内部令牌，解析后必须丢弃，绝不写日志。
 */
const PYTHON_ENDPOINT_STDOUT_PREFIX = 'AIA_ENDPOINT ';

/** 每个子进程保留的日志尾部行数，用于把真实失败原因写进状态消息。 */
const DIAGNOSTIC_TAIL_LINES = 6;
/** 单行日志的最大缓冲（防止子进程只写不换行时无限增长）。 */
const MAX_PENDING_LINE_BYTES = 64 * 1024;
/** 端点文件缺失后继续后台复查的间隔与窗口。 */
const PYTHON_RECHECK_INTERVAL_MS = 3_000;
const PYTHON_RECHECK_WINDOW_MS = 120_000;

/**
 * 子进程编排（开发文档 §2.1）。
 *
 * 职责边界：本类只负责「按顺序拉起、探活、停止、把运行时会话交给渲染层」，
 * 不含任何业务规则。启动顺序是 Python → Java，理由见 docs/architecture/decisions.md ADR-002。
 *
 * 令牌只存在于内存与本机端点文件中，退出即失效；日志里也只打印端口，不打印令牌。
 */
export class ServiceManager {
  private javaProcess: ChildProcess | null = null;
  private pythonProcess: ChildProcess | null = null;
  private javaStatus: ServiceStatus = { state: 'down', message: '未启动', baseUrl: null, pid: null };
  private pythonStatus: ServiceStatus = { state: 'down', message: '未启动', baseUrl: null, pid: null };
  private readonly listeners = new Set<Listener>();
  private readonly diagnostics = new Map<string, string[]>();
  private stopping = false;
  private pythonRecheckTimer: NodeJS.Timeout | null = null;
  private pythonRecheckUntil = 0;

  constructor(private readonly layout: Layout, private readonly appVersion: string) {}

  onStatusChange(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  getRuntime(): RuntimeSnapshot {
    const javaEndpoint = readEndpointFile(this.layout.javaEndpointFile);
    const pythonEndpoint = readEndpointFile(this.layout.pythonEndpointFile);
    // AI 地址既不缓存「超时」结论，也不缓存端点：只要子进程还活着，
    // 就以端点文件（子进程直写，或父进程代写）为准，UI 才能从失败态自愈。
    const pythonBaseUrl =
      this.pythonStatus.state === 'up'
        ? this.pythonStatus.baseUrl
        : this.pythonProcess
          ? (pythonEndpoint?.baseUrl ?? null)
          : null;
    return {
      appVersion: this.appVersion,
      platform: process.platform,
      dataDir: this.layout.userDataDir,
      logDir: this.layout.logsDir,
      javaBaseUrl: this.javaStatus.state === 'up' ? this.javaStatus.baseUrl : null,
      sessionToken: this.javaStatus.state === 'up' && javaEndpoint ? javaEndpoint.token : null,
      pythonBaseUrl,
      status: { java: this.javaStatus, python: this.pythonStatus },
      warnings: this.layout.warnings,
    };
  }

  private emit(): void {
    const snapshot = this.getRuntime();
    for (const listener of this.listeners) {
      try {
        listener(snapshot);
      } catch {
        // 监听者异常不得影响启动流程
      }
    }
  }

  async startAll(): Promise<void> {
    fs.mkdirSync(this.layout.runtimeDir, { recursive: true });
    fs.mkdirSync(this.layout.logsDir, { recursive: true });
    this.clearPythonRecheck();
    // 上一次运行的端点文件必须清掉，否则会把「陈旧端口」当成服务已就绪
    for (const stale of [this.layout.javaEndpointFile, this.layout.pythonEndpointFile]) {
      try {
        fs.rmSync(stale, { force: true });
      } catch {
        // 忽略：文件本来就不存在
      }
    }
    await this.startPython();
    await this.startJava();
  }

  async restartAll(): Promise<RuntimeSnapshot> {
    await this.stopAll();
    await this.startAll();
    return this.getRuntime();
  }

  private async startPython(): Promise<void> {
    if (!this.layout.pythonExe || !this.layout.pythonAppDir) {
      this.pythonStatus = {
        state: 'disabled',
        message: '未安装 Python AI 服务依赖（services/python-ai/.venv），AI 功能不可用',
        baseUrl: null,
        pid: null,
      };
      this.emit();
      return;
    }
    this.pythonStatus = { state: 'starting', message: '正在启动 AI 服务', baseUrl: null, pid: null };
    this.emit();

    const args = [
      '-m',
      'app.main',
      '--host',
      '127.0.0.1',
      '--port',
      '0',
      '--endpoint-file',
      this.layout.pythonEndpointFile,
      // 让子进程把端点也经 stdout 交给本进程：子进程可能因完整性级别/ACL 限制
      // 写不了用户数据目录，而本进程通常可以，由本进程代为落盘。
      '--endpoint-stdout',
      '--log-level',
      'INFO',
    ];
    // 用对象包一层，避免 TS 在闭包赋值后仍把变量窄化为 null
    const relay: { endpoint: EndpointSnapshot | null } = { endpoint: null };

    this.pythonProcess = spawn(this.layout.pythonExe, args, {
      cwd: this.layout.pythonAppDir,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
      env: {
        ...process.env,
        PYTHONUNBUFFERED: '1',
        // stdout 是管道时，Windows 上的 Python 默认用 ANSI 代码页（中文环境为 GBK/cp936），
        // 与本进程的 utf8 解码不一致就会出现乱码。强制 UTF-8 端到端保持一致。
        PYTHONIOENCODING: 'utf-8',
        PYTHONUTF8: '1',
      },
    });
    this.pipeLogs(this.pythonProcess, 'python-ai', (line) => {
      if (!line.startsWith(PYTHON_ENDPOINT_STDOUT_PREFIX)) {
        return false;
      }
      const endpoint = parseEndpointLine(line.slice(PYTHON_ENDPOINT_STDOUT_PREFIX.length));
      if (!endpoint) {
        return false;
      }
      relay.endpoint = endpoint;
      this.publishPythonEndpoint(endpoint);
      return true; // 含内部令牌：消费掉，不进日志
    });
    this.pythonProcess.on('error', (error) => {
      this.pythonStatus = {
        state: 'down',
        message: `无法启动 AI 服务进程：${error.message}`,
        baseUrl: null,
        pid: null,
      };
      this.emit();
    });
    this.pythonProcess.on('exit', (code) => {
      const wasStopping = this.stopping;
      this.pythonProcess = null;
      this.clearPythonRecheck();
      this.pythonStatus = {
        state: 'down',
        message: wasStopping
          ? '已停止'
          : this.withDiagnostics(`AI 服务退出（退出码 ${code ?? '未知'}）`, 'python-ai'),
        baseUrl: null,
        pid: null,
      };
      this.emit();
    });

    const endpoint = await this.waitForPythonEndpoint(30_000, () => relay.endpoint);
    if (endpoint && this.pythonProcess) {
      this.pythonStatus = {
        state: 'up',
        message: 'AI 服务就绪',
        baseUrl: endpoint.baseUrl,
        pid: this.pythonProcess.pid ?? null,
      };
    } else {
      this.pythonStatus = {
        state: 'down',
        message: this.withDiagnostics('AI 服务启动超时（答题主流程不受影响）', 'python-ai'),
        baseUrl: null,
        pid: this.pythonProcess?.pid ?? null,
      };
      // 一次超时不等于永久不可用：继续后台复查，服务晚一点就绪也能自动恢复
      this.schedulePythonRecheck();
    }
    this.emit();
  }

  /**
   * 等待 Python 端点：stdout 中继与端点文件任一先到即返回。
   *
   * <p>注意超时判定放在读取之后：主进程如果被阻塞（例如 stdout 管道写满）导致越过截止时间，
   * 已经就绪的端点也不应该被丢掉。
   */
  private async waitForPythonEndpoint(
    timeoutMs: number,
    relayed: () => EndpointSnapshot | null,
  ): Promise<EndpointSnapshot | null> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const fromRelay = relayed();
      if (fromRelay) {
        return fromRelay;
      }
      const fromFile = readEndpointFile(this.layout.pythonEndpointFile);
      if (fromFile) {
        return fromFile;
      }
      if (Date.now() >= deadline) {
        return null;
      }
      await delay(200);
    }
  }

  /**
   * 由父进程代写 Python 端点文件（Java 仍按文件读取端点）。
   *
   * 走同目录临时文件 + rename 的原子替换，避免 Java 读到半截 JSON；失败只告警，
   * 因为本次会话的运行时会话已经从 stdout 中继拿到，AI 功能不受影响。
   */
  private publishPythonEndpoint(endpoint: EndpointSnapshot): void {
    const target = this.layout.pythonEndpointFile;
    const port = Number.parseInt(endpoint.baseUrl.slice(endpoint.baseUrl.lastIndexOf(':') + 1), 10);
    const payload = {
      service: 'python-ai',
      port: Number.isFinite(port) ? port : null,
      baseUrl: endpoint.baseUrl,
      token: endpoint.token,
      tokenHeader: 'X-Internal-Token',
      relayedBy: 'desktop-shell',
      startedAt: new Date().toISOString(),
    };
    try {
      fs.mkdirSync(path.dirname(target), { recursive: true });
      const temp = `${target}.${process.pid}.relay.tmp`;
      fs.writeFileSync(temp, `${JSON.stringify(payload, null, 2)}\n`, 'utf8');
      fs.renameSync(temp, target);
    } catch (error) {
      console.warn(
        `[python-ai] 端点文件代写失败（本次会话仍通过内存端点工作）：${target} ${(error as Error).message}`,
      );
    }
  }

  private schedulePythonRecheck(): void {
    if (this.pythonRecheckTimer) {
      return;
    }
    this.pythonRecheckUntil = Date.now() + PYTHON_RECHECK_WINDOW_MS;
    this.pythonRecheckTimer = setInterval(() => {
      void this.recheckPython();
    }, PYTHON_RECHECK_INTERVAL_MS);
    this.pythonRecheckTimer.unref?.();
  }

  private clearPythonRecheck(): void {
    if (this.pythonRecheckTimer) {
      clearInterval(this.pythonRecheckTimer);
      this.pythonRecheckTimer = null;
    }
    this.pythonRecheckUntil = 0;
  }

  /** 后台复查：端点出现且 /health 通过时，把状态从「超时」恢复为「就绪」。 */
  private async recheckPython(): Promise<void> {
    if (!this.pythonProcess || Date.now() > this.pythonRecheckUntil) {
      this.clearPythonRecheck();
      return;
    }
    const endpoint = readEndpointFile(this.layout.pythonEndpointFile);
    if (!endpoint) {
      return;
    }
    if (!(await this.probeHealth(endpoint.baseUrl))) {
      return;
    }
    this.pythonStatus = {
      state: 'up',
      message: 'AI 服务就绪（已从启动超时中恢复）',
      baseUrl: endpoint.baseUrl,
      pid: this.pythonProcess.pid ?? null,
    };
    this.clearPythonRecheck();
    this.emit();
  }

  private async startJava(): Promise<void> {
    if (!this.layout.javaJar) {
      this.javaStatus = {
        state: 'down',
        message: '未找到 Java 服务包，请先执行 scripts/mvn.ps1 package',
        baseUrl: null,
        pid: null,
      };
      this.emit();
      return;
    }
    this.javaStatus = { state: 'starting', message: '正在启动业务服务', baseUrl: null, pid: null };
    this.emit();

    const args = [
      // logback 的 CONSOLE appender 已显式声明 UTF-8（见 logback-spring.xml），所以 Java 日志
      // 本身一直是 UTF-8；这里额外固定 JVM 的输出编码，覆盖 logback 之外（Spring banner 等）
      // 写到 System.out/System.err 的内容，避免同一份日志里出现两种编码。
      '-Dstdout.encoding=UTF-8',
      '-Dstderr.encoding=UTF-8',
      '-Dsun.stdout.encoding=UTF-8',
      '-Dsun.stderr.encoding=UTF-8',
      '-jar',
      this.layout.javaJar,
      `--aia.data-dir=${this.layout.userDataDir}`,
      `--aia.endpoint-file=${this.layout.javaEndpointFile}`,
      `--aia.python-endpoint-file=${this.layout.pythonEndpointFile}`,
      '--server.port=0',
      '--server.address=127.0.0.1',
    ];
    this.javaProcess = spawn(this.layout.javaBin, args, {
      cwd: path.dirname(this.layout.javaJar),
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env },
    });
    this.pipeLogs(this.javaProcess, 'java-api');
    this.javaProcess.on('error', (error) => {
      this.javaStatus = {
        state: 'down',
        message: `无法启动 Java 进程：${error.message}`,
        baseUrl: null,
        pid: null,
      };
      this.emit();
    });
    this.javaProcess.on('exit', (code) => {
      const wasStopping = this.stopping;
      this.javaProcess = null;
      this.javaStatus = {
        state: 'down',
        message: wasStopping
          ? '已停止'
          : this.withDiagnostics(`业务服务退出（退出码 ${code ?? '未知'}）`, 'java-api'),
        baseUrl: null,
        pid: null,
      };
      this.emit();
    });

    const endpoint = await this.waitForEndpoint(this.layout.javaEndpointFile, 90_000);
    if (!endpoint) {
      this.javaStatus = {
        state: 'down',
        message: this.withDiagnostics(
          '业务服务启动超时，请查看数据目录 logs/java-api.log',
          'java-api',
        ),
        baseUrl: null,
        pid: this.javaProcess?.pid ?? null,
      };
      this.emit();
      return;
    }
    const healthy = await this.waitForHealth(endpoint.baseUrl, 30_000);
    this.javaStatus = {
      state: healthy ? 'up' : 'down',
      message: healthy
        ? '业务服务就绪'
        : this.withDiagnostics('业务服务进程已启动但健康检查未通过', 'java-api'),
      baseUrl: endpoint.baseUrl,
      pid: this.javaProcess?.pid ?? null,
    };
    this.emit();
  }

  /**
   * 逐行转发子进程日志。
   *
   * <p>两个细节都很重要：
   * 1) 必须用 `StringDecoder` 而不是 `chunk.toString('utf8')`：多字节字符可能被切分到两个
   *    chunk，直接解码会在日志里出现 U+FFFD（乱码）；
   * 2) `onLine` 返回 true 表示该行已被业务消费（例如含令牌的端点行），不得写日志。
   */
  private pipeLogs(
    child: ChildProcess,
    tag: string,
    onLine?: (line: string) => boolean,
  ): void {
    const attach = (stream: NodeJS.ReadableStream | null): void => {
      if (!stream) {
        return;
      }
      const decoder = new StringDecoder('utf8');
      let pending = '';
      const flush = (line: string): void => {
        const text = line.endsWith('\r') ? line.slice(0, -1) : line;
        if (text.trim().length === 0) {
          return;
        }
        if (onLine && onLine(text)) {
          return;
        }
        this.rememberDiagnostic(tag, text);
        console.log(`[${tag}] ${text}`);
      };
      stream.on('data', (chunk: Buffer) => {
        pending += decoder.write(chunk);
        let index = pending.indexOf('\n');
        while (index >= 0) {
          flush(pending.slice(0, index));
          pending = pending.slice(index + 1);
          index = pending.indexOf('\n');
        }
        if (pending.length > MAX_PENDING_LINE_BYTES) {
          flush(pending);
          pending = '';
        }
      });
      stream.on('end', () => {
        pending += decoder.end();
        if (pending.length > 0) {
          flush(pending);
        }
      });
    };
    attach(child.stdout);
    attach(child.stderr);
  }

  private rememberDiagnostic(tag: string, line: string): void {
    const tail = this.diagnostics.get(tag) ?? [];
    tail.push(line.length > 300 ? `${line.slice(0, 300)}…` : line);
    if (tail.length > DIAGNOSTIC_TAIL_LINES) {
      tail.splice(0, tail.length - DIAGNOSTIC_TAIL_LINES);
    }
    this.diagnostics.set(tag, tail);
  }

  /** 把子进程最近输出拼进状态消息：用户看到的是真实原因，而不是干巴巴的「超时」。 */
  private withDiagnostics(message: string, tag: string): string {
    const tail = this.diagnostics.get(tag) ?? [];
    if (tail.length === 0) {
      return message;
    }
    const detail = tail.join(' / ');
    const compact = detail.length > 400 ? `${detail.slice(-400)}` : detail;
    return `${message}｜最近输出：${compact}`;
  }

  private async waitForEndpoint(
    file: string,
    timeoutMs: number,
  ): Promise<{ baseUrl: string; token: string } | null> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const endpoint = readEndpointFile(file);
      if (endpoint) {
        return endpoint;
      }
      if (Date.now() >= deadline) {
        return null;
      }
      await delay(300);
    }
  }

  private async waitForHealth(baseUrl: string, timeoutMs: number): Promise<boolean> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (await this.probeHealth(baseUrl)) {
        return true;
      }
      await delay(400);
    }
    return false;
  }

  private async probeHealth(baseUrl: string): Promise<boolean> {
    try {
      const response = await fetch(`${baseUrl}/health`, { signal: AbortSignal.timeout(2000) });
      return response.ok;
    } catch {
      // 服务还没起来，继续等
      return false;
    }
  }

  async stopAll(): Promise<void> {
    this.stopping = true;
    this.clearPythonRecheck();
    try {
      await Promise.all([this.stop(this.javaProcess, 'java'), this.stop(this.pythonProcess, 'python')]);
    } finally {
      this.stopping = false;
    }
  }

  private async stop(child: ChildProcess | null, tag: string): Promise<void> {
    if (!child || child.exitCode !== null || child.killed) {
      return;
    }
    const pid = child.pid;
    return new Promise<void>((resolve) => {
      const timer = setTimeout(() => {
        this.killTree(child);
        resolve();
      }, 5000);
      child.once('exit', () => {
        clearTimeout(timer);
        resolve();
      });
      // Windows 上 kill 会直接终止进程；SQLite 依赖 WAL 恢复保证一致性
      this.killTree(child);
      void tag;
    });
  }

  /**
   * 结束子进程及其整棵进程树。
   *
   * <p>Windows 上 `child.kill()` 只终止直接子进程：venv 里的 `python.exe` 是启动器，
   * 真正的解释器是它的子进程，只杀启动器会留下占着端口的孤儿进程（表现为「旧服务还活着、
   * 新服务起不来」）。因此这里显式用 `taskkill /T /F` 结束整棵树。
   */
  private killTree(child: ChildProcess): void {
    const pid = child.pid;
    if (process.platform === 'win32' && typeof pid === 'number') {
      try {
        const result = spawnSync('taskkill', ['/pid', String(pid), '/T', '/F'], {
          windowsHide: true,
          stdio: 'ignore',
        });
        if (result.error === undefined) {
          return;
        }
      } catch {
        // 回落到 child.kill()
      }
    }
    try {
      child.kill();
    } catch {
      // 进程可能已经退出
    }
  }
}

/** 解析 Python 经 stdout 中继的端点行；格式非法时返回 null（该行随后进入普通日志）。 */
function parseEndpointLine(raw: string): EndpointSnapshot | null {
  try {
    const parsed = JSON.parse(raw) as { baseUrl?: unknown; token?: unknown };
    if (typeof parsed.baseUrl !== 'string' || parsed.baseUrl.length === 0) {
      return null;
    }
    return { baseUrl: parsed.baseUrl, token: typeof parsed.token === 'string' ? parsed.token : '' };
  } catch {
    return null;
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
