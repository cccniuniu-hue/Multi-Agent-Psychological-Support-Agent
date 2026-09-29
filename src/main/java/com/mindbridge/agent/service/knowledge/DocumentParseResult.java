package com.mindbridge.agent.service.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    public DocumentParseResult withImages(List<ImageReference> images) {
        return new DocumentParseResult(source, title, body, pages, images, markdown, status, error);
    }

    public record Page(int number, String body) {
    }

    public record ImageReference(
            String path,
            Integer pageNumber,
            @JsonIgnore String base64Content,
            ImageDescription description
    ) {
        public ImageReference(String path, Integer pageNumber) {
            this(path, pageNumber, null, null);
        }

        public ImageReference(String path, Integer pageNumber, String base64Content) {
            this(path, pageNumber, base64Content, null);
        }

        public ImageReference withDescription(ImageDescription description) {
            return new ImageReference(path, pageNumber, base64Content, description);
        }
    }

    public enum Status {
        SUCCESS,
        EMPTY,
        FAILED
    }
}
