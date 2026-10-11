package com.aia.answer.common;

import org.springframework.http.HttpStatus;

/**
 * 冻结错误码（契约 api-contract.md §0.3）。新增码必须先改契约。
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, false),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, false),
    ORIGIN_REJECTED(HttpStatus.FORBIDDEN, false),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    STATE_CONFLICT(HttpStatus.CONFLICT, false),
    VERSION_CONFLICT(HttpStatus.CONFLICT, false),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, false),
    DB_BUSY(HttpStatus.SERVICE_UNAVAILABLE, true),
    DB_MIGRATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, false),
    CONFIG_JSON_INVALID(HttpStatus.BAD_REQUEST, false),
    CONFIG_SCHEMA_UNSUPPORTED(HttpStatus.BAD_REQUEST, false),
    /** setting.json 校验失败（§6 reload）：客户端应展示 schema path，修正后再调 reload。 */
    CONFIG_FILE_INVALID(HttpStatus.BAD_REQUEST, false),
    BASE_URL_BLOCKED(HttpStatus.BAD_REQUEST, false),
    CREDENTIAL_MISSING(HttpStatus.BAD_REQUEST, false),
    LLM_AUTH_FAILED(HttpStatus.BAD_GATEWAY, false),
    LLM_CONNECT_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, true),
    LLM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true),
    TASK_RETRY_EXHAUSTED(HttpStatus.CONFLICT, false),
    DOCUMENT_FORMAT_UNSUPPORTED(HttpStatus.BAD_REQUEST, false),
    DOCUMENT_TOO_LARGE(HttpStatus.BAD_REQUEST, false),
    DOCUMENT_PARSE_FAILED(HttpStatus.BAD_REQUEST, false),
    DOCUMENT_INDEX_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, true),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, true);

    private final HttpStatus status;
    private final boolean retryable;

    ErrorCode(HttpStatus status, boolean retryable) {
        this.status = status;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }
}
