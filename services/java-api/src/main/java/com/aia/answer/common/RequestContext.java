package com.aia.answer.common;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 每请求上下文：requestId 用于响应包络与日志关联（契约 §0.1）。
 */
public final class RequestContext {

    private static final String MDC_KEY = "requestId";
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestContext() {
    }

    public static String begin() {
        String id = Ids.requestId();
        CURRENT.set(id);
        MDC.put(MDC_KEY, id);
        return id;
    }

    public static String requestId() {
        String id = CURRENT.get();
        return id != null ? id : "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }
}
