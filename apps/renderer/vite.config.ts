import { fileURLToPath, URL } from 'node:url';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

import vue from '@vitejs/plugin-vue';
import { defineConfig, type Plugin } from 'vite';

/** 仓库根目录：apps/renderer/vite.config.ts -> ../../.. */
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');

/** Java 服务落盘的运行时端点描述文件 */
const DEFAULT_ENDPOINT_FILE = resolve(repoRoot, '.runtime', 'java-endpoint.json');

interface JavaEndpointFile {
  port?: number;
  baseUrl?: string;
  token?: string;
  sessionToken?: string;
  [key: string]: unknown;
}

function readEndpointFile(): { ok: true; raw: JavaEndpointFile; file: string } | { ok: false; file: string; reason: string } {
  const file = process.env.AIA_ENDPOINT_FILE?.trim()
    ? resolve(process.env.AIA_ENDPOINT_FILE.trim())
    : DEFAULT_ENDPOINT_FILE;

  if (!existsSync(file)) {
    return {
      ok: false,
      file,
      reason: `未找到 Java 运行时端点文件：${file}。请先启动 Java 服务（scripts/dev-start），或设置环境变量 AIA_ENDPOINT_FILE 指向该文件。`
    };
  }

  try {
    const text = readFileSync(file, 'utf8');
    const parsed: unknown = JSON.parse(text);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return { ok: false, file, reason: `端点文件不是 JSON 对象：${file}` };
    }
    return { ok: true, raw: parsed as JavaEndpointFile, file };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return { ok: false, file, reason: `端点文件解析失败：${message}` };
  }
}

function asNonEmptyString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : undefined;
}

/**
 * 仅 dev 生效的 `GET /__app_runtime` shim。
 * 把 `<repo>/.runtime/java-endpoint.json` 的 port/baseUrl/token 映射为
 * `{ javaBaseUrl, sessionToken }`，供浏览器直接开发调试使用。
 */
function appRuntimeShimPlugin(): Plugin {
  return {
    name: 'ai-answer:app-runtime-shim',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use('/__app_runtime', (req, res) => {
        const send = (status: number, payload: unknown): void => {
          const body = JSON.stringify(payload);
          res.statusCode = status;
          res.setHeader('Content-Type', 'application/json; charset=utf-8');
          res.setHeader('Cache-Control', 'no-store');
          res.end(body);
        };

        if (req.method !== 'GET' && req.method !== 'HEAD') {
          send(405, { success: false, message: '仅支持 GET' });
          return;
        }

        const result = readEndpointFile();
        if (!result.ok) {
          server.config.logger.warn(`[app-runtime-shim] ${result.reason}`);
          send(503, { success: false, message: result.reason, endpointFile: result.file });
          return;
        }

        const { raw, file } = result;
        const port = typeof raw.port === 'number' ? raw.port : undefined;
        const baseUrl =
          asNonEmptyString(raw.baseUrl) ?? (port !== undefined ? `http://127.0.0.1:${port}` : undefined);
        const sessionToken = asNonEmptyString(raw.token) ?? asNonEmptyString(raw.sessionToken);

        if (!baseUrl || !sessionToken) {
          send(503, {
            success: false,
            message: `端点文件缺少 baseUrl/port 或 token 字段：${file}`,
            endpointFile: file
          });
          return;
        }

        send(200, {
          javaBaseUrl: baseUrl,
          sessionToken,
          source: 'dev-shim',
          endpointFile: file
        });
      });
    }
  };
}

export default defineConfig({
  base: './',
  plugins: [vue(), appRuntimeShimPlugin()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: false
  },
  preview: {
    host: '127.0.0.1',
    port: 4173
  },
  build: {
    target: 'esnext',
    outDir: 'dist',
    assetsDir: 'assets',
    sourcemap: false,
    chunkSizeWarningLimit: 1200,
    rollupOptions: {
      output: {
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          'element-plus': ['element-plus']
        }
      }
    }
  }
});
