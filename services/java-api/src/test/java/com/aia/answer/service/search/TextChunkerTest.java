package com.aia.answer.service.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文本分块单元测试（开发文档 §523）。
 *
 * <p>核心不变式是**确定性**：同一输入必须得到完全相同的分块序列，否则索引重建后
 * 「同一条检索结论」无法复现，阶段 6 的「来源可追溯」也就失去意义。
 */
class TextChunkerTest {

    @Test
    @DisplayName("规范化：去 BOM、统一换行、去行尾空白、压缩连续空行")
    void normalizesText() {
        String raw = "\uFEFF第一行  \r\n\r\n\r\n第二行\t\r\n";
        String normalized = TextChunker.normalize(raw);
        assertThat(normalized).isEqualTo("第一行\n\n第二行");
    }

    @Test
    @DisplayName("分块确定性：同一输入多次分块结果完全一致")
    void chunkingIsDeterministic() {
        String text = TextChunker.normalize(buildParagraphs(6, 220));
        List<String> first = TextChunker.chunk(text);
        List<String> second = TextChunker.chunk(text);
        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEmpty();
    }

    @Test
    @DisplayName("分块长度受控：单块不超过上限，且在目标长度附近装箱")
    void chunkSizeIsBounded() {
        List<String> chunks = TextChunker.chunk(TextChunker.normalize(buildParagraphs(8, 300)));
        assertThat(chunks).isNotEmpty();
        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(TextChunker.MAX_CHARS);
        }
        // 除了最后一块，其余应达到目标长度附近（装箱有效）
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertThat(chunks.get(i).length()).isGreaterThan(TextChunker.TARGET_CHARS / 2);
        }
    }

    @Test
    @DisplayName("超长段落按句子边界切分，不切断句子")
    void splitsOversizedParagraphAtSentenceBoundary() {
        String sentence = "这是一句用于测试的中文句子，长度足够触发切分逻辑。";
        StringBuilder builder = new StringBuilder();
        while (builder.length() < TextChunker.MAX_CHARS * 3) {
            builder.append(sentence);
        }
        List<String> chunks = TextChunker.chunk(TextChunker.normalize(builder.toString()));
        assertThat(chunks).hasSizeGreaterThan(1);
        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(TextChunker.MAX_CHARS);
            assertThat(chunk).endsWith("。");
        }
    }

    @Test
    @DisplayName("空输入与纯空白输入不产生任何分块")
    void emptyInputProducesNoChunks() {
        assertThat(TextChunker.chunk(TextChunker.normalize(""))).isEmpty();
        assertThat(TextChunker.chunk(TextChunker.normalize("   \n\n  \t "))).isEmpty();
        assertThat(TextChunker.chunk(null)).isEmpty();
    }

    @Test
    @DisplayName("解析器版本按来源类型区分，且随分块策略版本化")
    void parserVersionReflectsSourceKind() {
        assertThat(TextChunker.parserVersion("text")).isEqualTo(TextChunker.PARSER_VERSION_TEXT);
        assertThat(TextChunker.parserVersion("markdown")).isEqualTo(TextChunker.PARSER_VERSION_MARKDOWN);
        assertThat(TextChunker.PARSER_VERSION_TEXT).isEqualTo("text-v1");
    }

    private static String buildParagraphs(int count, int lengthEach) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append("\n\n");
            }
            builder.append("第").append(i + 1).append("段：");
            while (builder.length() % lengthEach != 0) {
                builder.append("知识");
            }
        }
        return builder.toString();
    }
}
