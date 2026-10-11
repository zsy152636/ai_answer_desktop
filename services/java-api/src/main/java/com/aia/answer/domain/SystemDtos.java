package com.aia.answer.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** 系统设置、仪表盘、异步任务传输对象（契约 api-contract.md §1、§8、§9）。 */
public final class SystemDtos {

    private SystemDtos() {
    }

    public record AiServiceStatus(boolean available, String baseUrl, Long latencyMs, String message) {
    }

    public record MetaView(
            String appVersion,
            String contractVersion,
            int schemaVersion,
            String dataDir,
            String databasePath,
            long databaseSizeBytes,
            boolean fts5Available,
            AiServiceStatus aiService,
            String startedAt) {
    }

    public record DashboardView(
            int questionCount,
            int publishedQuestionCount,
            int paperCount,
            int publishedPaperCount,
            int inProgressAttemptCount,
            int dueWrongQuestionCount,
            int activeWrongQuestionCount,
            int masteredWrongQuestionCount,
            List<AttemptDtos.AttemptSummary> recentAttempts,
            List<WrongDtos.WrongQuestionView> recentWrongQuestions,
            AiServiceStatus aiService) {
    }

    public record BackupView(String fileName, long sizeBytes, String checksum, String createdAt,
                             int schemaVersion, String appVersion, boolean integrityOk, String kind) {
    }

    /**
     * 系统信息（契约 §8）。
     *
     * <p>v1.1 起不再有 {@code credentialBackend}：密钥不再交给 OS 凭据存储，
     * 而是存放在 {@code configPath} 指向的 setting.json 中，UI 必须显式展示该路径。
     *
     * @param configSource 固定 {@code setting.json}
     * @param configPath   setting.json 的绝对路径
     */
    public record SystemInfoView(
            String appVersion,
            int schemaVersion,
            String dataDir,
            String logDir,
            String databasePath,
            long databaseSizeBytes,
            int backupCount,
            List<BackupView> backups,
            long uptimeSeconds,
            String configSource,
            String configPath,
            AiServiceStatus aiService) {
    }

    public record RestoreRequest(@NotBlank(message = "fileName 不能为空") String fileName, Boolean confirm) {
    }

    public record RestoreResponse(boolean pendingRestore, boolean requiresRestart, BackupView backup) {
    }

    public record IntegrityView(boolean ok, String integrityCheck, String foreignKeyCheck, int schemaVersion,
                                long databaseSizeBytes) {
    }

    public record SeedDemoResponse(int questionsCreated, int papersCreated, boolean skipped) {
    }

    public record LogFileView(String fileName, long sizeBytes, String modifiedAt) {
    }

    public record LogTailView(String fileName, List<String> lines) {
    }

    public record TaskView(
            String id,
            String taskType,
            String status,
            JsonNode payload,
            JsonNode result,
            int attemptCount,
            int maxAttempts,
            String leaseOwner,
            String leaseUntil,
            String heartbeatAt,
            String nextRunAt,
            String lastErrorCode,
            String lastErrorMessage,
            String createdAt,
            String updatedAt) {
    }

    public record GradeSubjectiveResponse(
            String snapshotQuestionId,
            Double suggestedScore,
            double maxScore,
            String gradingStatus,
            JsonNode gradingDetail,
            String message) {
    }
}
