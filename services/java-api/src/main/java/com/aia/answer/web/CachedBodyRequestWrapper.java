package com.aia.answer.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 把请求体缓存到内存，使幂等过滤器可以「先算哈希再交给控制器读取」。
 *
 * <p>请求体上限由 {@code SecurityFilter} 在更早的环节拦截，因此这里不会缓存超大请求。
 */
public class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyRequestWrapper(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    public byte[] cachedBody() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream stream = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return stream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException("异步读取未启用");
            }

            @Override
            public int read() {
                return stream.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
    }

    /** 读取原始请求体。 */
    public static byte[] readBody(HttpServletRequest request) throws IOException {
        return request.getInputStream().readAllBytes();
    }
}
