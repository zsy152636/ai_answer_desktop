package com.aia.answer.web;

import com.aia.answer.common.ApiResponse;
import com.aia.answer.common.PageResult;
import com.aia.answer.domain.PaperDtos;
import com.aia.answer.service.PaperService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 试卷与发布快照（契约 §3）。
 */
@RestController
@RequestMapping("/api/v1/papers")
public class PaperController {

    private final PaperService service;

    public PaperController(PaperService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<PaperDtos.PaperSummary>> list(@RequestParam(required = false) Integer page,
                                                               @RequestParam(required = false) Integer size,
                                                               @RequestParam(required = false) String q,
                                                               @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.list(new PaperDtos.Query(page, size, q, status)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PaperDtos.PaperView>> create(@Valid @RequestBody PaperDtos.SaveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(request)));
    }

    @GetMapping("/{id}")
    public ApiResponse<PaperDtos.PaperView> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<PaperDtos.PaperView> update(@PathVariable String id,
                                                   @Valid @RequestBody PaperDtos.SaveRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<PaperDtos.PaperSnapshotView> publish(@PathVariable String id) {
        return ApiResponse.ok(service.publish(id));
    }

    @PostMapping("/{id}/archive")
    public ApiResponse<PaperDtos.PaperView> archive(@PathVariable String id) {
        return ApiResponse.ok(service.archive(id));
    }

    @GetMapping("/{id}/snapshots")
    public ApiResponse<List<PaperDtos.PaperSnapshotView>> snapshots(@PathVariable String id) {
        return ApiResponse.ok(service.listSnapshots(id));
    }

    @GetMapping("/{id}/snapshots/{snapshotId}")
    public ApiResponse<PaperDtos.PaperSnapshotView> snapshot(@PathVariable String id, @PathVariable String snapshotId) {
        return ApiResponse.ok(service.getSnapshot(id, snapshotId));
    }
}
