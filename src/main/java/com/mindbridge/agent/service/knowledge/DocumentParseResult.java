package com.mindbridge.agent.service.knowledge;

import java.util.List;

/** 统一的文档解析结果；页码从 1 开始，图片路径相对于原文档。 */
public record DocumentParseResult(
        String source,
        String title,
        String body,
        List<Page> pages,
        List<ImageReference> images,
        Status status,
        String error
) {

    public DocumentParseResult {
        pages = List.copyOf(pages);
        images = List.copyOf(images);
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
