/** 答题 store：作答状态、自动保存（防抖）、提交、主观题 AI 建议分轮询与人工确认 */
import { defineStore } from 'pinia';
import { computed, ref, shallowRef } from 'vue';

import * as attemptsApi from '@/api/attempts';
import * as tasksApi from '@/api/tasks';
import { ApiError, toApiError } from '@/api/client';
import { resolveRuntime } from '@/api/runtime';
import type {
  AiGradeEnqueueResponse,
  AttemptResult,
  AttemptSheet,
  ManualGradeRequest
} from '@/types/attempt';
import type { QuestionType } from '@/types/common';
import type { AsyncTask } from '@/types/task';
import type { QuestionAnswer } from '@/types/question';
import { createEmptyUserAnswer, isUserAnswerEmpty } from '@/utils/answer';
import { describeError, type UiErrorInfo } from '@/utils/error';
import { isTaskSettled } from '@/types/task';

export type SaveState = 'idle' | 'pending' | 'saving' | 'saved' | 'failed';

interface LocalSaveEntry {
  state: SaveState;
  savedAt: string | null;
  message: string | null;
}

const AUTOSAVE_DEBOUNCE_MS = 800;
/**
 * AI 评分任务轮询参数。
 * 首次 1.2s（模型通常几百毫秒响应），之后 2.5s 一次，总预算约 18 秒；
 * 超预算后不再阻塞界面，提示用户稍后刷新（任务仍在后台重试）。
 */
const AI_POLL_FIRST_DELAY_MS = 1200;
const AI_POLL_INTERVAL_MS = 2500;
const AI_POLL_MAX_ATTEMPTS = 8;

export interface AiGradeRunResult {
  /** 排队的任务数 */
  pendingCount: number;
  taskIds: string[];
  /** 是否全部任务都已到终态 */
  settled: boolean;
  succeeded: number;
  failed: number;
  /** 未在超时内结束的任务数 */
  stillRunning: number;
  tasks: AsyncTask[];
  /** 面向用户的提示（失败、超时或成功） */
  message: string | null;
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms);
  });
}

export const useAttemptStore = defineStore('attempt', () => {
  const sheet = shallowRef<AttemptSheet | null>(null);
  const result = shallowRef<AttemptResult | null>(null);
  const loading = ref(false);
  const error = ref<UiErrorInfo | null>(null);
  /** snapshotQuestionId → 本地作答 */
  const answers = ref<Record<string, QuestionAnswer>>({});
  /** snapshotQuestionId → 保存状态 */
  const saveStates = ref<Record<string, LocalSaveEntry>>({});
  const submitting = ref(false);
  const submitError = ref<UiErrorInfo | null>(null);
  /** 提交使用的幂等键，重试提交时复用 */
  const submitKey = ref<string | null>(null);
  /** snapshotQuestionId → 是否正在人工评分请求中 */
  const gradingInFlight = ref<Record<string, boolean>>({});
  /** 整体 AI 建议分流程是否进行中 */
  const aiGrading = ref(false);
  const aiGradeMessage = ref<string | null>(null);
  const lastSubmitAt = ref<string | null>(null);

  const timers = new Map<string, number>();

  const status = computed(() => sheet.value?.status ?? null);
  const editable = computed(() => status.value === 'in_progress');
  const questions = computed(() => sheet.value?.questions ?? []);

  /** 未作答题目的 snapshotQuestionId 列表 */
  const unansweredIds = computed(() => {
    const current = sheet.value;
    if (!current) return [];
    return current.questions
      .filter((question) =>
        isUserAnswerEmpty(answers.value[question.snapshotQuestionId], question.questionType)
      )
      .map((question) => question.snapshotQuestionId);
  });

  const answeredCount = computed(() => questions.value.length - unansweredIds.value.length);

  const hasUnsavedChanges = computed(() =>
    Object.values(saveStates.value).some(
      (entry) => entry.state === 'pending' || entry.state === 'failed'
    )
  );

  const savingCount = computed(
    () =>
      Object.values(saveStates.value).filter(
        (entry) => entry.state === 'saving' || entry.state === 'pending'
      ).length
  );

  const failedSaves = computed(() =>
    Object.entries(saveStates.value)
      .filter(([, entry]) => entry.state === 'failed')
      .map(([id]) => id)
  );

  function setSaveState(id: string, patch: Partial<LocalSaveEntry>): void {
    const current = saveStates.value[id] ?? { state: 'idle', savedAt: null, message: null };
    saveStates.value = { ...saveStates.value, [id]: { ...current, ...patch } };
  }

  /**
   * 载入答题卡；服务端已保存的作答会被恢复（刷新后可继续）。
   * 未提交时 `questions[].answer` / `explanation` 为 `null`（服务端不泄露答案），
   * 因此作答一律取自 `answers[]`，不依赖 `questions[].answer`。
   */
  async function load(attemptId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const loaded = await attemptsApi.getAttemptSheet(attemptId);
      sheet.value = loaded;

      const next: Record<string, QuestionAnswer> = {};
      const states: Record<string, LocalSaveEntry> = {};
      for (const question of loaded.questions) {
        const saved = loaded.answers.find(
          (item) => item.snapshotQuestionId === question.snapshotQuestionId
        );
        next[question.snapshotQuestionId] =
          saved?.answer ?? createEmptyUserAnswer(question.questionType);
        states[question.snapshotQuestionId] = {
          state: saved?.answer ? 'saved' : 'idle',
          savedAt: saved?.savedAt ?? null,
          message: null
        };
      }
      answers.value = next;
      saveStates.value = states;
      submitKey.value = null;
      aiGradeMessage.value = null;
    } catch (caught) {
      error.value = describeError(caught);
      sheet.value = null;
    } finally {
      loading.value = false;
    }
  }

  async function loadResult(attemptId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      result.value = await attemptsApi.getAttemptResult(attemptId);
    } catch (caught) {
      error.value = describeError(caught);
      result.value = null;
    } finally {
      loading.value = false;
    }
  }

  /** 立即保存单题（不做防抖） */
  async function saveAnswerNow(snapshotQuestionId: string): Promise<boolean> {
    const current = sheet.value;
    const answer = answers.value[snapshotQuestionId];
    if (!current || !answer || !editable.value) return false;

    setSaveState(snapshotQuestionId, { state: 'saving', message: null });
    try {
      const saved = await attemptsApi.saveAttemptAnswer(current.id, snapshotQuestionId, answer);
      setSaveState(snapshotQuestionId, {
        state: 'saved',
        savedAt: saved.savedAt ?? new Date().toISOString(),
        message: null
      });
      return true;
    } catch (caught) {
      const info = describeError(caught);
      setSaveState(snapshotQuestionId, { state: 'failed', message: info.text });
      return false;
    }
  }

  /** 防抖自动保存 */
  function queueSave(snapshotQuestionId: string): void {
    setSaveState(snapshotQuestionId, { state: 'pending', message: null });
    const existing = timers.get(snapshotQuestionId);
    if (existing !== undefined) window.clearTimeout(existing);
    const timer = window.setTimeout(() => {
      timers.delete(snapshotQuestionId);
      void saveAnswerNow(snapshotQuestionId);
    }, AUTOSAVE_DEBOUNCE_MS);
    timers.set(snapshotQuestionId, timer);
  }

  function setAnswer(snapshotQuestionId: string, answer: QuestionAnswer): void {
    answers.value = { ...answers.value, [snapshotQuestionId]: answer };
    queueSave(snapshotQuestionId);
  }

  /** 提交前把所有待保存/失败的作答立即落库 */
  async function flushPendingSaves(): Promise<number> {
    const pending = Object.entries(saveStates.value)
      .filter(
        ([, entry]) =>
          entry.state === 'pending' || entry.state === 'failed' || entry.state === 'saving'
      )
      .map(([id]) => id);
    for (const id of pending) {
      const timer = timers.get(id);
      if (timer !== undefined) {
        window.clearTimeout(timer);
        timers.delete(id);
      }
    }
    let failed = 0;
    for (const id of pending) {
      const ok = await saveAnswerNow(id);
      if (!ok) failed += 1;
    }
    return failed;
  }

  /** 提交作答；幂等键在重试时复用，服务端同键重放并回 `Idempotent-Replay: true` */
  async function submit(): Promise<AttemptResult | null> {
    const current = sheet.value;
    if (!current) return null;
    submitting.value = true;
    submitError.value = null;
    try {
      await flushPendingSaves();
      if (!submitKey.value) submitKey.value = `submit-${current.id}-${Date.now()}`;
      const submitted = await attemptsApi.submitAttempt(current.id, submitKey.value);
      result.value = submitted;
      lastSubmitAt.value = new Date().toISOString();
      sheet.value = {
        ...current,
        status: submitted.attempt.status,
        totalScore: submitted.attempt.totalScore,
        submittedAt: submitted.attempt.submittedAt,
        gradedAt: submitted.attempt.gradedAt
      };
      return submitted;
    } catch (caught) {
      submitError.value = describeError(caught);
      return null;
    } finally {
      submitting.value = false;
    }
  }

  async function abandon(): Promise<boolean> {
    const current = sheet.value;
    if (!current) return false;
    try {
      const updated = await attemptsApi.abandonAttempt(current.id);
      sheet.value = updated;
      return true;
    } catch (caught) {
      error.value = describeError(caught);
      return false;
    }
  }

  /**
   * 轮询任务直到全部终态或超出预算。
   * 任务可能在 `retry_wait`（AI 不可用时的退避重试）停留数分钟，
   * 因此本函数**有预算上限**：超时后返回当前快照，由界面提示「稍后刷新」，
   * 不阻塞用户操作。
   */
  async function pollTasks(taskIds: string[]): Promise<AsyncTask[]> {
    const latest = new Map<string, AsyncTask>();
    let delayMs = AI_POLL_FIRST_DELAY_MS;

    for (let attempt = 0; attempt < AI_POLL_MAX_ATTEMPTS; attempt += 1) {
      await delay(delayMs);
      delayMs = AI_POLL_INTERVAL_MS;

      let allSettled = true;
      for (const taskId of taskIds) {
        const cached = latest.get(taskId);
        if (cached && isTaskSettled(cached.status)) continue;
        try {
          const task = await tasksApi.getTask(taskId);
          latest.set(taskId, task);
          if (!isTaskSettled(task.status)) allSettled = false;
        } catch {
          allSettled = false;
        }
      }
      if (allSettled) break;
    }

    return taskIds
      .map((taskId) => latest.get(taskId))
      .filter((task): task is AsyncTask => task !== undefined);
  }

  /**
   * 主观题 AI 复核闭环：
   *   1. `POST /attempts/{id}/ai-grade` 排队任务（不改分数）
   *   2. 轮询每个 `GET /tasks/{taskId}` 直到终态或超时
   *   3. 刷新 `AttemptResult`：AI 只写 `gradingDetail.suggestedScore`，状态仍为 `pending_review`
   *      → 需在结果页「人工确认分数」调用 `gradeManually` 才生效
   */
  async function requestAiGrade(attemptId: string): Promise<AiGradeRunResult | null> {
    aiGrading.value = true;
    aiGradeMessage.value = null;
    try {
      const enqueued: AiGradeEnqueueResponse = await attemptsApi.requestAiGrade(attemptId);

      if (enqueued.taskIds.length === 0) {
        await loadResult(attemptId);
        const empty: AiGradeRunResult = {
          pendingCount: 0,
          taskIds: [],
          settled: true,
          succeeded: 0,
          failed: 0,
          stillRunning: 0,
          tasks: [],
          message: '没有待复核的主观题，无需 AI 评分。'
        };
        aiGradeMessage.value = empty.message;
        return empty;
      }

      const tasks = await pollTasks(enqueued.taskIds);
      await loadResult(attemptId);

      const succeeded = tasks.filter((task) => task.status === 'succeeded').length;
      const failed = tasks.filter((task) => task.status === 'failed').length;
      const stillRunning = tasks.filter((task) => !isTaskSettled(task.status)).length;

      let message: string | null = null;
      if (failed > 0 && succeeded === 0) {
        const first = tasks.find((task) => task.status === 'failed');
        message =
          first?.lastErrorMessage ??
          'AI 评分任务失败；成绩仍为「AI 建议分待复核」，可稍后重试或手动评分。';
      } else if (stillRunning > 0) {
        // retry_wait 通常意味着 AI 侧不可用（LLM_UNAVAILABLE 等），任务仍在后台退避重试
        const waiting = tasks.find((task) => !isTaskSettled(task.status));
        const errorCode = waiting?.lastErrorCode ?? null;
        const errorText = waiting?.lastErrorMessage ?? null;
        if (errorCode === 'LLM_UNAVAILABLE') {
          message =
            'AI 服务当前不可用，评分任务已在后台排队重试。成绩保持「AI 建议分待复核」，你也可以直接手动评分。' +
            (errorText ? `（${errorText}）` : '');
        } else if (errorCode === 'CREDENTIAL_MISSING') {
          message = '尚未配置可用的 LLM 档案，AI 评分无法进行。请先在 LLM 设置中配置档案与密钥，或直接手动评分。';
        } else if (errorCode) {
          message = `AI 评分任务仍在后台重试（${errorCode}）。可稍后刷新查看建议分，或直接手动评分。`;
        } else {
          message = `仍有 ${stillRunning} 个 AI 评分任务在执行，可稍后刷新结果页查看建议分，或直接手动评分。`;
        }
      } else if (succeeded > 0) {
        message = `AI 已生成 ${succeeded} 条建议分，需人工确认后才会生效。`;
      }
      aiGradeMessage.value = message;

      return {
        pendingCount: enqueued.pendingCount,
        taskIds: enqueued.taskIds,
        settled: stillRunning === 0,
        succeeded,
        failed,
        stillRunning,
        tasks,
        message
      };
    } catch (caught) {
      const apiError = caught instanceof ApiError ? caught : toApiError(caught);
      const info = describeError(apiError);
      aiGradeMessage.value =
        apiError.code === 'LLM_UNAVAILABLE'
          ? 'AI 能力当前不可用，成绩保持「AI 建议分待复核」。主流程不受影响。'
          : info.text;
      return null;
    } finally {
      aiGrading.value = false;
    }
  }

  /** 人工确认或覆盖某道主观题的分数；返回重算后的完整结果 */
  async function gradeManually(
    attemptId: string,
    snapshotQuestionId: string,
    payload: ManualGradeRequest
  ): Promise<AttemptResult | null> {
    gradingInFlight.value = { ...gradingInFlight.value, [snapshotQuestionId]: true };
    try {
      const updated = await attemptsApi.gradeAnswerManually(attemptId, snapshotQuestionId, payload);
      result.value = updated;
      aiGradeMessage.value = `已人工确认得分 ${payload.score}，总分已重算。`;
      return updated;
    } catch (caught) {
      aiGradeMessage.value = describeError(caught).text;
      return null;
    } finally {
      const next = { ...gradingInFlight.value };
      delete next[snapshotQuestionId];
      gradingInFlight.value = next;
    }
  }

  function reset(): void {
    for (const timer of timers.values()) window.clearTimeout(timer);
    timers.clear();
    sheet.value = null;
    result.value = null;
    answers.value = {};
    saveStates.value = {};
    submitKey.value = null;
    submitError.value = null;
    error.value = null;
    gradingInFlight.value = {};
    aiGradeMessage.value = null;
  }

  /** 当前运行时地址（诊断展示用） */
  async function runtimeBaseUrl(): Promise<string> {
    const runtime = await resolveRuntime();
    return runtime.javaBaseUrl;
  }

  return {
    sheet,
    result,
    loading,
    error,
    answers,
    saveStates,
    submitting,
    submitError,
    gradingInFlight,
    aiGrading,
    aiGradeMessage,
    status,
    editable,
    questions,
    unansweredIds,
    answeredCount,
    hasUnsavedChanges,
    savingCount,
    failedSaves,
    load,
    loadResult,
    setAnswer,
    saveAnswerNow,
    queueSave,
    flushPendingSaves,
    submit,
    abandon,
    requestAiGrade,
    gradeManually,
    reset,
    runtimeBaseUrl
  };
});

/** 供组件按题型取空作答（避免组件直接依赖 utils） */
export function emptyAnswerFor(type: QuestionType): QuestionAnswer {
  return createEmptyUserAnswer(type);
}
