package com.aia.answer.web;

import com.aia.answer.common.ApiResponse;
import com.aia.answer.common.PageResult;
import com.aia.answer.domain.QuestionDtos;
import com.aia.answer.service.QuestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 题库与题目版本（契约 §2）。
 */
@RestController
@RequestMapping("/api/v1/questions")
public class QuestionController {

    private final QuestionService service;

    public QuestionController(QuestionService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<QuestionDtos.QuestionSummary>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String questionType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(service.list(new QuestionDtos.Query(page, size, q, questionType, status, tag, sort)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<QuestionDtos.QuestionView>> create(
            @Valid @RequestBody QuestionDtos.SaveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(request)));
    }

    @GetMapping("/{id}")
    public ApiResponse<QuestionDtos.QuestionView> get(@PathVariable String id,
                                                      @RequestParam(required = false) Integer versionNo) {
        return ApiResponse.ok(service.get(id, versionNo));
    }

    @PutMapping("/{id}")
    public ApiResponse<QuestionDtos.QuestionView> update(@PathVariable String id,
                                                         @Valid @RequestBody QuestionDtos.SaveRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<QuestionDtos.QuestionView> publish(@PathVariable String id) {
        return ApiResponse.ok(service.publish(id));
    }

    @PostMapping("/{id}/archive")
    public ApiResponse<QuestionDtos.QuestionView> archive(@PathVariable String id) {
        return ApiResponse.ok(service.archive(id));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<QuestionDtos.VersionBrief>> versions(@PathVariable String id) {
        return ApiResponse.ok(service.versions(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.ok(Map.of("deleted", true, "id", id));
    }
}
