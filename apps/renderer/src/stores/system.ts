/** 系统设置 store：数据目录、备份/恢复、完整性检查、日志（按 Java `SystemController` 对齐） */
import { defineStore } from 'pinia';
import { computed, ref, shallowRef } from 'vue';

import * as systemApi from '@/api/system';
import type {
  BackupCreateResponse,
  BackupEntry,
  IntegrityCheckResponse,
  LogFileEntry,
  LogTailResponse,
  SystemInfoResponse
} from '@/types/system';
import { describeError, type UiErrorInfo } from '@/utils/error';

export const useSystemStore = defineStore('system', () => {
  const info = shallowRef<SystemInfoResponse | null>(null);
  const loading = ref(false);
  const error = ref<UiErrorInfo | null>(null);
  const loaded = ref(false);

  const backupRunning = ref(false);
  const backupError = ref<UiErrorInfo | null>(null);
  const lastBackup = shallowRef<BackupCreateResponse | null>(null);

  const restoring = ref(false);
  const restoreError = ref<UiErrorInfo | null>(null);
  const restoreRequiresRestart = ref(false);

  const verifying = ref(false);
  const verifyError = ref<UiErrorInfo | null>(null);
  /** 恢复前的只读校验结果 */
  const verifyResult = shallowRef<IntegrityCheckResponse | null>(null);
  const verifiedFileName = ref<string | null>(null);

  const integrity = shallowRef<IntegrityCheckResponse | null>(null);
  const integrityRunning = ref(false);
  const integrityError = ref<UiErrorInfo | null>(null);

  const seeding = ref(false);
  const seedMessage = ref<string | null>(null);

  const logFiles = shallowRef<LogFileEntry[]>([]);
  const activeLogFile = ref<string | null>(null);
  const logTail = shallowRef<LogTailResponse | null>(null);
  const logLoading = ref(false);
  const logError = ref<UiErrorInfo | null>(null);

  const backups = computed<BackupEntry[]>(() => info.value?.backups ?? []);
  /** LLM 配置来源标识（契约 §8：固定 `setting.json`） */
  const configSource = computed(() => info.value?.configSource ?? '');
  /** `<UserData>/config/setting.json` 的绝对路径 */
  const configPath = computed(() => info.value?.configPath ?? '');

  async function load(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      info.value = await systemApi.getSystemInfo();
      loaded.value = true;
    } catch (caught) {
      error.value = describeError(caught);
      info.value = null;
      loaded.value = false;
    } finally {
      loading.value = false;
    }
  }

  async function loadBackups(): Promise<void> {
    try {
      const list = await systemApi.listBackups();
      if (info.value) info.value = { ...info.value, backups: list, backupCount: list.length };
    } catch (caught) {
      error.value = describeError(caught);
    }
  }

  async function createBackup(): Promise<BackupCreateResponse | null> {
    backupRunning.value = true;
    backupError.value = null;
    try {
      const created = await systemApi.createBackup();
      lastBackup.value = created;
      await loadBackups();
      return created;
    } catch (caught) {
      backupError.value = describeError(caught);
      return null;
    } finally {
      backupRunning.value = false;
    }
  }

  /** 恢复前的只读校验（integrity_check + foreign_key_check + schema_version） */
  async function verifyBackup(fileName: string): Promise<IntegrityCheckResponse | null> {
    verifying.value = true;
    verifyError.value = null;
    verifyResult.value = null;
    verifiedFileName.value = fileName;
    try {
      verifyResult.value = await systemApi.verifyBackup(fileName);
      return verifyResult.value;
    } catch (caught) {
      verifyError.value = describeError(caught);
      return null;
    } finally {
      verifying.value = false;
    }
  }

  async function restore(fileName: string): Promise<boolean> {
    restoring.value = true;
    restoreError.value = null;
    try {
      const response = await systemApi.restoreBackup(fileName);
      restoreRequiresRestart.value = response.requiresRestart;
      return response.pendingRestore;
    } catch (caught) {
      restoreError.value = describeError(caught);
      return false;
    } finally {
      restoring.value = false;
    }
  }

  async function runIntegrityCheck(): Promise<IntegrityCheckResponse | null> {
    integrityRunning.value = true;
    integrityError.value = null;
    try {
      integrity.value = await systemApi.runIntegrityCheck();
      return integrity.value;
    } catch (caught) {
      integrityError.value = describeError(caught);
      return null;
    } finally {
      integrityRunning.value = false;
    }
  }

  async function seedDemo(): Promise<boolean> {
    seeding.value = true;
    seedMessage.value = null;
    try {
      const response = await systemApi.seedDemoData();
      seedMessage.value = response.skipped
        ? '演示数据已存在，本次跳过（幂等）。'
        : `已写入演示数据：新增 ${response.questionsCreated} 道题目、${response.papersCreated} 张试卷。`;
      await load();
      return true;
    } catch (caught) {
      seedMessage.value = describeError(caught).text;
      return false;
    } finally {
      seeding.value = false;
    }
  }

  async function loadLogFiles(): Promise<void> {
    logLoading.value = true;
    logError.value = null;
    try {
      logFiles.value = await systemApi.listLogFiles();
    } catch (caught) {
      logError.value = describeError(caught);
      logFiles.value = [];
    } finally {
      logLoading.value = false;
    }
  }

  async function loadLogTail(file: string): Promise<void> {
    logLoading.value = true;
    logError.value = null;
    activeLogFile.value = file;
    try {
      logTail.value = await systemApi.readLogTail(file);
    } catch (caught) {
      logError.value = describeError(caught);
      logTail.value = null;
    } finally {
      logLoading.value = false;
    }
  }

  return {
    info,
    loading,
    error,
    loaded,
    backupRunning,
    backupError,
    lastBackup,
    restoring,
    restoreError,
    restoreRequiresRestart,
    verifying,
    verifyError,
    verifyResult,
    verifiedFileName,
    integrity,
    integrityRunning,
    integrityError,
    seeding,
    seedMessage,
    logFiles,
    activeLogFile,
    logTail,
    logLoading,
    logError,
    backups,
    configSource,
    configPath,
    load,
    loadBackups,
    createBackup,
    verifyBackup,
    restore,
    runIntegrityCheck,
    seedDemo,
    loadLogFiles,
    loadLogTail
  };
});
