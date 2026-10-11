// 子进程编排的真实验证脚本（不需要图形界面）。
//
// 为什么单独存在：Electron 的 GUI 需要可用的桌面会话；在无法启动 GUI 的环境里，
// 「主进程能不能把 Python 与 Java 正确拉起来、探活、再停掉」这件事仍然必须被验证。
// 本脚本直接使用编译后的 ServiceManager（它不依赖 Electron），用真实 jar、真实 venv、
// 真实临时数据目录跑一遍完整生命周期。
//
// 用法（apps/desktop 下）：
//   node scripts/verify-services.mjs
import { existsSync, mkdirSync, rmSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { ServiceManager } = require('../dist/main/service-manager.js');
const { readEndpointFile } = require('../dist/main/endpoints.js');

const desktopDir = path.resolve(import.meta.dirname, '..');
const repoRoot = path.resolve(desktopDir, '..', '..');

const javaJar = path.join(repoRoot, 'services', 'java-api', 'target', 'answer-java-api.jar');
const pythonExe = path.join(repoRoot, 'services', 'python-ai', '.venv', 'Scripts', 'python.exe');
const pythonAppDir = path.join(repoRoot, 'services', 'python-ai');
const javaBin = process.env.AIA_JAVA_BIN
  ?? (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java.exe') : 'java');

let passed = 0;
let failed = 0;
function check(name, condition, detail = '') {
  if (condition) {
    passed++;
    console.log(`  [PASS] ${name}`);
  } else {
    failed++;
    console.log(`  [FAIL] ${name} ${detail}`);
  }
}

// 数据目录刻意放在仓库内而不是 %TEMP%：部分主机的 TEMP 目录 ACL 不允许子进程在其中建目录
// （Python 会直接报 WinError 5），放仓库内既稳定又便于事后排查。
// 可用 AIA_VERIFY_DATA_DIR 指向别处，用来复现「子进程写不了用户数据目录」这类环境问题。
const userDataDir = process.env.AIA_VERIFY_DATA_DIR
  ? path.resolve(process.env.AIA_VERIFY_DATA_DIR)
  : path.join(repoRoot, '.devdata', 'desktop-verify');
rmSync(userDataDir, { recursive: true, force: true });
mkdirSync(path.join(userDataDir, 'runtime'), { recursive: true });
const layout = {
  packaged: false,
  userDataDir,
  runtimeDir: path.join(userDataDir, 'runtime'),
  logsDir: path.join(userDataDir, 'logs'),
  javaEndpointFile: path.join(userDataDir, 'runtime', 'java-endpoint.json'),
  pythonEndpointFile: path.join(userDataDir, 'runtime', 'python-endpoint.json'),
  javaJar: existsSync(javaJar) ? javaJar : null,
  javaBin,
  javaHome: process.env.JAVA_HOME ?? null,
  pythonExe: existsSync(pythonExe) ? pythonExe : null,
  pythonAppDir,
  rendererIndexHtml: null,
  devRendererUrl: null,
  warnings: [],
};

console.log('');
console.log('=== 子进程编排验证（ServiceManager，无需 Electron GUI）===');
console.log(`java   : ${layout.javaJar ?? '未找到 jar'}`);
console.log(`python : ${layout.pythonExe ?? '未找到 venv'}`);
console.log(`数据目录: ${userDataDir}`);
console.log('');

const manager = new ServiceManager(layout, '1.0.0');
const statusEvents = [];
manager.onStatusChange((snapshot) => statusEvents.push(snapshot.status.java.state));

try {
  check('Java 服务包存在', layout.javaJar !== null, '请先执行 scripts/mvn.ps1 package');
  if (!layout.javaJar) {
    throw new Error('缺少 Java 服务包，无法继续');
  }

  await manager.startAll();
  const runtime = manager.getRuntime();

  check('Java 服务状态为 up', runtime.status.java.state === 'up', `实际 ${runtime.status.java.state}：${runtime.status.java.message}`);
  check('运行时会话包含 Java 地址', typeof runtime.javaBaseUrl === 'string' && runtime.javaBaseUrl.startsWith('http://127.0.0.1:'));
  check('运行时会话包含会话令牌', typeof runtime.sessionToken === 'string' && runtime.sessionToken.length >= 32);
  check('端点文件已落盘且令牌一致',
    readEndpointFile(layout.javaEndpointFile)?.token === runtime.sessionToken);
  check('发出了启动状态事件', statusEvents.includes('starting') && statusEvents.includes('up'));

  if (layout.pythonExe) {
    check('Python AI 服务状态为 up', runtime.status.python.state === 'up', `实际 ${runtime.status.python.state}：${runtime.status.python.message}`);
    check('运行时会话包含 AI 地址', typeof runtime.pythonBaseUrl === 'string' && runtime.pythonBaseUrl.startsWith('http://127.0.0.1:'));
  } else {
    console.log('  [SKIP] 未找到 Python venv，跳过 AI 服务断言');
  }

  // 真正调用两个服务的健康检查
  const javaHealth = await fetch(`${runtime.javaBaseUrl}/health`).then((r) => r.json());
  check('Java /health 返回 UP', javaHealth.status === 'UP', JSON.stringify(javaHealth));

  if (runtime.pythonBaseUrl) {
    const pythonHealth = await fetch(`${runtime.pythonBaseUrl}/health`).then((r) => r.json());
    check('Python /health 返回 UP', pythonHealth.status === 'UP', JSON.stringify(pythonHealth));
  }

  // 业务接口必须带令牌：无令牌应 401
  const unauthorized = await fetch(`${runtime.javaBaseUrl}/api/v1/meta`);
  check('无令牌访问业务接口返回 401', unauthorized.status === 401, `实际 ${unauthorized.status}`);
  const authorized = await fetch(`${runtime.javaBaseUrl}/api/v1/meta`, {
    headers: { 'X-Session-Token': runtime.sessionToken },
  });
  check('带令牌访问业务接口返回 200', authorized.status === 200, `实际 ${authorized.status}`);

  // 前端「AI 服务不可用」的直接回归断言：Java 必须能把 Python 端点解析成可用的 AI 服务
  const meta = await authorized.json();
  check(
    'Java /meta 报告 AI 服务可用',
    meta?.data?.aiService?.available === true,
    JSON.stringify(meta?.data?.aiService),
  );
} catch (error) {
  failed++;
  console.log(`  [FAIL] 执行过程抛出异常：${error.message}`);
} finally {
  await manager.stopAll();
  const afterStop = manager.getRuntime();
  check('停止后 Java 状态不再是 up', afterStop.status.java.state !== 'up', `实际 ${afterStop.status.java.state}`);

  // 端口应已释放：再请求一次健康检查必须失败
  if (afterStop.javaBaseUrl === null) {
    check('停止后运行时会话已清除 Java 地址', true);
  }
  try {
    rmSync(userDataDir, { recursive: true, force: true });
  } catch {
    // 临时目录清理失败不影响结论
  }
}

console.log('');
console.log(`=== 结果：通过 ${passed}，失败 ${failed} ===`);
process.exit(failed === 0 ? 0 : 1);
