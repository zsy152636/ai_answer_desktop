/** 日期/数值/文本格式化工具 */

function pad(value: number): string {
  return value < 10 ? `0${value}` : String(value);
}

/** UTC ISO-8601 → 本地 `YYYY-MM-DD HH:mm:ss` */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours()
  )}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** UTC ISO-8601 → 本地 `YYYY-MM-DD` */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** 相对时间描述，例如「3 分钟前」 */
export function formatRelative(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  const diffMs = Date.now() - date.getTime();
  const future = diffMs < 0;
  const abs = Math.abs(diffMs);
  const minute = 60_000;
  const hour = 60 * minute;
  const day = 24 * hour;

  let text: string;
  if (abs < minute) text = '刚刚';
  else if (abs < hour) text = `${Math.floor(abs / minute)} 分钟`;
  else if (abs < day) text = `${Math.floor(abs / hour)} 小时`;
  else text = `${Math.floor(abs / day)} 天`;

  if (text === '刚刚') return text;
  return future ? `${text}后` : `${text}前`;
}

/** 字节数 → 可读体积 */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '—';
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = bytes / 1024;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return `${value.toFixed(value >= 100 ? 0 : 1)} ${units[index]}`;
}

/** 秒 → 可读时长 */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds)) return '—';
  if (seconds < 60) return `${Math.round(seconds)} 秒`;
  const minutes = Math.floor(seconds / 60);
  const rest = Math.round(seconds % 60);
  if (minutes < 60) return rest > 0 ? `${minutes} 分 ${rest} 秒` : `${minutes} 分`;
  const hours = Math.floor(minutes / 60);
  return `${hours} 小时 ${minutes % 60} 分`;
}

/** 分数展示，null 显示为「待评分」 */
export function formatScore(score: number | null | undefined, maxScore?: number | null): string {
  if (score === null || score === undefined) return '待评分';
  const base = typeof score === 'number' ? trimNumber(score) : String(score);
  if (maxScore === null || maxScore === undefined) return base;
  return `${base} / ${trimNumber(maxScore)}`;
}

export function trimNumber(value: number): string {
  if (Number.isInteger(value)) return String(value);
  return String(Math.round(value * 100) / 100);
}

/** 截断长文本，保留可读长度 */
export function truncate(text: string | null | undefined, max = 60): string {
  if (!text) return '';
  const plain = text.replace(/\s+/g, ' ').trim();
  return plain.length > max ? `${plain.slice(0, max)}…` : plain;
}
