package com.aia.answer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 运行期配置。全部通过命令行参数传入，形如 {@code --aia.data-dir=D:\...}。
 *
 * <p>开发文档 §4.1：正式业务路径不得依赖进程当前工作目录，因此 Java 侧不做任何
 * 相对路径推断，dataDir 必须由 Electron 主进程显式下发（开发/测试脚本亦然）。
 */
@ConfigurationProperties(prefix = "aia")
public class AppProperties {

    /** 用户数据目录。<数据目录>/database/app.db 为其下的正式业务库。 */
    private String dataDir;

    /** Java 端点文件（含随机端口与会话令牌），供 Electron 主进程读取。 */
    private String endpointFile;

    /** Python AI 服务端点文件。缺省时 Java 不启用 AI 能力。 */
    private String pythonEndpointFile;

    /** 会话令牌。缺省时启动随机生成，仅本进程生命周期有效，不落盘。 */
    private String sessionToken;

    /** 是否在启动时检查并按需自动备份。 */
    private boolean autoBackupEnabled = true;

    /** 距上次备份超过该小时数则启动时自动备份。 */
    private int autoBackupHours = 24;

    /** 备份保留数量，超出后从最旧的开始清理。 */
    private int backupRetentionCount = 30;

    /** 幂等记录保留天数。 */
    private int idempotencyRetentionDays = 7;

    /** 日志接口返回的最大行数。 */
    private int logTailLines = 500;

    /** 单个响应/请求体大小上限（字节），默认 1 MiB。 */
    private int maxPayloadBytes = 1024 * 1024;

    // 说明（v1.1）：这里刻意不再有 credentialBackend 属性 —— 密钥不再交给 OS 凭据存储，
    // 唯一权威是 <UserData>/config/setting.json（ADR-014 取代 ADR-004）。

    public Path resolveDataDir() {
        Path base;
        if (dataDir != null && !dataDir.isBlank()) {
            base = Paths.get(dataDir);
        } else {
            String localAppData = System.getenv("LOCALAPPDATA");
            base = (localAppData != null && !localAppData.isBlank())
                    ? Paths.get(localAppData, "ai-answer-desktop")
                    : Paths.get(System.getProperty("user.home"), ".ai-answer-desktop");
        }
        return base.toAbsolutePath().normalize();
    }

    public Path databasePath() {
        return resolveDataDir().resolve("database").resolve("app.db");
    }

    public Path backupsDir() {
        return resolveDataDir().resolve("backups");
    }

    public Path logsDir() {
        return resolveDataDir().resolve("logs");
    }

    public Path runtimeDir() {
        return resolveDataDir().resolve("runtime");
    }

    public Path tempDir() {
        return resolveDataDir().resolve("temp");
    }

    public Path filesDir() {
        return resolveDataDir().resolve("files");
    }

    /** 用户配置文件目录。<数据目录>/config/setting.json 为文件驱动的 LLM 配置。 */
    public Path configDir() {
        return resolveDataDir().resolve("config");
    }

    /**
     * 用户配置文件。该文件是本机唯一的 LLM 配置来源（含明文密钥），
     * 因此写入侧必须走原子写 + 0600 权限，禁止把内容写进日志。
     */
    public Path settingFile() {
        return configDir().resolve("setting.json");
    }

    public Path restoreRequestFile() {
        return runtimeDir().resolve("restore-request.json");
    }

    public Path resolveEndpointFile() {
        return endpointFile != null && !endpointFile.isBlank()
                ? Paths.get(endpointFile).toAbsolutePath().normalize()
                : runtimeDir().resolve("java-endpoint.json");
    }

    public Path resolvePythonEndpointFile() {
        if (pythonEndpointFile != null && !pythonEndpointFile.isBlank()) {
            return Paths.get(pythonEndpointFile).toAbsolutePath().normalize();
        }
        Path fallback = runtimeDir().resolve("python-endpoint.json");
        return Files.exists(fallback) ? fallback : null;
    }

    // ------------------------------------------------------------------ getters / setters

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public String getEndpointFile() {
        return endpointFile;
    }

    public void setEndpointFile(String endpointFile) {
        this.endpointFile = endpointFile;
    }

    public String getPythonEndpointFile() {
        return pythonEndpointFile;
    }

    public void setPythonEndpointFile(String pythonEndpointFile) {
        this.pythonEndpointFile = pythonEndpointFile;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    public boolean isAutoBackupEnabled() {
        return autoBackupEnabled;
    }

    public void setAutoBackupEnabled(boolean autoBackupEnabled) {
        this.autoBackupEnabled = autoBackupEnabled;
    }

    public int getAutoBackupHours() {
        return autoBackupHours;
    }

    public void setAutoBackupHours(int autoBackupHours) {
        this.autoBackupHours = autoBackupHours;
    }

    public int getBackupRetentionCount() {
        return backupRetentionCount;
    }

    public void setBackupRetentionCount(int backupRetentionCount) {
        this.backupRetentionCount = backupRetentionCount;
    }

    public int getIdempotencyRetentionDays() {
        return idempotencyRetentionDays;
    }

    public void setIdempotencyRetentionDays(int idempotencyRetentionDays) {
        this.idempotencyRetentionDays = idempotencyRetentionDays;
    }

    public int getLogTailLines() {
        return logTailLines;
    }

    public void setLogTailLines(int logTailLines) {
        this.logTailLines = logTailLines;
    }

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public void setMaxPayloadBytes(int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }
}
