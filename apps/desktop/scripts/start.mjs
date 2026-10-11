// 启动 Electron 的包装脚本。
//
// 为什么需要它：某些环境（CI、沙箱、被继承的 shell 会话）会设置 ELECTRON_RUN_AS_NODE=1，
// 这会让 electron.exe 退化成纯 Node 运行——主进程里 `require('electron')` 会解析到 npm 包
// （一个字符串路径），于是报 “Cannot read properties of undefined (reading 'whenReady')”。
// 这里在派生子进程前显式删除该变量，让应用无论从什么环境启动都能正常起来。
//
// 另外：主进程日志统一按 UTF-8 输出。Windows 控制台默认是 GBK（cp936），
// 不切换代码页就会把中文日志显示成乱码（子进程的编码在 ServiceManager 里单独强制为 UTF-8）。
import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import path from 'node:path';
import process from 'node:process';

function useUtf8Console() {
  if (process.platform !== 'win32') {
    return;
  }
  // 没有控制台时不切：既无意义，也会让 chcp 新建一个可见的控制台窗口
  if (!process.stdout.isTTY && !process.stderr.isTTY) {
    return;
  }
  try {
    // chcp 作用于整个控制台窗口，执行一次即可影响随后所有输出。
    // 这里刻意不传 windowsHide：它对应 CREATE_NO_WINDOW，会让子进程脱离当前控制台，
    // chcp 就变成空操作（这正是上一版没生效的原因）。
    spawnSync('chcp.com', ['65001'], { stdio: 'ignore' });
  } catch {
    // 无法切换时只影响显示，不影响功能，继续启动
  }
}

useUtf8Console();

const require = createRequire(import.meta.url);
const electronBinary = require('electron');

const env = { ...process.env };
delete env.ELECTRON_RUN_AS_NODE;

const args = process.argv.slice(2);
const child = spawn(electronBinary, ['.', ...args], {
  stdio: 'inherit',
  env,
  cwd: path.resolve(import.meta.dirname, '..'),
});

child.on('exit', (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal);
    return;
  }
  process.exit(code ?? 0);
});
