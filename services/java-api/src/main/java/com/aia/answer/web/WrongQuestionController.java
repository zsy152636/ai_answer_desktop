package com.aia.answer.web;

import com.aia.answer.common.ApiResponse;
import com.aia.answer.common.PageResult;
import com.aia.answer.domain.AttemptDtos;
import com.aia.answer.domain.WrongDtos;
import com.aia.answer.service.WrongQuestionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 错题闭环（契约 §5）。
 */
@RestController
@RequestMapping("/api/v1/wrong-questions")
public class WrongQuestionController {

    private final WrongQuestionService service;

    public WrongQuestionController(WrongQuestionService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<WrongDtos.WrongQuestionView>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean dueOnly,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String questionType,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(service.list(new WrongDtos.Query(page, size, status, dueOnly, tag, questionType, sort)));
    }

    @GetMapping("/{id}")
    public ApiResponse<WrongDtos.WrongQuestionDetail> detail(@PathVariable String id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/{id}/review")
    public ApiResponse<WrongDtos.WrongQuestionDetail> review(@PathVariable String id,
                                                             @Valid @RequestBody WrongDtos.ReviewRequest request) {
        return ApiResponse.ok(service.review(id, request));
    }

    @PostMapping("/practice")
    public ApiResponse<AttemptDtos.AttemptSheet> practice(@RequestBody(required = false) WrongDtos.PracticeRequest request) {
        return ApiResponse.ok(service.practice(request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<WrongDtos.WrongQuestionDetail> patchStatus(@PathVariable String id,
                                                                  @Valid @RequestBody WrongDtos.StatusRequest request) {
        return ApiResponse.ok(service.patchStatus(id, request.status()));
    }
}
