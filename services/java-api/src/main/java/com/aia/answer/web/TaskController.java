package com.aia.answer.web;

import com.aia.answer.common.ApiResponse;
import com.aia.answer.common.PageResult;
import com.aia.answer.domain.SystemDtos;
import com.aia.answer.service.TaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异步任务（契约 §9）。
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<SystemDtos.TaskView>> list(@RequestParam(required = false) Integer page,
                                                             @RequestParam(required = false) Integer size,
                                                             @RequestParam(required = false) String status,
                                                             @RequestParam(required = false) String taskType) {
        return ApiResponse.ok(service.list(status, taskType, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<SystemDtos.TaskView> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<SystemDtos.TaskView> retry(@PathVariable String id) {
        return ApiResponse.ok(service.retry(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<SystemDtos.TaskView> cancel(@PathVariable String id) {
        return ApiResponse.ok(service.cancel(id));
    }
}
