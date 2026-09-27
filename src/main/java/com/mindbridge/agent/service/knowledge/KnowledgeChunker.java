package com.mindbridge.agent.service.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库文本切块器。
 *
 * <p>优先在换行、句号和英文标点附近切分，减少单个片段语义被截断。</p>
 */
public class KnowledgeChunker {

    public List<Chunk> chunk(MarkdownDocument document, int maxTokens) {
        int limit = Math.max(1, maxTokens);
        List<Chunk> chunks = new ArrayList<>();
        String pending = "";
        String pendingSection = "";

        for (MarkdownDocument.Block block : document.blocks()) {
            String section = block.headingPath().stream()
                    .map(MarkdownDocument.Heading::text)
                    .reduce((left, right) -> left + " / " + right)
                    .orElse("");
            if (block.type() != MarkdownDocument.BlockType.PARAGRAPH) {
                addChunk(chunks, pending, pendingSection, MarkdownDocument.BlockType.PARAGRAPH);
                pending = "";
                chunks.add(new Chunk(block.text(), section, block.type(), estimateTokens(block.text())));
                continue;
            }

            if (estimateTokens(block.text()) > limit) {
                addChunk(chunks, pending, pendingSection, MarkdownDocument.BlockType.PARAGRAPH);
                pending = "";
                for (String part : splitByTokens(block.text(), limit)) {
                    chunks.add(new Chunk(part, section, block.type(), estimateTokens(part)));
                }
                continue;
            }

            String combined = pending.isEmpty() ? block.text() : pending + "\n\n" + block.text();
            if (!pending.isEmpty() && (!pendingSection.equals(section) || estimateTokens(combined) > limit)) {
                addChunk(chunks, pending, pendingSection, MarkdownDocument.BlockType.PARAGRAPH);
                pending = block.text();
            } else {
                pending = combined;
            }
            pendingSection = section;
        }
        addChunk(chunks, pending, pendingSection, MarkdownDocument.BlockType.PARAGRAPH);
        return chunks;
    }

    public List<String> chunk(String content, int chunkSize, int overlap) {
        String text = content.replace("\r\n", "\n").trim();
        if (text.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int safeSize = Math.max(120, chunkSize);
        int safeOverlap = Math.max(0, Math.min(overlap, safeSize / 2));
        int index = 0;
        while (index < text.length()) {
            int end = Math.min(text.length(), index + safeSize);
            if (end < text.length()) {
                // 尽量在自然边界切开，找不到合适边界时才按固定长度切。
                int boundary = Math.max(
                        Math.max(text.lastIndexOf("\n", end), text.lastIndexOf("。", end)),
                        Math.max(text.lastIndexOf(".", end), text.lastIndexOf("?", end)));
                if (boundary > index + safeSize / 2) {
                    end = boundary + 1;
                }
            }
            chunks.add(text.substring(index, end).trim());
            if (end >= text.length()) {
                break;
            }
            index = Math.max(0, end - safeOverlap);
        }
        return chunks;
    }

    private void addChunk(List<Chunk> chunks, String content, String section, MarkdownDocument.BlockType type) {
        if (!content.isBlank()) {
            chunks.add(new Chunk(content, section, type, estimateTokens(content)));
        }
    }

    private List<String> splitByTokens(String text, int maxTokens) {
        List<String> parts = new ArrayList<>();
        int maxUnits = maxTokens * 4;
        int start = 0;
        int units = 0;
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            int next = index + Character.charCount(codePoint);
            int cost = tokenUnits(codePoint);
            if (units > 0 && units + cost > maxUnits) {
                parts.add(text.substring(start, index).strip());
                start = index;
                units = 0;
            }
            units += cost;
            index = next;
        }
        if (start < text.length()) {
            parts.add(text.substring(start).strip());
        }
        return parts.stream().filter(part -> !part.isBlank()).toList();
    }

    private int estimateTokens(String text) {
        int units = text.codePoints().map(this::tokenUnits).sum();
        return (units + 3) / 4;
    }

    private int tokenUnits(int codePoint) {
        if (Character.isWhitespace(codePoint)) {
            return 0;
        }
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN ? 4 : 1;
    }

    public record Chunk(
            String content,
            String sectionPath,
            MarkdownDocument.BlockType type,
            int estimatedTokens
    ) {
    }
}
