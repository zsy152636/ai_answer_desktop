package com.aia.answer.web;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.ApiResponse;
import com.aia.answer.common.ErrorCode;
import com.aia.answer.common.Json;
import com.aia.answer.common.RequestContext;
import com.aia.answer.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 桌面安全基线（开发文档 §2.2、§7）。
 *
 * <p>本过滤器一次完成四件事，顺序不可调换：
 * <ol>
 *   <li>请求编号注入（响应包络与日志关联）；</li>
 *   <li>请求体上限与 Host 头校验；</li>
 *   <li>CORS 预检放行与来源白名单（渲染进程来自 file:// 或 Vite 开发端口）；</li>
 *   <li>会话令牌校验：{@code /health} 之外的所有接口都必须携带本次运行有效的 X-Session-Token。</li>
 * </ol>
 *
 * <p>令牌只在内存中，退出即失效；日志与响应永不回显令牌。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SecurityFilter.class);
    private static final String TOKEN_HEADER = "X-Session-Token";
    private static final List<String> ALLOWED_HEADERS =
            List.of("Content-Type", "X-Session-Token", "Idempotency-Key");
    private static final List<String> ALLOWED_METHODS =
            List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    private final AppProperties props;
    private final Json json;
    private final String sessionToken;

    public SecurityFilter(AppProperties props, Json json) {
        this.props = props;
        this.json = json;
        this.sessionToken = (props.getSessionToken() == null || props.getSessionToken().isBlank())
                ? com.aia.answer.common.Ids.token()
                : props.getSessionToken();
    }

    /** 供启动步骤写端点文件使用。 */
    public String sessionToken() {
        return sessionToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RequestContext.begin();
        try {
            String origin = request.getHeader("Origin");

            // 1. 请求体上限（开发文档 §6.4：限制响应大小；请求同侧对称限制）
            long contentLength = request.getContentLengthLong();
            if (contentLength > props.getMaxPayloadBytes()) {
                reject(response, ErrorCode.VALIDATION_ERROR, "请求体超过上限 " + props.getMaxPayloadBytes() + " 字节");
                return;
            }

            // 2. Host 校验：只接受 loopback 主机名
            if (!hostAllowed(request.getHeader("Host"))) {
                reject(response, ErrorCode.ORIGIN_REJECTED, "Host 不在可信列表内");
                return;
            }

            // 3. CORS：预检必须在校验令牌之前放行，否则浏览器拿不到响应头
            if (!originAllowed(origin)) {
                reject(response, ErrorCode.ORIGIN_REJECTED, "请求来源不在可信列表内");
                return;
            }
            applyCors(response, origin);
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                return;
            }

            // 4. 会话令牌
            String path = request.getRequestURI();
            if (!"/health".equals(path) && path.startsWith("/api/")) {
                String provided = request.getHeader(TOKEN_HEADER);
                if (provided == null || !constantTimeEquals(provided, sessionToken)) {
                    log.warn("拒绝未授权访问 path={} requestId={}", path, RequestContext.requestId());
                    reject(response, ErrorCode.UNAUTHORIZED, "会话令牌缺失或已失效，请重启桌面应用");
                    return;
                }
            }

            response.setHeader("X-Request-Id", RequestContext.requestId());
            response.setHeader("Cache-Control", "no-store");
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    private void applyCors(HttpServletResponse response, String origin) {
        if (origin != null && !origin.isBlank()) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
        } else {
            response.setHeader("Access-Control-Allow-Origin", "*");
        }
        response.setHeader("Access-Control-Allow-Methods", String.join(", ", ALLOWED_METHODS));
        response.setHeader("Access-Control-Allow-Headers", String.join(", ", ALLOWED_HEADERS));
        response.setHeader("Access-Control-Max-Age", "600");
    }

    private boolean hostAllowed(String host) {
        if (host == null || host.isBlank()) {
            // HTTP/1.0 或无 Host：loopback 绑定下不构成风险
            return true;
        }
        String value = host.trim().toLowerCase(java.util.Locale.ROOT);
        String name = value;
        if (name.startsWith("[")) {
            int end = name.indexOf(']');
            name = end > 0 ? name.substring(1, end) : name;
        } else if (name.contains(":")) {
            name = name.substring(0, name.indexOf(':'));
        }
        return "127.0.0.1".equals(name) || "localhost".equals(name) || "::1".equals(name);
    }

    private boolean originAllowed(String origin) {
        if (origin == null || origin.isBlank() || "null".equalsIgnoreCase(origin)) {
            // file:// 渲染进程发送 Origin: null；无 Origin 表示非浏览器调用（如本机脚本）
            return true;
        }
        String value = origin.toLowerCase(java.util.Locale.ROOT);
        return value.startsWith("http://127.0.0.1")
                || value.startsWith("http://localhost")
                || value.startsWith("http://[::1]")
                || value.startsWith("file://")
                || value.startsWith("app://");
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiResponse<Void> body = ApiResponse.fail(code, message, List.of());
        response.getWriter().write(json.write(body));
    }

    /** 暴露给启动步骤：把错误包络写成字符串（用于非 Servlet 场景）。 */
    static String describe(ApiException ex) {
        return ex.code().name() + ": " + ex.getMessage();
    }
}
