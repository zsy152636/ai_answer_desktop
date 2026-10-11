#!/usr/bin/env node
/**
 * 把 esbuild / vite 的临时目录指向仓库内 `.tmp-build/`，然后执行给定的 npm script。
 *
 * 为什么需要它：在部分 Windows 环境中（受控沙箱、受限 ACL、某些杀软实时防护），
 * esbuild 无法删除 `%TEMP%` 下的临时文件，构建会以
 * `[vite:esbuild-transpile] remove ...\\Temp\\esbuild-xxx: Access is denied.` 失败。
 * 这是运行环境的权限问题，不是代码问题；把 TMP/TEMP 指到工程目录内即可绕开。
 *
 * 用法：node scripts/with-local-tmp.mjs build
 */
import { spawn } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const appRoot = resolve(here, '..');
const localTmp = join(appRoot, '.tmp-build');

mkdirSync(localTmp, { recursive: true });

const target = process.argv[2];
if (!target) {
  console.error('用法：node scripts/with-local-tmp.mjs <npm-script|命令> [参数...]');
  process.exit(2);
}

const passthrough = process.argv.slice(3);
const isScript = target !== 'exec' && passthrough.length === 0;
const args = isScript ? ['run', target] : ['exec', target, ...passthrough];

const env = { ...process.env, TMP: localTmp, TEMP: localTmp, TMPDIR: localTmp };

const child = spawn('pnpm', args, {
  cwd: appRoot,
  env,
  stdio: 'inherit',
  shell: process.platform === 'win32'
});

child.on('exit', (code, signal) => {
  if (signal) {
    console.error(`构建被信号中断：${signal}`);
    process.exit(1);
  }
  process.exit(code ?? 1);
});

child.on('error', (error) => {
  console.error(`无法启动 pnpm：${error.message}`);
  process.exit(1);
});
