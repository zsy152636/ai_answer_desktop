package com.aia.answer.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 全局异常 → 统一错误包络（契约 §0.2 / §0.3）。
 *
 * <p>内部错误只回传 requestId，不回传堆栈、SQL 或路径，避免泄漏本机信息。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApi(ApiException ex) {
        if (ex.code().status().is5xxServerError()) {
            log.warn("业务异常 requestId={} code={} message={}", RequestContext.requestId(), ex.code(), ex.getMessage());
        }
        return ResponseEntity.status(ex.code().status())
                .body(ApiResponse.fail(ex.code(), ex.getMessage(), ex.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleBodyValidation(MethodArgumentNotValidException ex) {
        List<ApiException.FieldViolation> details = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            details.add(new ApiException.FieldViolation(fe.getField(), fe.getDefaultMessage()));
        }
        String message = details.isEmpty() ? "请求参数校验失败" : details.get(0).message();
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiResponse.fail(ErrorCode.VALIDATION_ERROR, message, details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException ex) {
        List<ApiException.FieldViolation> details = ex.getConstraintViolations().stream()
                .map(v -> new ApiException.FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        String message = details.isEmpty() ? "请求参数校验失败" : details.get(0).message();
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiResponse.fail(ErrorCode.VALIDATION_ERROR, message, details));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex) {
        String field = null;
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            field = mismatch.getName();
        } else if (ex instanceof MissingServletRequestParameterException missing) {
            field = missing.getParameterName();
        }
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiResponse.fail(ErrorCode.VALIDATION_ERROR, "请求格式或参数类型不正确",
                        field == null ? List.of() : List.of(new ApiException.FieldViolation(field, "invalid"))));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiResponse.fail(ErrorCode.VALIDATION_ERROR, "该资源不支持此请求方法", List.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.status())
                .body(ApiResponse.fail(ErrorCode.RESOURCE_NOT_FOUND,
                        "接口不存在：" + request.getMethod() + " " + request.getRequestURI(), List.of()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccess(DataAccessException ex) {
        String text = ex.getMostSpecificCause() == null ? "" : String.valueOf(ex.getMostSpecificCause().getMessage());
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("locked") || lower.contains("busy")) {
            log.warn("SQLite 繁忙 requestId={}", RequestContext.requestId());
            return ResponseEntity.status(ErrorCode.DB_BUSY.status())
                    .body(ApiResponse.fail(ErrorCode.DB_BUSY, "数据库繁忙，请稍后重试", List.of()));
        }
        log.error("数据库访问失败 requestId={}", RequestContext.requestId(), ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, "数据库访问失败", List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception ex) {
        log.error("未预期错误 requestId={}", RequestContext.requestId(), ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR,
                        "服务内部错误，请把请求编号提供给诊断日志：" + RequestContext.requestId(), List.of()));
    }
}
