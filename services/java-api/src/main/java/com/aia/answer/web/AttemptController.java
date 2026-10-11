package com.aia.answer.web;

import com.aia.answer.common.ApiResponse;
import com.aia.answer.common.PageResult;
import com.aia.answer.domain.AttemptDtos;
import com.aia.answer.service.AttemptService;
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
 * 答题与判分（契约 §4）。
 *
 * <p>新增的两个端点用于主观题复核闭环：{@code /ai-grade} 触发 AI 建议分，
 * {@code /answers/{id}/grade} 由人工确认或覆盖分数，二者都会重算总分。
 */
@RestController
@RequestMapping("/api/v1/attempts")
public class AttemptController {

    private final AttemptService service;

    public AttemptController(AttemptService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<AttemptDtos.AttemptSummary>> list(@RequestParam(required = false) Integer page,
                                                                    @RequestParam(required = false) Integer size,
                                                                    @RequestParam(required = false) String status,
                                                                    @RequestParam(required = false) String paperId) {
        return ApiResponse.ok(service.list(status, paperId, page, size));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AttemptDtos.AttemptSheet>> start(
            @RequestBody(required = false) AttemptDtos.StartRequest request) {
        AttemptDtos.StartRequest effective = request == null
                ? new AttemptDtos.StartRequest(null, "paper", null, null) : request;
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.start(effective)));
    }

    @GetMapping("/{id}")
    public ApiResponse<AttemptDtos.AttemptSheet> sheet(@PathVariable String id) {
        return ApiResponse.ok(service.sheet(id));
    }

    @PutMapping("/{id}/answers/{snapshotQuestionId}")
    public ApiResponse<AttemptDtos.AnswerView> saveAnswer(@PathVariable String id,
                                                          @PathVariable String snapshotQuestionId,
                                                          @Valid @RequestBody AttemptDtos.SaveAnswerRequest request) {
        return ApiResponse.ok(service.saveAnswer(id, snapshotQuestionId, request.answer()));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<AttemptDtos.AttemptResult> submit(@PathVariable String id) {
        return ApiResponse.ok(service.submit(id));
    }

    @GetMapping("/{id}/result")
    public ApiResponse<AttemptDtos.AttemptResult> result(@PathVariable String id) {
        return ApiResponse.ok(service.result(id));
    }

    @PostMapping("/{id}/abandon")
    public ApiResponse<AttemptDtos.AttemptSheet> abandon(@PathVariable String id) {
        return ApiResponse.ok(service.abandon(id));
    }

    /** 为所有待复核的主观题排队 AI 评分任务。 */
    @PostMapping("/{id}/ai-grade")
    public ApiResponse<AttemptDtos.AiGradeResponse> requestAiGrade(@PathVariable String id) {
        List<String> taskIds = service.requestAiGrading(id);
        return ApiResponse.ok(new AttemptDtos.AiGradeResponse(taskIds, taskIds.size()));
    }

    /** 人工确认或覆盖主观题分数（AI 建议分只有经过这一步才生效）。 */
    @PostMapping("/{id}/answers/{snapshotQuestionId}/grade")
    public ApiResponse<AttemptDtos.AttemptResult> gradeManually(@PathVariable String id,
                                                                @PathVariable String snapshotQuestionId,
                                                                @Valid @RequestBody AttemptDtos.ManualGradeRequest request) {
        return ApiResponse.ok(service.gradeManually(id, snapshotQuestionId, request.score(), request.note()));
    }
}
