package com.aia.answer.service;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.ErrorCode;
import com.aia.answer.config.AppProperties;
import com.aia.answer.domain.DocumentDtos;
import com.aia.answer.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 资料文件的落盘与一致性补偿（开发文档 §9.1）。
 *
 * <p>写入顺序是硬约束：先写临时文件并计算 SHA-256 → 原子移动到
 * {@code <dataDir>/files/imports/<fileId>/<originalName>} → 再在 SQLite 短事务里登记元数据。
 * 数据库登记失败时由调用方触发补偿删除；反方向的「有文件没记录」由
 * {@link #cleanup(boolean)} 在保留窗口之后清理。
 *
 * <p>路径安全：所有对外暴露的都是相对数据目录的路径，解析时必须落在
 * {@code files/} 之内，禁止逃逸用户数据目录。
 */
@Service
public class DocumentFileService {

    private static final Logger log = LoggerFactory.getLogger(DocumentFileService.class);

    /** 首期支持的资料格式；PDF/DOCX 属阶段 7（开发文档 §9.2）。 */
    public static final Set<String> ALLOWED_EXTENSIONS = Set.of("txt", "md", "markdown");
    /** 单份资料上限，避免把超大文件读进内存。 */
    public static final long MAX_IMPORT_BYTES = 8L * 1024 * 1024;
    /** 孤儿文件与临时文件的保留窗口（小时）；窗口内的文件可能正在被使用，不清理。 */
    public static final int RETENTION_HOURS = 24;

    private final AppProperties props;
    private final DocumentRepository repository;

    public DocumentFileService(AppProperties props, DocumentRepository repository) {
        this.props = props;
        this.repository = repository;
    }

    /** 暂存结果：文件已落到 temp 目录，SHA-256 与大小已算出，等待提升到正式目录。 */
    public record StagedFile(Path tempPath, String sha256, long sizeBytes, String originalName, String extension,
                             String mimeType) {
    }

    /**
     * 校验来源文件并暂存到 temp 目录。
     *
     * <p>刻意不复用前端传来的 sha256 作为事实：这里重新算一遍并交由调用方比对，防 TOCTOU。
     */
    public StagedFile stage(Path source) {
        if (source == null) {
            throw ApiException.validation("未提供文件路径", "sourcePath");
        }
        Path normalized = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw ApiException.validation("文件不存在或不是普通文件", "sourcePath");
        }
        String originalName = normalized.getFileName().toString();
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ApiException(ErrorCode.DOCUMENT_FORMAT_UNSUPPORTED,
                    "首期只支持纯文本资料（" + String.join(" / ", ALLOWED_EXTENSIONS.stream().sorted().toList())
                            + "），PDF/DOCX 文本提取属阶段 7");
        }
        long size;
        try {
            size = Files.size(normalized);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.DOCUMENT_PARSE_FAILED, "无法读取文件大小：" + e.getMessage());
        }
        if (size > MAX_IMPORT_BYTES) {
            throw new ApiException(ErrorCode.DOCUMENT_TOO_LARGE,
                    "资料超过导入上限（" + (MAX_IMPORT_BYTES / 1024 / 1024) + " MiB）");
        }
        if (size == 0) {
            throw new ApiException(ErrorCode.DOCUMENT_PARSE_FAILED, "文件内容为空");
        }
        try {
            Files.createDirectories(props.tempDir());
            Path temp = Files.createTempFile(props.tempDir(), "import-", "." + extension);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(normalized), digest)) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StagedFile(temp, toHex(digest.digest()), size, originalName, extension, mimeOf(extension));
        } catch (IOException e) {
            throw new ApiException(ErrorCode.DOCUMENT_PARSE_FAILED, "暂存文件失败：" + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 把暂存文件提升为正式文件：{@code files/imports/<fileId>/<originalName>}。
     *
     * @return 相对数据目录的路径（正斜杠）
     */
    public String promote(StagedFile staged, String fileId, String originalName) {
        Path targetDir = props.filesDir().resolve("imports").resolve(fileId);
        Path target = targetDir.resolve(sanitizeFileName(originalName, staged.extension()));
        assertInsideFiles(target);
        try {
            Files.createDirectories(targetDir);
            try {
                Files.move(staged.tempPath(), target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(staged.tempPath(), target, StandardCopyOption.REPLACE_EXISTING);
            }
            return props.resolveDataDir().relativize(target).toString().replace('\\', '/');
        } catch (IOException e) {
            throw new ApiException(ErrorCode.DOCUMENT_PARSE_FAILED, "保存文件失败：" + e.getMessage());
        }
    }

    /** 解析相对路径为绝对路径，并强制校验其位于 files/ 之内。 */
    public Path resolveStored(String relativePath) {
        Path candidate = props.resolveDataDir().resolve(relativePath).normalize();
        assertInsideFiles(candidate);
        return candidate;
    }

    /** 删除已落盘文件（补偿路径），并清理随之变空的 fileId 目录。 */
    public boolean deleteStored(String relativePath) {
        try {
            Path target = resolveStored(relativePath);
            boolean removed = Files.deleteIfExists(target);
            Path parent = target.getParent();
            if (parent != null && !parent.equals(props.filesDir().resolve("imports"))) {
                try (Stream<Path> children = Files.list(parent)) {
                    if (children.findAny().isEmpty()) {
                        Files.deleteIfExists(parent);
                    }
                }
            }
            return removed;
        } catch (IOException | ApiException e) {
            log.warn("补偿删除文件失败（保留记录以便下次巡检）：{}", e.getMessage());
            return false;
        }
    }

    /** 一致性报告：孤儿文件、缺失文件、过期临时文件（开发文档 §9.1、检查清单 §685）。 */
    public DocumentDtos.FileConsistencyReport report() {
        return new DocumentDtos.FileConsistencyReport(orphanFiles(), missingFiles(), tempFileCount(),
                RETENTION_HOURS);
    }

    /**
     * 执行一致性补偿。
     *
     * @param dryRun true 只报告不修改
     */
    public DocumentDtos.FileCleanupResponse cleanup(boolean dryRun) {
        List<DocumentDtos.OrphanFile> orphans = orphanFiles();
        int removed = 0;
        if (!dryRun) {
            for (DocumentDtos.OrphanFile orphan : orphans) {
                if (deleteStored(orphan.relativePath())) {
                    removed++;
                }
            }
            removed += deleteExpiredTempFiles();
        }
        int markedFailed = 0;
        List<DocumentDtos.MissingFile> missing = missingFiles();
        for (DocumentDtos.MissingFile item : missing) {
            Optional<DocumentRepository.DocumentRow> document = repository.findDocument(item.documentId());
            if (document.isEmpty() || "failed".equals(document.get().status())) {
                continue;
            }
            if (!dryRun) {
                // 原文文件缺失时保留记录与失败详情，不自动发布部分结果（开发文档 §505）
                repository.markDocumentFailed(item.documentId(), ErrorCode.DOCUMENT_PARSE_FAILED.name(),
                        "原文文件缺失：" + item.relativePath(), nowUtc());
            }
            markedFailed++;
        }
        return new DocumentDtos.FileCleanupResponse(removed, markedFailed, dryRun);
    }

    private List<DocumentDtos.OrphanFile> orphanFiles() {
        Path importsDir = props.filesDir().resolve("imports");
        List<DocumentDtos.OrphanFile> orphans = new ArrayList<>();
        if (!Files.isDirectory(importsDir)) {
            return orphans;
        }
        try (Stream<Path> dirs = Files.list(importsDir)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                String fileId = dir.getFileName().toString();
                if (repository.findFileById(fileId).isPresent()) {
                    continue;
                }
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        if (!olderThanRetention(file)) {
                            continue; // 保留窗口内：可能是刚失败、还没登记完的导入
                        }
                        orphans.add(new DocumentDtos.OrphanFile(fileId,
                                props.resolveDataDir().relativize(file).toString().replace('\\', '/'),
                                Files.size(file), lastModified(file)));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("扫描孤儿文件失败：{}", e.getMessage());
        }
        orphans.sort(Comparator.comparing(DocumentDtos.OrphanFile::relativePath));
        return orphans;
    }

    private List<DocumentDtos.MissingFile> missingFiles() {
        List<DocumentDtos.MissingFile> missing = new ArrayList<>();
        for (DocumentRepository.FileRow file : repository.listFiles()) {
            Path stored;
            try {
                stored = resolveStored(file.relativePath());
            } catch (ApiException e) {
                stored = null;
            }
            if (stored != null && Files.isRegularFile(stored)) {
                continue;
            }
            for (DocumentRepository.DocumentRow document : repository.listDocumentsByFile(file.id())) {
                missing.add(new DocumentDtos.MissingFile(document.id(), document.title(), file.relativePath()));
            }
        }
        return missing;
    }

    private int tempFileCount() {
        try (Stream<Path> files = Files.list(props.tempDir())) {
            return (int) files.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private int deleteExpiredTempFiles() {
        int removed = 0;
        try (Stream<Path> files = Files.list(props.tempDir())) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (olderThanRetention(file) && Files.deleteIfExists(file)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("清理临时文件失败：{}", e.getMessage());
        }
        return removed;
    }

    private boolean olderThanRetention(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant()
                    .isBefore(Instant.now().minus(RETENTION_HOURS, ChronoUnit.HOURS));
        } catch (IOException e) {
            return false;
        }
    }

    private void assertInsideFiles(Path candidate) {
        Path filesRoot = props.filesDir().toAbsolutePath().normalize();
        if (!candidate.toAbsolutePath().normalize().startsWith(filesRoot)) {
            throw ApiException.validation("文件路径越出数据目录", "sourcePath");
        }
    }

    private String lastModified(Path file) {
        try {
            return DateTimeFormatter.ISO_INSTANT.format(
                    Files.getLastModifiedTime(file).toInstant().truncatedTo(ChronoUnit.SECONDS));
        } catch (IOException e) {
            return "";
        }
    }

    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 原始文件名只作显示元数据；落盘名必须去掉路径分隔符与控制字符。 */
    static String sanitizeFileName(String originalName, String extension) {
        String base = originalName == null ? "" : originalName.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[\\p{Cntrl}<>:\"|?*]", "_").trim();
        if (base.isEmpty() || base.equals(".") || base.equals("..")) {
            base = "document." + (extension.isEmpty() ? "txt" : extension);
        }
        if (base.length() > 120) {
            base = base.substring(0, 120);
        }
        return base;
    }

    static String mimeOf(String extension) {
        return switch (extension) {
            case "md", "markdown" -> "text/markdown";
            default -> "text/plain";
        };
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String nowUtc() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
