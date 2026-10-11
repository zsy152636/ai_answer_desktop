package com.aia.answer.web;

import com.aia.answer.common.ApiResponse;
import com.aia.answer.common.ErrorCode;
import com.aia.answer.common.Json;
import com.aia.answer.service.IdempotencyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * 幂等过滤器（契约 §0.4）。
 *
 * <p>只对带 {@code Idempotency-Key} 的 POST/PUT 生效：首次请求正常执行并把
 * 「状态码 + 响应体」按 key 落库；同键同载荷的重复请求直接重放首次结果；
 * 同键不同载荷返回 {@code IDEMPOTENCY_CONFLICT}。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class IdempotencyFilter extends OncePerRequestFilter {

    private final IdempotencyService idempotencyService;
    private final Json json;

    public IdempotencyFilter(IdempotencyService idempotencyService, Json json) {
        this.idempotencyService = idempotencyService;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader("Idempotency-Key");
        String method = request.getMethod();
        boolean applicable = IdempotencyService.validKey(key)
                && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method));
        if (!applicable) {
            chain.doFilter(request, response);
            return;
        }
        if (!IdempotencyService.validKey(key)) {
            writeError(response, ErrorCode.VALIDATION_ERROR, "Idempotency-Key 长度必须在 1 到 128 之间");
            return;
        }

        byte[] body = CachedBodyRequestWrapper.readBody(request);
        String scope = method + " " + request.getRequestURI();
        String hash = IdempotencyService.hash(method, request.getRequestURI(), body);

        Optional<com.aia.answer.repository.IdempotencyRepository.Record> existing = idempotencyService.find(key);
        if (existing.isPresent()) {
            com.aia.answer.repository.IdempotencyRepository.Record record = existing.get();
            if (!record.requestHash().equals(hash) || !record.scope().equals(scope)) {
                writeError(response, ErrorCode.IDEMPOTENCY_CONFLICT,
                        "同一 Idempotency-Key 被用于不同请求载荷，已拒绝执行");
                return;
            }
            response.setStatus(record.statusCode());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Idempotent-Replay", "true");
            response.getWriter().write(record.responseJson());
            return;
        }

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(new CachedBodyRequestWrapper(request, body), wrappedResponse);
        } finally {
            byte[] content = wrappedResponse.getContentAsByteArray();
            int status = wrappedResponse.getStatus();
            if (status < 500 && content.length > 0) {
                idempotencyService.save(key, scope, hash, status, new String(content, StandardCharsets.UTF_8));
            }
            wrappedResponse.copyBodyToResponse();
        }
    }

    private void writeError(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.write(ApiResponse.fail(code, message, List.of())));
    }
}
