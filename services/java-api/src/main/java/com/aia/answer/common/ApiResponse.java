package com.aia.answer.common;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 统一响应包络（契约 api-contract.md §0.1 / §0.2）。
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        ApiError error,
        String requestId,
        String timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, RequestContext.requestId(), now());
    }

    public static <T> ApiResponse<T> fail(ErrorCode code, String message, List<ApiException.FieldViolation> details) {
        ApiError error = new ApiError(code.name(), message, details == null ? List.of() : details, code.retryable());
        return new ApiResponse<>(false, null, error, RequestContext.requestId(), now());
    }

    public static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    public record ApiError(String code, String message, List<ApiException.FieldViolation> details, boolean retryable) {
    }
}
