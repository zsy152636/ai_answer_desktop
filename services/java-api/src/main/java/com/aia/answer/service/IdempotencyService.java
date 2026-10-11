package com.aia.answer.service;

import com.aia.answer.common.Ids;
import com.aia.answer.config.AppProperties;
import com.aia.answer.repository.IdempotencyRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 幂等记录服务（契约 §0.4）。
 *
 * <p>语义是「同键同载荷返回首次结果，同键不同载荷拒绝」：后者比静默重放更安全，
 * 因为它暴露了调用方的键复用错误。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final AppProperties props;

    public IdempotencyService(IdempotencyRepository repository, AppProperties props) {
        this.repository = repository;
        this.props = props;
    }

    public Optional<IdempotencyRepository.Record> find(String key) {
        return repository.find(key);
    }

    public void save(String key, String scope, String requestHash, int statusCode, String responseJson) {
        String now = nowUtc();
        String expiresAt = DateTimeFormatter.ISO_INSTANT.format(
                Instant.now().plus(Duration.ofDays(Math.max(1, props.getIdempotencyRetentionDays())))
                        .truncatedTo(ChronoUnit.SECONDS));
        repository.save(key, scope, requestHash, statusCode, responseJson, now, expiresAt);
    }

    public static String hash(String method, String path, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(method.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            if (body != null) {
                digest.update(body);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean validKey(String key) {
        return key != null && !key.isBlank() && key.length() <= 128;
    }

    public static String newKey() {
        return Ids.ulid();
    }

    private static String nowUtc() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
