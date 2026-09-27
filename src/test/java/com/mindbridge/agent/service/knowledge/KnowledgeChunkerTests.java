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
}
