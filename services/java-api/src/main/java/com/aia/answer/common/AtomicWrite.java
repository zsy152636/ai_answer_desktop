package com.aia.answer.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * 原子文件写入：先写同目录临时文件，再 ATOMIC_MOVE 替换目标。
 * 端点文件与恢复请求文件都必须走这里，避免读到半截 JSON（开发文档 §9.1 的一致性原则）。
 */
public final class AtomicWrite {

    private AtomicWrite() {
    }

    public static void writeString(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + System.nanoTime());
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static Optional<String> readString(Path source) {
        if (source == null || !Files.exists(source)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(source, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static String sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                digest.update(buf, 0, read);
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
    }
}
