package com.aia.answer.web;

import com.aia.answer.common.ApiResponse;
import com.aia.answer.common.Json;
import com.aia.answer.common.PageResult;
import com.aia.answer.domain.DocumentDtos;
import com.aia.answer.service.DocumentService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资料库与本地检索接口（契约 §11）。
 *
 * <p>鉴权与其他业务接口一致（`X-Session-Token`，由 SecurityFilter 统一校验）。
 * 检索结果带 `documentId`/`chunkId`/`pageNo`，供界面与 AI 引用回溯到具体分块。
 */
@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private final DocumentService documentService;
    private final Json json;

    public DocumentController(DocumentService documentService, Json json) {
        this.documentService = documentService;
        this.json = json;
    }

    @GetMapping("/documents")
    public ApiResponse<PageResult<DocumentDtos.DocumentSummary>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sha256) {
        return ApiResponse.ok(documentService.list(new DocumentDtos.Query(page, size, q, status, sha256)));
    }

    @PostMapping("/documents/import")
    public ApiResponse<DocumentDtos.DocumentSummary> importDocument(@RequestBody JsonNode body) {
        DocumentDtos.ImportRequest request = json.convert(body, DocumentDtos.ImportRequest.class);
        return ApiResponse.ok(documentService.importDocument(request));
    }

    @GetMapping("/documents/{id}")
    public ApiResponse<DocumentDtos.DocumentSummary> get(@PathVariable String id) {
        return ApiResponse.ok(documentService.get(id));
    }

    @GetMapping("/documents/{id}/chunks")
    public ApiResponse<PageResult<DocumentDtos.ChunkView>> chunks(@PathVariable String id,
                                                                 @RequestParam(required = false) Integer page,
                                                                 @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(documentService.chunks(id, page, size));
    }

    @DeleteMapping("/documents/{id}")
    public ApiResponse<DocumentDtos.DocumentDeleteResponse> delete(@PathVariable String id) {
        return ApiResponse.ok(documentService.delete(id));
    }

    @PostMapping("/documents/reindex")
    public ApiResponse<DocumentDtos.IndexRebuildResponse> reindex() {
        return ApiResponse.ok(documentService.rebuildIndex());
    }

    @GetMapping("/search")
    public ApiResponse<DocumentDtos.SearchResponse> search(@RequestParam String q,
                                                           @RequestParam(required = false) Integer limit,
                                                           @RequestParam(required = false) String documentId) {
        return ApiResponse.ok(documentService.search(q, limit, documentId));
    }

    @GetMapping("/search/index")
    public ApiResponse<DocumentDtos.IndexState> indexState() {
        return ApiResponse.ok(documentService.indexState());
    }

    @GetMapping("/system/files")
    public ApiResponse<DocumentDtos.FileConsistencyReport> files() {
        return ApiResponse.ok(documentService.fileReport());
    }

    /** 一致性补偿。默认 dryRun=true：破坏性动作必须显式关闭演练模式（开发文档 §675）。 */
    @PostMapping("/system/files/cleanup")
    public ApiResponse<DocumentDtos.FileCleanupResponse> cleanup(@RequestBody(required = false) JsonNode body) {
        boolean dryRun = body == null || body.path("dryRun").asBoolean(true);
        return ApiResponse.ok(documentService.cleanupFiles(dryRun));
    }
}
