/** 契约 §9 异步任务 */
import { http } from './client';
import type { PageResult } from '@/types/common';
import type { AsyncTask, TaskListQuery } from '@/types/task';

export function listTasks(query: TaskListQuery): Promise<PageResult<AsyncTask>> {
  return http.get<PageResult<AsyncTask>>('/tasks', { ...query });
}

export function getTask(id: string): Promise<AsyncTask> {
  return http.get<AsyncTask>(`/tasks/${encodeURIComponent(id)}`);
}

export function retryTask(id: string): Promise<AsyncTask> {
  return http.post<AsyncTask>(`/tasks/${encodeURIComponent(id)}/retry`);
}

export function cancelTask(id: string): Promise<AsyncTask> {
  return http.post<AsyncTask>(`/tasks/${encodeURIComponent(id)}/cancel`);
}

/**
 * 任务类型常量。契约 §9 说明 v1 只实现 `ai_grade`，其余为占位。
 */
export const TASK_TYPE_AI_GRADE = 'ai_grade';
