package com.mindbridge.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeChunkerTests {

    @Test
    void groupsParagraphsBySectionAndSplitsOverlongParagraphsByTokenLimit() {
        MarkdownDocument.Heading guide = new MarkdownDocument.Heading(1, "Guide");
        MarkdownDocument.Heading sleep = new MarkdownDocument.Heading(2, "Sleep");
        MarkdownDocument document = new MarkdownDocument(
                List.of(guide, sleep),
                List.of(
                        new MarkdownDocument.Block(
                                MarkdownDocument.BlockType.PARAGRAPH, "one", List.of(guide)),
                        new MarkdownDocument.Block(
                                MarkdownDocument.BlockType.PARAGRAPH, "two", List.of(guide)),
                        new MarkdownDocument.Block(
                                MarkdownDocument.BlockType.PARAGRAPH,
                                "一二三四五六七八九",
                                List.of(guide, sleep))),
                List.of());

        List<KnowledgeChunker.Chunk> chunks = new KnowledgeChunker().chunk(document, 4);

        assertThat(chunks).extracting(KnowledgeChunker.Chunk::content)
                .containsExactly("one\n\ntwo", "一二三四", "五六七八", "九");
        assertThat(chunks).extracting(KnowledgeChunker.Chunk::sectionPath)
                .containsExactly("Guide", "Guide / Sleep", "Guide / Sleep", "Guide / Sleep");
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.type()).isEqualTo(MarkdownDocument.BlockType.PARAGRAPH);
            assertThat(chunk.estimatedTokens()).isLessThanOrEqualTo(4);
        });
    }

    @Test
    void splitsLongTablesByRowsAndRepeatsTheHeader() {
        MarkdownDocument.Block table = new MarkdownDocument.Block(
                MarkdownDocument.BlockType.TABLE,
                "|Name|Phone|\n|---|---|\n|A|111|\n|B|222|",
                List.of());

        List<KnowledgeChunker.Chunk> chunks = new KnowledgeChunker().chunk(
                new MarkdownDocument(List.of(), List.of(table), List.of()), 7);

        assertThat(chunks).extracting(KnowledgeChunker.Chunk::content)
                .containsExactly(
                        "|Name|Phone|\n|---|---|\n|A|111|",
                        "|Name|Phone|\n|---|---|\n|B|222|");
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.type()).isEqualTo(MarkdownDocument.BlockType.TABLE);
            assertThat(chunk.estimatedTokens()).isLessThanOrEqualTo(7);
        });
    }

    @Test
    void splitsLongCodeBlocksWithoutDroppingFences() {
        MarkdownDocument.Block code = new MarkdownDocument.Block(
                MarkdownDocument.BlockType.CODE,
                "```java\nalpha();\nbeta();\n```",
                List.of());

        List<KnowledgeChunker.Chunk> chunks = new KnowledgeChunker().chunk(
                new MarkdownDocument(List.of(), List.of(code), List.of()), 5);

        assertThat(chunks).extracting(KnowledgeChunker.Chunk::content)
                .containsExactly("```java\nalpha();\n```", "```java\nbeta();\n```");
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.type()).isEqualTo(MarkdownDocument.BlockType.CODE);
            assertThat(chunk.estimatedTokens()).isLessThanOrEqualTo(5);
        });
    }

    @Test
    void repeatsOriginalImagePathWhenDescriptionNeedsMultipleChunks() {
        String path = "images/chart.png";
        ImageDescription description = new ImageDescription("chart", "A".repeat(120),
                List.of(), List.of(), List.of());
        var image = new DocumentParseResult.ImageReference(path, null, null, description);

        List<KnowledgeChunker.Chunk> chunks = new KnowledgeChunker().chunkImages(
                List.of(image), MarkdownDocument.empty(), 12);

        assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(chunk -> {
            assertThat(chunk.content()).startsWith("原图：" + path + "\n");
            assertThat(chunk.imagePath()).isEqualTo(path);
            assertThat(chunk.type()).isEqualTo(MarkdownDocument.BlockType.IMAGE);
            assertThat(chunk.estimatedTokens()).isLessThanOrEqualTo(12);
        });
    }
}
