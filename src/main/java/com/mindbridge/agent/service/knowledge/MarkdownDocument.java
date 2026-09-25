package com.mindbridge.agent.service.knowledge;

import java.util.List;

/** Structured Markdown content used by section-aware knowledge chunking. */
public record MarkdownDocument(
        List<Heading> headings,
        List<Block> blocks,
        List<ImageReference> images
) {

    public MarkdownDocument {
        headings = List.copyOf(headings);
        blocks = List.copyOf(blocks);
        images = List.copyOf(images);
    }

    public static MarkdownDocument empty() {
        return new MarkdownDocument(List.of(), List.of(), List.of());
    }

    public record Heading(int level, String text) {
    }

    public record Block(BlockType type, String text, List<Heading> headingPath) {
        public Block {
            headingPath = List.copyOf(headingPath);
        }
    }

    public record ImageReference(
            String destination,
            String altText,
            String title,
            List<Heading> headingPath
    ) {
        public ImageReference {
            headingPath = List.copyOf(headingPath);
        }
    }

    public enum BlockType {
        PARAGRAPH,
        TABLE
    }
}
