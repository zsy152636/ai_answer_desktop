/**
 * 契约 §9 异步任务（Java `SystemDtos.TaskView`）。
 */
import type { TaskStatus } from './common';

/** 契约 §9 AsyncTask */
export interface AsyncTask {
  id: string;
  taskType: string;
  status: TaskStatus;
  payload: Record<string, unknown> | null;
  result: Record<string, unknown> | null;
  attemptCount: number;
  maxAttempts: number;
  leaseOwner: string | null;
  leaseUntil: string | null;
  heartbeatAt: string | null;
  nextRunAt: string | null;
  lastErrorCode: string | null;
  lastErrorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TaskListQuery {
  page?: number;
  size?: number;
  status?: TaskStatus;
  taskType?: string;
}

/** 任务是否已进入终态（不再变化） */
export function isTaskSettled(status: TaskStatus): boolean {
  return status === 'succeeded' || status === 'failed' || status === 'cancelled';
}
