// 包管理器守卫：本仓库的前端与桌面壳使用 pnpm 管理依赖。
//
// 为什么必须拦下来：pnpm 的 node_modules 是「符号链接 + .pnpm 内容寻址存储」结构，
// npm 无法解析这种布局，会先打一堆 ERESOLVE 警告，然后以
// `npm error Cannot read properties of null (reading 'matches')` 崩溃退出——
// 报错完全指不到真正的原因（用错了包管理器）。
// 这里在 preinstall 阶段就给出可执行的提示。
//
// 注意：只挂在 preinstall 上，`npm run build` / `npm start` 不受影响。
const userAgent = process.env.npm_config_user_agent ?? '';

// pnpm 的 UA 形如 "pnpm/11.2.2 npm/? node/v22.22.2 win32 x64"，
// npm 的 UA 形如 "npm/10.9.7 node/v22.22.2 win32 x64"。
const isNpm = /^npm\//.test(userAgent);
const isYarn = /^yarn\//.test(userAgent);

if (isNpm || isYarn) {
  const manager = isNpm ? 'npm' : 'yarn';
  console.error('');
  console.error(`[包管理器守卫] 本目录使用 pnpm 管理依赖，检测到你在用 ${manager}。`);
  console.error('');
  console.error('pnpm 的 node_modules 是符号链接 + 内容寻址存储结构，');
  console.error(`${manager} 无法解析它，会以 "Cannot read properties of null (reading 'matches')" 之类`);
  console.error('与本项目无关的错误崩溃。请改用：');
  console.error('');
  console.error('    pnpm install');
  console.error('');
  console.error('如果之前已经用 npm 跑过安装，请先执行 pnpm install 让依赖树回到 pnpm 结构。');
  console.error('（Electron 首次安装需要下载运行时二进制，国内网络可先设置：');
  console.error("    $env:ELECTRON_MIRROR='https://npmmirror.com/mirrors/electron/'）");
  console.error('');
  process.exit(1);
}
