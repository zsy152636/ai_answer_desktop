package com.aia.answer.service;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.ErrorCode;
import com.aia.answer.common.Ids;
import com.aia.answer.common.Json;
import com.aia.answer.common.PageResult;
import com.aia.answer.config.AppProperties;
import com.aia.answer.db.SqliteCapabilityRegistry;
import com.aia.answer.domain.DocumentDtos;
import com.aia.answer.repository.DocumentRepository;
import com.aia.answer.repository.MetaRepository;
import com.aia.answer.service.search.DocumentSearchService;
import com.aia.answer.service.search.IndexStateService;
import com.aia.answer.service.search.TextChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 资料库与本地检索的业务编排（开发文档 §9.1、§10；契约 §11）。
 *
 * <p>不变式：
 * <ol>
 *   <li><b>原文与分块在普通表</b>，FTS5 只保存可检索文本与 chunk_id，不作为原文唯一存储；</li>
 *   <li>文件 I/O 与分块计算**不持有数据库事务**（开发文档 §498），只有元数据与分块写入走短事务；</li>
 *   <li>删除资料时先删 FTS 行（依赖分块 id）再删 document（分块靠外键级联），最后在「同一 sha256
 *       已无其它资料引用」时才删除磁盘文件，形成补偿路径；</li>
 *   <li>索引重建版本化：新版本构建 + 检索自检通过才切换 active_version，失败保留旧版本。</li>
 * </ol>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /** 单条检索依据送给模型的最大长度（防止上下文被单条资料挤满）。 */
    private static final int MAX_REFERENCE_CHARS = 2000;

    private static final Set<String> VALID_STATUSES = Set.of("imported", "indexed", "failed");
    private static final Set<String> VALID_DUPLICATE_ACTIONS = Set.of("reject", "reuse", "new");

    private final AppProperties props;
    private final DocumentRepository repository;
    private final DocumentFileService fileService;
    private final IndexStateService indexState;
    private final DocumentSearchService searchService;
    private final SqliteCapabilityRegistry capabilities;
    private final MetaRepository metaRepository;
    private final Json json;
    private final TransactionTemplate tx;

    public DocumentService(AppProperties props, DocumentRepository repository, DocumentFileService fileService,
                           IndexStateService indexState, DocumentSearchService searchService,
                           SqliteCapabilityRegistry capabilities, MetaRepository metaRepository, Json json,
                           PlatformTransactionManager transactionManager) {
        this.props = props;
        this.repository = repository;
        this.fileService = fileService;
        this.indexState = indexState;
        this.searchService = searchService;
        this.capabilities = capabilities;
        this.metaRepository = metaRepository;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------- 查询

    public PageResult<DocumentDtos.DocumentSummary> list(DocumentDtos.Query query) {
        int page = query.page() == null ? 1 : query.page();
        int size = query.size() == null ? 20 : query.size();
        PageResult.validate(page, size);
        String status = normalizeStatus(query.status());
        String q = query.q() == null || query.q().isBlank() ? null : query.q().trim();
        String sha256 = query.sha256() == null || query.sha256().isBlank() ? null : query.sha256().trim();
        long total = repository.countDocuments(q, status, sha256);
        List<DocumentDtos.DocumentSummary> items =
                repository.pageDocuments(q, status, sha256, size, (page - 1) * size).stream()
                        .map(this::toSummary)
                        .toList();
        return PageResult.of(items, page, size, total);
    }

    public DocumentDtos.DocumentSummary get(String id) {
        DocumentRepository.DocumentRow document = requireDocument(id);
        return toSummary(document);
    }

    public PageResult<DocumentDtos.ChunkView> chunks(String id, Integer pageParam, Integer sizeParam) {
        DocumentRepository.DocumentRow document = requireDocument(id);
        int page = pageParam == null ? 1 : pageParam;
        int size = sizeParam == null ? 20 : sizeParam;
        PageResult.validate(page, size);
        long version = document.indexVersion() == null ? indexState.activeVersion() : document.indexVersion();
        long total = repository.countChunksOfDocument(id, version);
        List<DocumentDtos.ChunkView> items =
                repository.pageChunks(id, version, size, (page - 1) * size).stream()
                        .map(chunk -> new DocumentDtos.ChunkView(chunk.id(), chunk.documentId(), chunk.chunkIndex(),
                                chunk.pageNo(), chunk.content(), chunk.contentHash(), chunk.parserVersion(),
                                chunk.indexVersion(), chunk.createdAt()))
                        .toList();
        return PageResult.of(items, page, size, total);
    }

    public DocumentDtos.SearchResponse search(String query, Integer limit, String documentId) {
        return searchService.search(query, limit, documentId);
    }

    public DocumentDtos.IndexState indexState() {
        return indexState.state();
    }

    public DocumentDtos.FileConsistencyReport fileReport() {
        return fileService.report();
    }

    public DocumentDtos.FileCleanupResponse cleanupFiles(boolean dryRun) {
        return fileService.cleanup(dryRun);
    }

    // ------------------------------------------------------------- 导入

    public DocumentDtos.DocumentSummary importDocument(DocumentDtos.ImportRequest request) {
        String onDuplicate = normalizeDuplicateAction(request.onDuplicate());
        DocumentFileService.StagedFile staged = fileService.stage(pathOrNull(request.sourcePath()));
        try {
            if (request.sha256() != null && !request.sha256().isBlank()
                    && !request.sha256().equalsIgnoreCase(staged.sha256())) {
                throw ApiException.validation("文件在读取过程中发生变化（SHA-256 不一致），请重新选择文件", "sourcePath");
            }
            Optional<DocumentRepository.FileRow> existingFile = repository.findFileBySha(staged.sha256());
            if (existingFile.isPresent()) {
                String fileId = existingFile.get().id();
                List<DocumentRepository.DocumentRow> existingDocuments = repository.listDocumentsByFile(fileId);
                if (!existingDocuments.isEmpty() && !"new".equals(onDuplicate)) {
                    if ("reuse".equals(onDuplicate)) {
                        return get(existingDocuments.get(0).id());
                    }
                    throw new ApiException(ErrorCode.STATE_CONFLICT,
                            "该文件已导入过：" + existingDocuments.get(0).title(),
                            List.of(new ApiException.FieldViolation("sourcePath",
                                    "文件内容与已有资料相同（sha256 一致），请选择复用或新建")));
                }
                // 复用同一份磁盘文件，只新增一条资料记录（开发文档 §9.1）
                String documentId = createDocument(staged, existingFile.get(), resolveTitle(request.title(), staged));
                audit("document.imported", "document", documentId, "file=" + fileId + " sha256=" + staged.sha256());
                return get(documentId);
            }

            String fileId = Ids.of("file");
            String relativePath = fileService.promote(staged, fileId, staged.originalName());
            String now = nowUtc();
            DocumentRepository.FileRow fileRow = new DocumentRepository.FileRow(fileId, staged.sha256(),
                    staged.sizeBytes(), staged.mimeType(), staged.originalName(), relativePath, now,
                    "imported", now, now);
            try {
                tx.executeWithoutResult(status -> repository.insertFile(fileRow));
            } catch (RuntimeException e) {
                // 开发文档 §501/§502：数据库登记失败 → 补偿删除已落盘文件
                boolean removed = fileService.deleteStored(relativePath);
                log.warn("资料元数据写入失败，已{}删除落盘文件 {}", removed ? "" : "尝试", relativePath);
                throw e;
            }
            String documentId = createDocument(staged, fileRow, resolveTitle(request.title(), staged));
            audit("document.imported", documentId, fileId, staged.sha256());
            return get(documentId);
        } finally {
            deleteQuietly(staged.tempPath());
        }
    }

    // ------------------------------------------------------------- 删除

    public DocumentDtos.DocumentDeleteResponse delete(String id) {
        DocumentRepository.DocumentRow document = requireDocument(id);
        DocumentRepository.FileRow file = repository.findFileById(document.fileId()).orElse(null);
        boolean withFts = capabilities.fts5Trigram();
        tx.executeWithoutResult(status -> {
            if (withFts) {
                repository.deleteFtsByDocument(id);
            }
            repository.deleteDocument(id);
        });
        audit("document.deleted", "document", id, "title=" + document.title());
        boolean fileRemoved = false;
        if (file != null && repository.countDocumentsOfFile(file.id()) == 0) {
            fileRemoved = fileService.deleteStored(file.relativePath());
            if (fileRemoved) {
                tx.executeWithoutResult(status -> repository.deleteFile(file.id()));
            }
        }
        return new DocumentDtos.DocumentDeleteResponse(true, fileRemoved);
    }

    // ------------------------------------------------------------- 索引重建

    public DocumentDtos.IndexRebuildResponse rebuildIndex() {
        long active = indexState.activeVersion();
        long buildingVersion = active + 1;
        boolean withFts = capabilities.fts5Trigram();
        indexState.beginBuild(buildingVersion);
        // 新版本从干净状态开始：清掉上一次失败构建可能残留的分块与索引行（幂等）
        tx.executeWithoutResult(status -> {
            if (withFts) {
                repository.deleteFtsByVersion(buildingVersion);
            }
            repository.deleteChunksByVersion(buildingVersion);
        });

        List<String> failures = new ArrayList<>();
        int chunks = 0;
        for (DocumentRepository.DocumentRow document : repository.listDocumentsForRebuild()) {
            try {
                chunks += reindexDocument(document, buildingVersion, withFts);
            } catch (RuntimeException e) {
                failures.add(document.title() + "：" + safeMessage(e));
            }
        }
        if (failures.isEmpty() && withFts && chunks > 0 && !selfCheck(buildingVersion)) {
            failures.add("检索自检未命中（新索引可能不完整）");
        }
        if (!failures.isEmpty()) {
            tx.executeWithoutResult(status -> {
                if (withFts) {
                    repository.deleteFtsByVersion(buildingVersion);
                }
                repository.deleteChunksByVersion(buildingVersion);
            });
            indexState.abortBuild();
            audit("document.index.failed", "index", String.valueOf(buildingVersion),
                    String.join("；", failures));
            throw new ApiException(ErrorCode.DOCUMENT_INDEX_FAILED,
                    "索引重建失败，已保留旧索引版本（v" + active + "）：" + String.join("；", failures));
        }
        tx.executeWithoutResult(status -> {
            indexState.activate(buildingVersion);
            // 切换成功后清理旧版本，避免分块表与 FTS 无限增长
            if (withFts) {
                repository.deleteFtsBelowVersion(buildingVersion);
            }
            repository.deleteChunksBelowVersion(buildingVersion);
        });
        audit("document.index.rebuilt", "index", String.valueOf(buildingVersion), "chunks=" + chunks);
        return new DocumentDtos.IndexRebuildResponse(indexState.state(), chunks, List.of());
    }

    private int reindexDocument(DocumentRepository.DocumentRow document, long buildingVersion, boolean withFts) {
        DocumentRepository.FileRow file = repository.findFileById(document.fileId())
                .orElseThrow(() -> new IllegalStateException("资料缺少文件记录"));
        Parsed parsed = parse(fileService.resolveStored(file.relativePath()), document.sourceKind());
        String now = nowUtc();
        List<DocumentRepository.ChunkRow> rows = new ArrayList<>(parsed.chunks().size());
        List<DocumentRepository.FtsRow> ftsRows = new ArrayList<>(parsed.chunks().size());
        for (int index = 0; index < parsed.chunks().size(); index++) {
            String content = parsed.chunks().get(index);
            String chunkId = Ids.of("chk");
            rows.add(new DocumentRepository.ChunkRow(chunkId, document.id(), null, index, content,
                    sha256Hex(content), parsed.parserVersion(), (int) buildingVersion, now));
            ftsRows.add(new DocumentRepository.FtsRow(content, chunkId));
        }
        tx.executeWithoutResult(status -> {
            repository.insertChunks(rows);
            if (withFts) {
                repository.insertFtsRows(ftsRows);
            }
            repository.markDocumentIndexed(document.id(), rows.size(), parsed.charCount(),
                    (int) buildingVersion, parsed.parserVersion(), now);
        });
        return rows.size();
    }

    /** 完整性自检：用新版本第一条分块的前几个字符做一次真实检索，必须能命中。 */
    private boolean selfCheck(long buildingVersion) {
        return repository.pageChunksFirst(buildingVersion)
                .map(chunk -> {
                    String probe = probeOf(chunk.content());
                    return probe.length() < 3 || searchService.canRetrieve(buildingVersion, probe);
                })
                .orElse(true);
    }

    private static String probeOf(String content) {
        String trimmed = content.strip();
        return trimmed.length() <= 24 ? trimmed : trimmed.substring(0, 24);
    }

    // ------------------------------------------------------------- AI 检索依据

    /**
     * 为「检索增强的 AI 解释」取回 top-N 分块。
     *
     * <p>返回的 {@code refId}（K1…Kn）是模型唯一被允许引用的标识；Java 侧保留
     * {@code documentId}/{@code chunkId}/{@code pageNo}，因此响应里的每条引用都能回到真实分块核对。
     */
    public List<DocumentDtos.AiReference> referencesFor(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        DocumentDtos.SearchResponse response = searchService.search(query, limit, null);
        if (response.results().isEmpty()) {
            return List.of();
        }
        List<String> chunkIds = response.results().stream().map(DocumentDtos.SearchHit::chunkId).toList();
        Map<String, DocumentRepository.ChunkViewRow> byId = new LinkedHashMap<>();
        for (DocumentRepository.ChunkViewRow row : repository.findChunksByIds(chunkIds)) {
            byId.put(row.id(), row);
        }
        List<DocumentDtos.AiReference> references = new ArrayList<>();
        int index = 1;
        for (DocumentDtos.SearchHit hit : response.results()) {
            DocumentRepository.ChunkViewRow chunk = byId.get(hit.chunkId());
            if (chunk == null) {
                continue;
            }
            references.add(new DocumentDtos.AiReference("K" + index++, hit.documentId(), hit.chunkId(),
                    hit.title(), hit.pageNo(), truncate(chunk.content(), MAX_REFERENCE_CHARS)));
        }
        return references;
    }

    // ------------------------------------------------------------- 内部

    private String createDocument(DocumentFileService.StagedFile staged, DocumentRepository.FileRow file,
                                  String title) {
        String documentId = Ids.of("doc");
        String sourceKind = "markdown".equals(sourceKindOf(staged.extension())) ? "markdown" : "text";
        String now = nowUtc();
        Parsed parsed;
        try {
            parsed = parse(fileService.resolveStored(file.relativePath()), sourceKind);
        } catch (ApiException e) {
            // 开发文档 §505：解析失败保留原始文件与失败详情，不自动发布部分结果
            tx.executeWithoutResult(status -> repository.insertDocument(new DocumentRepository.DocumentRow(
                    documentId, title, file.id(), "failed", sourceKind, 0, 0, null, null,
                    e.code().name(), e.getMessage(), now, now)));
            audit("document.parse.failed", "document", documentId, e.getMessage());
            return documentId;
        }
        long version = indexState.activeVersion();
        boolean withFts = capabilities.fts5Trigram();
        List<DocumentRepository.ChunkRow> rows = new ArrayList<>(parsed.chunks().size());
        List<DocumentRepository.FtsRow> ftsRows = new ArrayList<>(parsed.chunks().size());
        for (int index = 0; index < parsed.chunks().size(); index++) {
            String content = parsed.chunks().get(index);
            String chunkId = Ids.of("chk");
            rows.add(new DocumentRepository.ChunkRow(chunkId, documentId, null, index, content,
                    sha256Hex(content), parsed.parserVersion(), (int) version, now));
            ftsRows.add(new DocumentRepository.FtsRow(content, chunkId));
        }
        tx.executeWithoutResult(status -> {
            repository.insertDocument(new DocumentRepository.DocumentRow(documentId, title, file.id(), "imported",
                    sourceKind, 0, 0, (int) version, parsed.parserVersion(), null, null, now, now));
            repository.insertChunks(rows);
            if (withFts) {
                repository.insertFtsRows(ftsRows);
            }
            repository.markDocumentIndexed(documentId, rows.size(), parsed.charCount(), (int) version,
                    parsed.parserVersion(), now);
        });
        return documentId;
    }

    /** 读取并分块；解码按 UTF-8 → GB18030 顺序尝试，两种都不成立才算解析失败。 */
    private Parsed parse(Path stored, String sourceKind) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(stored);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.DOCUMENT_PARSE_FAILED, "读取资料失败：" + e.getMessage());
        }
        boolean utf8 = true;
        String decoded;
        try {
            decoded = strictDecode(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException utf8Failure) {
            utf8 = false;
            try {
                decoded = strictDecode(bytes, Charset.forName("GB18030"));
            } catch (CharacterCodingException gbFailure) {
                throw new ApiException(ErrorCode.DOCUMENT_PARSE_FAILED,
                        "文本既不是 UTF-8 也不是 GB18030 编码，无法解析");
            }
        }
        String normalized = TextChunker.normalize(decoded);
        List<String> chunks = TextChunker.chunk(normalized);
        if (chunks.isEmpty()) {
            throw new ApiException(ErrorCode.DOCUMENT_PARSE_FAILED, "未从资料中解析出任何可检索文本");
        }
        String parserVersion = TextChunker.parserVersion(sourceKind) + (utf8 ? "+utf8" : "+gb18030");
        return new Parsed(chunks, normalized.length(), parserVersion);
    }

    private static String strictDecode(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private DocumentRepository.DocumentRow requireDocument(String id) {
        return repository.findDocument(id).orElseThrow(() -> ApiException.notFound("资料 " + id));
    }

    private DocumentDtos.DocumentSummary toSummary(DocumentRepository.DocumentRow document) {
        return repository.toSummary(document, repository.findFileById(document.fileId()).orElse(null));
    }

    private static Path pathOrNull(String raw) {
        return raw == null || raw.isBlank() ? null : Path.of(raw);
    }

    private static String resolveTitle(String requested, DocumentFileService.StagedFile staged) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim().length() > 200 ? requested.trim().substring(0, 200) : requested.trim();
        }
        String name = staged.originalName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.isBlank() ? "未命名资料" : base;
    }

    private static String sourceKindOf(String extension) {
        return switch (extension) {
            case "md", "markdown" -> "markdown";
            default -> "text";
        };
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toLowerCase();
        if (!VALID_STATUSES.contains(normalized)) {
            throw ApiException.validation("status 只能是 imported / indexed / failed", "status");
        }
        return normalized;
    }

    private static String normalizeDuplicateAction(String action) {
        if (action == null || action.isBlank()) {
            return "reject";
        }
        String normalized = action.trim().toLowerCase();
        if (!VALID_DUPLICATE_ACTIONS.contains(normalized)) {
            throw ApiException.validation("onDuplicate 只能是 reject / reuse / new", "onDuplicate");
        }
        return normalized;
    }

    private void audit(String eventType, String entityType, String entityId, String detail) {
        try {
            metaRepository.audit(eventType, entityType, entityId,
                    json.write(Map.of("detail", detail == null ? "" : detail)), nowUtc());
        } catch (RuntimeException e) {
            // 审计失败不得影响主流程
            log.warn("审计写入失败：{}", e.getMessage());
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("临时文件清理失败：{}", e.getMessage());
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String sha256Hex(String text) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest(text.getBytes(StandardCharsets.UTF_8))) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error instanceof ApiException ? error.getMessage() : error.getClass().getSimpleName();
        return message == null || message.isBlank() ? "未知错误" : message;
    }

    private static String nowUtc() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    /** 解析结果：分块序列、规范化后的字符数、解析器版本（含实际使用的编码）。 */
    private record Parsed(List<String> chunks, int charCount, String parserVersion) {
    }
}
