package com.aia.answer.common;

import java.util.List;

/**
 * 业务异常。消息面向用户，不得包含密钥、凭据或供应商原始错误全文（开发文档 §7.1）。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<FieldViolation> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public ApiException(ErrorCode code, String message, List<FieldViolation> details) {
        super(message);
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public static ApiException validation(String message, String field) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message,
                field == null ? List.of() : List.of(new FieldViolation(field, message)));
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, what + "不存在");
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.STATE_CONFLICT, message);
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldViolation> details() {
        return details;
    }

    /** 字段级校验明细。 */
    public record FieldViolation(String field, String message) {
    }
}
