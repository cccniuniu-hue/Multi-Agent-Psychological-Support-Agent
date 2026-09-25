package com.mindbridge.agent.service.knowledge;

import java.util.List;

/** 统一的文档解析结果；页码从 1 开始，图片路径相对于原文档。 */
public record DocumentParseResult(
        String source,
        String title,
        String body,
        List<Page> pages,
        List<ImageReference> images,
        MarkdownDocument markdown,
        Status status,
        String error
) {

    public DocumentParseResult {
        pages = List.copyOf(pages);
        images = List.copyOf(images);
        markdown = markdown == null ? MarkdownDocument.empty() : markdown;
    }

    public DocumentParseResult(
            String source,
            String title,
            String body,
            List<Page> pages,
            List<ImageReference> images,
            Status status,
            String error
    ) {
        this(source, title, body, pages, images, MarkdownDocument.empty(), status, error);
    }

    public DocumentParseResult withMarkdown(MarkdownDocument markdown) {
        return new DocumentParseResult(source, title, body, pages, images, markdown, status, error);
    }

    public record Page(int number, String body) {
    }

    public record ImageReference(String path, Integer pageNumber) {
    }

    public enum Status {
        SUCCESS,
        EMPTY,
        FAILED
    }
}
