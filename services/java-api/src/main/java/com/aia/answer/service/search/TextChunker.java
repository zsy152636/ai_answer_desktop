package com.aia.answer.service.search;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块（开发文档 §523）。
 *
 * <p>分块必须**确定性**：同一份原文、同一 {@code parser_version} 在任何一次重建里都产生完全相同的
 * 分块序列，否则「历史检索结论可复现」就无从谈起。因此这里不使用随机、不依赖时间、不做采样。
 *
 * <p>策略：先规范化（BOM/换行/行尾空白），再按空行切段；段落按顺序装箱到 {@link #TARGET_CHARS}
 * 附近，单段超过 {@link #MAX_CHARS} 时在句子边界处硬切。不做重叠：重叠会让同一条命中在多个分块里
 * 重复出现，反而降低「引用来源」的可读性。
 */
public final class TextChunker {

    /** 目标分块长度（字符）。 */
    public static final int TARGET_CHARS = 800;
    /** 单个分块上限（字符）；超过时在句子边界切分。 */
    public static final int MAX_CHARS = 1200;
    /** 解析器版本：随分块策略变化而提升，用于索引重建与溯源。 */
    public static final String PARSER_VERSION_TEXT = "text-v1";
    public static final String PARSER_VERSION_MARKDOWN = "markdown-v1";

    private static final String SENTENCE_ENDINGS = "。！？!?；;.";

    private TextChunker() {
    }

    public static String parserVersion(String sourceKind) {
        return "markdown".equals(sourceKind) ? PARSER_VERSION_MARKDOWN : PARSER_VERSION_TEXT;
    }

    /** 规范化：去 BOM、统一换行、去行尾空白、压缩连续空行。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = text.split("\n", -1);
        StringBuilder builder = new StringBuilder(text.length());
        int blankRun = 0;
        for (String line : lines) {
            String stripped = stripTrailing(line);
            if (stripped.isEmpty()) {
                blankRun++;
                if (blankRun > 1) {
                    continue;
                }
            } else {
                blankRun = 0;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(stripped);
        }
        return builder.toString().trim();
    }

    /** 切分为分块序列；空文本返回空列表。 */
    public static List<String> chunk(String normalizedText) {
        List<String> chunks = new ArrayList<>();
        if (normalizedText == null || normalizedText.isEmpty()) {
            return chunks;
        }
        StringBuilder current = new StringBuilder();
        for (String paragraph : normalizedText.split("\n\\s*\n")) {
            String block = paragraph.trim();
            if (block.isEmpty()) {
                continue;
            }
            for (String piece : splitOversized(block)) {
                if (current.length() == 0) {
                    current.append(piece);
                } else if (current.length() + 2 + piece.length() <= TARGET_CHARS) {
                    current.append("\n\n").append(piece);
                } else {
                    chunks.add(current.toString());
                    current.setLength(0);
                    current.append(piece);
                }
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /** 超过上限的段落按句子边界切分；找不到边界时按上限硬切。 */
    private static List<String> splitOversized(String block) {
        List<String> pieces = new ArrayList<>();
        String remaining = block;
        while (remaining.length() > MAX_CHARS) {
            int cut = lastSentenceBoundary(remaining, MAX_CHARS);
            pieces.add(remaining.substring(0, cut).trim());
            remaining = remaining.substring(cut).trim();
        }
        if (!remaining.isEmpty()) {
            pieces.add(remaining);
        }
        return pieces;
    }

    private static int lastSentenceBoundary(String text, int limit) {
        int floor = Math.max(1, limit / 2);
        for (int i = limit - 1; i >= floor; i--) {
            if (SENTENCE_ENDINGS.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return limit;
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }
}
