package com.aia.answer.service;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.AppInfo;
import com.aia.answer.common.AtomicWrite;
import com.aia.answer.common.ErrorCode;
import com.aia.answer.common.Json;
import com.aia.answer.config.AppProperties;
import com.aia.answer.domain.SystemDtos;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Python AI 服务的唯一出口（契约 §7）。
 *
 * <p>端点与内部令牌来自 Python 启动时写出的端点文件；Java 不缓存密钥，也不直连模型供应商。
 * AI 不可用时抛出的错误码统一为 {@code LLM_UNAVAILABLE}，调用方必须保证主流程不受影响。
 */
@Service
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final AppProperties props;
    private final Json json;
    private final HttpClient http;

    private volatile Endpoint cachedEndpoint;
    private volatile long cachedModifiedAt = -1L;

    public AiClient(AppProperties props, Json json) {
        this.props = props;
        this.json = json;
        this.http = HttpClient.newBuilder()
                // 必须锁定 HTTP/1.1：JDK HttpClient 默认会尝试 h2c 升级（Upgrade: h2c），
                // 而 uvicorn 的 h11 实现不支持该升级，会把请求判为非法 HTTP 并返回 400。
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public record Endpoint(String baseUrl, String token) {
    }

    /** 读取 Python 端点文件；文件缺失即视为 AI 不可用。 */
    public Optional<Endpoint> endpoint() {
        Path file = props.resolvePythonEndpointFile();
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (cachedEndpoint != null && modified == cachedModifiedAt) {
                return Optional.of(cachedEndpoint);
            }
            Optional<String> raw = AtomicWrite.readString(file);
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            JsonNode node = json.readTreeOrNull(raw.get());
            if (node == null || node.path("baseUrl").asText("").isBlank()) {
                return Optional.empty();
            }
            Endpoint endpoint = new Endpoint(node.path("baseUrl").asText(), node.path("token").asText(""));
            cachedEndpoint = endpoint;
            cachedModifiedAt = modified;
            return Optional.of(endpoint);
        } catch (IOException e) {
            log.warn("读取 Python 端点文件失败：{}", e.getMessage());
            return Optional.empty();
        }
    }

    public boolean available() {
        return endpoint().isPresent();
    }

    public SystemDtos.AiServiceStatus status() {
        Optional<Endpoint> endpoint = endpoint();
        if (endpoint.isEmpty()) {
            return new SystemDtos.AiServiceStatus(false, null, null, "AI 服务未启动");
        }
        long start = System.nanoTime();
        try {
            // /health 是裸对象，不走 {success,data,...} 包络，必须单独处理
            JsonNode health = sendRaw(HttpRequest.newBuilder(URI.create(endpoint.get().baseUrl() + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build());
            long latency = (System.nanoTime() - start) / 1_000_000;
            String version = health.path("version").asText(AppInfo.VERSION);
            boolean up = "UP".equalsIgnoreCase(health.path("status").asText(""));
            return new SystemDtos.AiServiceStatus(up, endpoint.get().baseUrl(), latency,
                    up ? "版本 " + version : "AI 服务状态异常");
        } catch (RuntimeException e) {
            return new SystemDtos.AiServiceStatus(false, endpoint.get().baseUrl(),
                    (System.nanoTime() - start) / 1_000_000, e.getMessage());
        }
    }

    public JsonNode explain(JsonNode payload) {
        return post("/api/v1/ai/explain", payload);
    }

    public JsonNode gradeSubjective(JsonNode payload) {
        return post("/api/v1/ai/grade-subjective", payload);
    }

    public JsonNode testConnection(JsonNode payload) {
        return post("/api/v1/ai/test-connection", payload);
    }

    private JsonNode get(String path, boolean authenticated) {
        Endpoint endpoint = requireEndpoint();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (authenticated) {
            builder.header("X-Internal-Token", endpoint.token());
        }
        return send(builder.build());
    }

    /** 直接返回解析后的响应体，不做包络校验（仅用于 /health 这类裸对象端点）。 */
    private JsonNode sendRaw(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode body = json.readTreeOrNull(response.body());
            if (body == null) {
                throw new ApiException(ErrorCode.LLM_UNAVAILABLE,
                        "AI 服务返回了无法解析的响应（HTTP " + response.statusCode() + "）");
            }
            if (response.statusCode() >= 400) {
                throw new ApiException(ErrorCode.LLM_UNAVAILABLE, "AI 服务返回 HTTP " + response.statusCode());
            }
            return body;
        } catch (HttpTimeoutException e) {
            throw new ApiException(ErrorCode.LLM_CONNECT_TIMEOUT, "调用 AI 服务超时");
        } catch (ConnectException e) {
            throw new ApiException(ErrorCode.LLM_UNAVAILABLE, "无法连接 AI 服务（可能尚未启动）");
        } catch (IOException e) {
            throw new ApiException(ErrorCode.LLM_UNAVAILABLE, "AI 服务通信失败：" + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.LLM_UNAVAILABLE, "AI 调用被中断");
        }
    }

    private JsonNode post(String path, JsonNode payload) {
        Endpoint endpoint = requireEndpoint();
        int timeoutSeconds = payload != null && payload.path("profile").has("timeoutSeconds")
                ? Math.max(5, Math.min(300, payload.path("profile").path("timeoutSeconds").asInt(60) + 5))
                : 90;
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-Internal-Token", endpoint.token())
                .POST(HttpRequest.BodyPublishers.ofString(json.write(payload), StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.body() != null && response.body().length() > MAX_RESPONSE_BYTES) {
                throw new ApiException(ErrorCode.LLM_UNAVAILABLE, "AI 服务响应体超过上限");
            }
            JsonNode body = json.readTreeOrNull(response.body());
            if (body == null) {
                throw new ApiException(ErrorCode.LLM_UNAVAILABLE, "AI 服务返回了无法解析的响应（HTTP " + response.statusCode() + "）");
            }
            if (body.path("success").asBoolean(false)) {
                return body.path("data");
            }
            String code = body.path("error").path("code").asText("LLM_UNAVAILABLE");
            String message = body.path("error").path("message").asText("AI 服务调用失败");
            throw new ApiException(mapErrorCode(code), message);
        } catch (HttpTimeoutException e) {
            throw new ApiException(ErrorCode.LLM_CONNECT_TIMEOUT, "调用 AI 服务超时");
        } catch (ConnectException e) {
            throw new ApiException(ErrorCode.LLM_UNAVAILABLE, "无法连接 AI 服务（可能尚未启动）");
        } catch (IOException e) {
            throw new ApiException(ErrorCode.LLM_UNAVAILABLE, "AI 服务通信失败：" + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.LLM_UNAVAILABLE, "AI 调用被中断");
        }
    }

    private ErrorCode mapErrorCode(String code) {
        try {
            return ErrorCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            return ErrorCode.LLM_UNAVAILABLE;
        }
    }

    private Endpoint requireEndpoint() {
        return endpoint().orElseThrow(() -> new ApiException(ErrorCode.LLM_UNAVAILABLE,
                "AI 服务未就绪：请在桌面端启动 Python AI 服务后重试"));
    }
}
