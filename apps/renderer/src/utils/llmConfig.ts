/**
 * `contracts/json-schema/setting.schema.json` 的前端校验实现（契约 §6 v1.1，文件驱动）。
 *
 * 为什么不是 JSON Schema 库：技术选型冻结了运行时依赖（Vue/Router/Pinia/Element Plus），
 * 不允许引入 ajv，因此这里按 schema 逐条手写等价校验，错误路径与 schema 属性名一致
 * （`schemaVersion` / `application.*` / `llm.defaultProfileId` / `llm.profiles[i].*`），
 * 便于与后端 `CONFIG_FILE_INVALID` / `VALIDATION_ERROR` 的 `errors[].path`、`details[].path` 直接对齐。
 * 跨字段规则（defaultProfileId 必须存在、id 唯一）按 schema 的 `$comment` 一并检查。
 */
import {
  LLM_PROVIDERS,
  SETTING_SCHEMA_VERSION,
  type LlmSettingFile,
  type LlmValidateError
} from '@/types/llm';

const ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
const BASE_URL_PATTERN = /^https?:\/\/[^\s]+$/;
const APPLICATION_LANGUAGES = ['zh-CN', 'en-US'] as const;

/** `apiKey` 明文上限（`setting.schema.json`：maxLength 4096） */
const API_KEY_MAX_LENGTH = 4096;

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value);
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

export interface LlmConfigValidationResult {
  valid: boolean;
  errors: LlmValidateError[];
  summary: {
    profileCount: number;
    providers: string[];
  };
}

/**
 * 校验一份 setting.json（或仅含 `llm` 段的同构文档）内容。
 *
 * 注意：这里**只做本地预检**，不会读取也不会修改 `<UserData>/config/setting.json`；
 * 服务端 `validate` / `import/preview` 才是权威校验。
 */
export function validateLlmConfigFile(input: unknown): LlmConfigValidationResult {
  const errors: LlmValidateError[] = [];

  if (!isPlainObject(input)) {
    return {
      valid: false,
      errors: [{ path: '', message: '配置根节点必须是 JSON 对象。' }],
      summary: { profileCount: 0, providers: [] }
    };
  }

  const allowedRootKeys = ['schemaVersion', 'application', 'llm'];
  for (const key of Object.keys(input)) {
    if (!allowedRootKeys.includes(key)) {
      errors.push({ path: key, message: `不允许的字段：${key}（additionalProperties=false）。` });
    }
  }

  if (!('schemaVersion' in input)) {
    errors.push({ path: 'schemaVersion', message: '缺少必填字段 schemaVersion。' });
  } else if (!isInteger(input.schemaVersion)) {
    errors.push({ path: 'schemaVersion', message: 'schemaVersion 必须是整数。' });
  } else if (input.schemaVersion !== SETTING_SCHEMA_VERSION) {
    errors.push({
      path: 'schemaVersion',
      message: `不支持的配置版本 ${String(input.schemaVersion)}，当前仅支持 ${SETTING_SCHEMA_VERSION}（对应错误码 CONFIG_SCHEMA_UNSUPPORTED）。`
    });
  }

  if ('application' in input && input.application !== undefined && input.application !== null) {
    const application = input.application;
    if (!isPlainObject(application)) {
      errors.push({ path: 'application', message: 'application 必须是对象。' });
    } else {
      for (const key of Object.keys(application)) {
        if (key !== 'language') {
          errors.push({ path: `application.${key}`, message: `不允许的字段：${key}。` });
        }
      }
      const language = application.language;
      if (
        language !== undefined &&
        (typeof language !== 'string' ||
          !(APPLICATION_LANGUAGES as readonly string[]).includes(language))
      ) {
        errors.push({
          path: 'application.language',
          message: `language 必须是 ${APPLICATION_LANGUAGES.join(' / ')} 之一。`
        });
      }
    }
  }

  const rawLlm = input.llm;
  if (!isPlainObject(rawLlm)) {
    errors.push({ path: 'llm', message: '缺少必填字段 llm，且必须是对象。' });
    return { valid: false, errors, summary: { profileCount: 0, providers: [] } };
  }
  for (const key of Object.keys(rawLlm)) {
    if (key !== 'defaultProfileId' && key !== 'profiles') {
      errors.push({ path: `llm.${key}`, message: `不允许的字段：${key}。` });
    }
  }

  const defaultProfileId = rawLlm.defaultProfileId;
  if (defaultProfileId !== undefined && defaultProfileId !== null) {
    if (typeof defaultProfileId !== 'string') {
      errors.push({ path: 'llm.defaultProfileId', message: 'defaultProfileId 必须是字符串或 null。' });
    } else if (defaultProfileId.length < 1 || defaultProfileId.length > 64) {
      errors.push({
        path: 'llm.defaultProfileId',
        message: 'defaultProfileId 长度必须在 1-64 之间。'
      });
    } else if (!ID_PATTERN.test(defaultProfileId)) {
      errors.push({
        path: 'llm.defaultProfileId',
        message: 'defaultProfileId 需匹配 ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$。'
      });
    }
  }

  const rawProfiles = rawLlm.profiles;
  const profiles: unknown[] = Array.isArray(rawProfiles) ? rawProfiles : [];
  if (!Array.isArray(rawProfiles)) {
    errors.push({ path: 'llm.profiles', message: '缺少必填字段 llm.profiles，且必须是数组。' });
  } else if (rawProfiles.length > 50) {
    errors.push({ path: 'llm.profiles', message: 'llm.profiles 最多 50 项。' });
  }

  const seenIds = new Set<string>();
  const providers = new Set<string>();

  profiles.forEach((rawProfile, index) => {
    const prefix = `llm.profiles[${index}]`;
    if (!isPlainObject(rawProfile)) {
      errors.push({ path: prefix, message: '每个 profile 必须是 JSON 对象。' });
      return;
    }

    const allowedProfileKeys = [
      'id',
      'name',
      'provider',
      'baseUrl',
      'model',
      'apiKey',
      'temperature',
      'timeoutSeconds',
      'connectTimeoutSeconds',
      'allowLocalNetwork',
      'parameters'
    ];
    for (const key of Object.keys(rawProfile)) {
      if (!allowedProfileKeys.includes(key)) {
        errors.push({ path: `${prefix}.${key}`, message: `不允许的字段：${key}。` });
      }
    }

    const id = rawProfile.id;
    if (typeof id !== 'string' || id.length === 0) {
      errors.push({ path: `${prefix}.id`, message: 'id 必填且必须是非空字符串。' });
    } else if (!ID_PATTERN.test(id)) {
      errors.push({ path: `${prefix}.id`, message: 'id 需匹配 ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$。' });
    } else if (seenIds.has(id)) {
      errors.push({ path: `${prefix}.id`, message: `profile id 重复：${id}。` });
    } else {
      seenIds.add(id);
    }

    const name = rawProfile.name;
    if (typeof name !== 'string' || name.length < 1 || name.length > 64) {
      errors.push({ path: `${prefix}.name`, message: 'name 必填，长度 1-64。' });
    }

    const provider = rawProfile.provider;
    if (typeof provider !== 'string') {
      errors.push({ path: `${prefix}.provider`, message: 'provider 必填。' });
    } else if (!(LLM_PROVIDERS as readonly string[]).includes(provider)) {
      errors.push({
        path: `${prefix}.provider`,
        message: `provider 必须是 ${LLM_PROVIDERS.join(' / ')} 之一。`
      });
    } else {
      providers.add(provider);
    }

    const baseUrl = rawProfile.baseUrl;
    if (typeof baseUrl !== 'string' || baseUrl.length < 8 || baseUrl.length > 512) {
      errors.push({ path: `${prefix}.baseUrl`, message: 'baseUrl 必填，长度 8-512。' });
    } else if (!BASE_URL_PATTERN.test(baseUrl)) {
      errors.push({
        path: `${prefix}.baseUrl`,
        message: 'baseUrl 必须以 http:// 或 https:// 开头且不含空白。'
      });
    } else if (baseUrl.endsWith('/')) {
      errors.push({
        path: `${prefix}.baseUrl`,
        message: 'baseUrl 不应以斜杠结尾，例如 http://127.0.0.1:11434/v1。'
      });
    }

    const model = rawProfile.model;
    if (typeof model !== 'string' || model.length < 1 || model.length > 128) {
      errors.push({ path: `${prefix}.model`, message: 'model 必填，长度 1-128。' });
    }

    // 明文密钥：只校验形状与长度，绝不进入错误信息、日志或 UI 文案
    if ('apiKey' in rawProfile && rawProfile.apiKey !== undefined && rawProfile.apiKey !== null) {
      const apiKey = rawProfile.apiKey;
      if (typeof apiKey !== 'string') {
        errors.push({ path: `${prefix}.apiKey`, message: 'apiKey 必须是字符串、null 或省略。' });
      } else if (apiKey.length > API_KEY_MAX_LENGTH) {
        errors.push({
          path: `${prefix}.apiKey`,
          message: `apiKey 长度不得超过 ${API_KEY_MAX_LENGTH} 个字符。`
        });
      }
    }

    if ('temperature' in rawProfile && rawProfile.temperature !== undefined && rawProfile.temperature !== null) {
      const temperature = rawProfile.temperature;
      if (!isFiniteNumber(temperature) || temperature < 0 || temperature > 2) {
        errors.push({ path: `${prefix}.temperature`, message: 'temperature 必须是 0-2 的数字。' });
      }
    }

    if ('timeoutSeconds' in rawProfile && rawProfile.timeoutSeconds !== undefined && rawProfile.timeoutSeconds !== null) {
      const value = rawProfile.timeoutSeconds;
      if (!isInteger(value) || value < 1 || value > 900) {
        errors.push({ path: `${prefix}.timeoutSeconds`, message: 'timeoutSeconds 必须是 1-900 的整数。' });
      }
    }

    if (
      'connectTimeoutSeconds' in rawProfile &&
      rawProfile.connectTimeoutSeconds !== undefined &&
      rawProfile.connectTimeoutSeconds !== null
    ) {
      const value = rawProfile.connectTimeoutSeconds;
      if (!isInteger(value) || value < 1 || value > 120) {
        errors.push({
          path: `${prefix}.connectTimeoutSeconds`,
          message: 'connectTimeoutSeconds 必须是 1-120 的整数。'
        });
      }
    }

    if ('allowLocalNetwork' in rawProfile && rawProfile.allowLocalNetwork !== undefined && rawProfile.allowLocalNetwork !== null) {
      if (typeof rawProfile.allowLocalNetwork !== 'boolean') {
        errors.push({ path: `${prefix}.allowLocalNetwork`, message: 'allowLocalNetwork 必须是布尔值。' });
      }
    }

    if ('parameters' in rawProfile && rawProfile.parameters !== undefined && rawProfile.parameters !== null) {
      const parameters = rawProfile.parameters;
      if (!isPlainObject(parameters)) {
        errors.push({ path: `${prefix}.parameters`, message: 'parameters 必须是对象。' });
      } else {
        const allowedParamKeys = ['topP', 'maxTokens', 'stream'];
        for (const key of Object.keys(parameters)) {
          if (!allowedParamKeys.includes(key)) {
            errors.push({ path: `${prefix}.parameters.${key}`, message: `不允许的字段：${key}。` });
          }
        }
        const topP = parameters.topP;
        if (topP !== undefined && (!isFiniteNumber(topP) || topP < 0 || topP > 1)) {
          errors.push({ path: `${prefix}.parameters.topP`, message: 'topP 必须是 0-1 的数字。' });
        }
        const maxTokens = parameters.maxTokens;
        if (maxTokens !== undefined && (!isInteger(maxTokens) || maxTokens < 1 || maxTokens > 131072)) {
          errors.push({ path: `${prefix}.parameters.maxTokens`, message: 'maxTokens 必须是 1-131072 的整数。' });
        }
        const stream = parameters.stream;
        if (stream !== undefined && typeof stream !== 'boolean') {
          errors.push({ path: `${prefix}.parameters.stream`, message: 'stream 必须是布尔值。' });
        }
      }
    }
  });

  if (typeof defaultProfileId === 'string' && defaultProfileId.length > 0) {
    if (!seenIds.has(defaultProfileId)) {
      errors.push({
        path: 'llm.defaultProfileId',
        message: `defaultProfileId「${defaultProfileId}」未指向任何已定义的 profile。`
      });
    }
  }

  const profileCount = profiles.length;
  return {
    valid: errors.length === 0,
    errors,
    summary: { profileCount, providers: [...providers] }
  };
}

export interface JsonParseFailure {
  ok: false;
  message: string;
  line: number | null;
  column: number | null;
}

export interface JsonParseSuccess {
  ok: true;
  value: unknown;
}

/** 解析 JSON 文本并给出行列位置（对应错误码 CONFIG_JSON_INVALID） */
export function parseJsonWithPosition(text: string): JsonParseSuccess | JsonParseFailure {
  try {
    return { ok: true, value: JSON.parse(text) as unknown };
  } catch (error) {
    const raw = error instanceof Error ? error.message : String(error);
    const match = /position\s+(\d+)/i.exec(raw);
    if (!match || !match[1]) {
      return { ok: false, message: raw, line: null, column: null };
    }
    const position = Number(match[1]);
    const before = text.slice(0, position);
    const line = before.split('\n').length;
    const lastNewline = before.lastIndexOf('\n');
    const column = position - lastNewline;
    return { ok: false, message: raw, line, column };
  }
}

/**
 * 类型守卫：把任意 JSON 收窄为 `LlmSettingFile`。
 * 注意：只有通过本地预检的文档才会返回非 null；含 `apiKey` 明文的文档也可能通过，
 * 调用方**不得**把它写进日志或页面（导入预览只把它交给后端）。
 */
export function asLlmSettingFile(value: unknown): LlmSettingFile | null {
  const result = validateLlmConfigFile(value);
  if (!result.valid) return null;
  return value as unknown as LlmSettingFile;
}
