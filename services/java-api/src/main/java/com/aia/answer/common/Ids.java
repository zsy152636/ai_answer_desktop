package com.aia.answer.common;

import java.security.SecureRandom;

/**
 * 标识生成：ULID（26 位 Crockford Base32，按时间字典序递增）+ 业务前缀。
 */
public final class Ids {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String ulid() {
        long ts = System.currentTimeMillis();
        char[] out = new char[26];
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (ts & 31)];
            ts >>>= 5;
        }
        for (int i = 10; i < 26; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(32)];
        }
        return new String(out);
    }

    public static String of(String prefix) {
        return prefix + "_" + ulid();
    }

    public static String requestId() {
        return "req_" + ulid().toLowerCase();
    }

    public static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
