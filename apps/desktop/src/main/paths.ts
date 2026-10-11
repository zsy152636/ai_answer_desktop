import { app } from 'electron';
import * as fs from 'node:fs';
import * as path from 'node:path';

/**
 * 目录与可执行文件解析。
 *
 * 开发态（未打包）从仓库目录解析；打包态从 resources 目录解析。
 * 所有路径都必须显式给出，绝不依赖进程当前工作目录（开发文档 §4.1）。
 */
export interface Layout {
  readonly packaged: boolean;
  readonly userDataDir: string;
  readonly runtimeDir: string;
  readonly logsDir: string;
  readonly javaEndpointFile: string;
  readonly pythonEndpointFile: string;
  readonly javaJar: string | null;
  readonly javaBin: string;
  readonly javaHome: string | null;
  readonly pythonExe: string | null;
  readonly pythonAppDir: string | null;
  readonly rendererIndexHtml: string | null;
  readonly devRendererUrl: string | null;
  /** 解析过程中的问题，直接展示给用户，而不是静默降级。 */
  readonly warnings: string[];
}

const WINDOWS = process.platform === 'win32';

function exists(target: string | null): boolean {
  if (!target) {
    return false;
  }
  try {
    fs.accessSync(target, fs.constants.F_OK);
    return true;
  } catch {
    return false;
  }
}

/** 仓库根目录：src/main → apps/desktop → apps → 仓库根。 */
function repositoryRoot(): string {
  return path.resolve(__dirname, '..', '..', '..', '..');
}

function resourceRoot(): string {
  return process.resourcesPath ?? path.resolve(__dirname, '..', '..');
}

function resolveJavaBin(warnings: string[]): { bin: string; home: string | null } {
  const candidates: string[] = [];
  if (process.env.AIA_JAVA_BIN) {
    candidates.push(process.env.AIA_JAVA_BIN);
  }
  const home = process.env.AIA_JAVA_HOME ?? process.env.JAVA_HOME ?? null;
  if (home) {
    candidates.push(path.join(home, 'bin', WINDOWS ? 'java.exe' : 'java'));
  }
  for (const candidate of candidates) {
    if (exists(candidate)) {
      return { bin: candidate, home: home ?? path.dirname(path.dirname(candidate)) };
    }
  }
  if (candidates.length > 0) {
    warnings.push(
      'JAVA_HOME 指向的目录里找不到 java 可执行文件，将尝试使用 PATH 上的 java。',
    );
  } else {
    warnings.push('未设置 JAVA_HOME，将尝试使用 PATH 上的 java（需要 JDK 17 及以上）。');
  }
  return { bin: 'java', home: null };
}

export function resolveLayout(): Layout {
  const packaged = app.isPackaged;
  const warnings: string[] = [];
  // 默认使用 OS 规定的 userData 目录；AIA_USER_DATA_DIR 仅作为开发/受限环境的逃生开关
  // （例如 %APPDATA% 被安全策略限制时，可显式指到一个可写目录）。
  if (process.env.AIA_USER_DATA_DIR) {
    const override = path.resolve(process.env.AIA_USER_DATA_DIR);
    fs.mkdirSync(override, { recursive: true });
    app.setPath('userData', override);
  }
  const userDataDir = app.getPath('userData');
  const runtimeDir = path.join(userDataDir, 'runtime');
  const logsDir = path.join(userDataDir, 'logs');

  const javaBinInfo = resolveJavaBin(warnings);

  const javaJarCandidates = packaged
    ? [path.join(resourceRoot(), 'services', 'answer-java-api.jar')]
    : [path.join(repositoryRoot(), 'services', 'java-api', 'target', 'answer-java-api.jar')];
  const javaJar = javaJarCandidates.find((candidate) => exists(candidate)) ?? null;
  if (!javaJar) {
    warnings.push(
      `未找到 Java 服务包（期望位置：${javaJarCandidates[0]}）。请先执行 scripts/mvn.ps1 package 构建。`,
    );
  }

  const pythonCandidates = packaged
    ? [path.join(resourceRoot(), 'python-ai', WINDOWS ? 'python.exe' : 'python')]
    : [
        path.join(repositoryRoot(), 'services', 'python-ai', '.venv', WINDOWS ? 'Scripts' : 'bin',
          WINDOWS ? 'python.exe' : 'python'),
      ];
  const pythonExe = pythonCandidates.find((candidate) => exists(candidate)) ?? null;
  const pythonAppDir = packaged
    ? path.join(resourceRoot(), 'python-ai')
    : path.join(repositoryRoot(), 'services', 'python-ai');
  if (!pythonExe) {
    warnings.push(
      '未找到 Python AI 服务虚拟环境（services/python-ai/.venv）。AI 讲解与主观题建议分将不可用，答题主流程不受影响。',
    );
  }

  const rendererIndexCandidates = packaged
    ? [path.join(resourceRoot(), 'renderer', 'index.html')]
    : [path.join(repositoryRoot(), 'apps', 'renderer', 'dist', 'index.html')];
  const rendererIndexHtml = rendererIndexCandidates.find((candidate) => exists(candidate)) ?? null;
  if (!rendererIndexHtml && packaged) {
    warnings.push(`未找到前端构建产物（期望位置：${rendererIndexCandidates[0]}）。`);
  }

  const devRendererUrl = process.env.AIA_RENDERER_URL ?? (packaged ? null : 'http://127.0.0.1:5173');

  return {
    packaged,
    userDataDir,
    runtimeDir,
    logsDir,
    javaEndpointFile: path.join(runtimeDir, 'java-endpoint.json'),
    pythonEndpointFile: path.join(runtimeDir, 'python-endpoint.json'),
    javaJar,
    javaBin: javaBinInfo.bin,
    javaHome: javaBinInfo.home,
    pythonExe: exists(pythonExe) ? pythonExe : null,
    pythonAppDir,
    rendererIndexHtml,
    devRendererUrl,
    warnings,
  };
}

/** 读取端点文件（实现见 endpoints.ts，那里不依赖 Electron 以便被测试脚本复用）。 */
export { readEndpointFile } from './endpoints';

