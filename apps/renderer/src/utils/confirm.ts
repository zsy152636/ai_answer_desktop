/** 破坏性操作的统一确认框封装（Element Plus MessageBox） */
import { ElMessage, ElMessageBox } from 'element-plus';

export interface ConfirmOptions {
  title?: string;
  confirmText?: string;
  cancelText?: string;
  type?: 'warning' | 'info' | 'error' | 'success';
  /** 输入框占位；传入后要求用户输入完全一致的文本才会继续 */
  requireTypingText?: string;
}

/** 通用确认框；用户取消返回 false，不抛出 */
export async function confirmAction(message: string, options: ConfirmOptions = {}): Promise<boolean> {
  try {
    if (options.requireTypingText !== undefined) {
      await ElMessageBox.prompt(message, options.title ?? '请确认', {
        confirmButtonText: options.confirmText ?? '确认',
        cancelButtonText: options.cancelText ?? '取消',
        type: options.type ?? 'warning',
        inputPlaceholder: `请输入「${options.requireTypingText}」以继续`,
        inputValidator: (value: string) => value === options.requireTypingText,
        inputErrorMessage: '输入内容不匹配，操作已取消。'
      });
      return true;
    }
    await ElMessageBox.confirm(message, options.title ?? '请确认', {
      confirmButtonText: options.confirmText ?? '确认',
      cancelButtonText: options.cancelText ?? '取消',
      type: options.type ?? 'warning'
    });
    return true;
  } catch {
    return false;
  }
}

/** 强确认（需要用户输入指定文本），用于恢复备份等高风险操作 */
export function confirmDestructive(message: string, typingText: string, title = '高风险操作'): Promise<boolean> {
  return confirmAction(message, { title, requireTypingText: typingText, type: 'error' });
}

export function toastSuccess(message: string): void {
  ElMessage.success(message);
}

export function toastInfo(message: string): void {
  ElMessage.info(message);
}

export function toastWarning(message: string): void {
  ElMessage.warning(message);
}

export function toastError(message: string): void {
  ElMessage.error({ message, duration: 5000, showClose: true });
}
