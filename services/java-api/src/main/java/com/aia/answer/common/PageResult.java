package com.aia.answer.common;

import java.util.List;

/**
 * 分页结果（契约 §0 分页约定）。
 */
public record PageResult<T>(List<T> items, int page, int size, long total, int totalPages) {

    public static <T> PageResult<T> of(List<T> items, int page, int size, long total) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) total / (double) size);
        return new PageResult<>(items, page, size, total, totalPages);
    }

    /** 页码范围与大小校验，越界返回 VALIDATION_ERROR。 */
    public static void validate(int page, int size) {
        if (page < 1) {
            throw ApiException.validation("page 必须大于等于 1", "page");
        }
        if (size < 1 || size > 100) {
            throw ApiException.validation("size 必须在 1 到 100 之间", "size");
        }
    }
}
