package com.mindbridge.agent.service.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

@Service
/**
 * 管理员文件上传知识库服务。
 *
 * <p>负责文件大小校验、类型识别和文本抽取，抽取后的文本交给 KnowledgeService 处理。</p>
 */
public class KnowledgeFileService {

    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;

    private final KnowledgeService knowledgeService;
    private final MarkerDocumentClient markerClient;

    public KnowledgeFileService(KnowledgeService knowledgeService, MarkerDocumentClient markerClient) {
        this.knowledgeService = knowledgeService;
        this.markerClient = markerClient;
    }

    public int ingest(String filename, byte[] bytes) {
        DocumentParseResult result = parse(filename, bytes);
        if (result.status() == DocumentParseResult.Status.FAILED) {
            throw new IllegalArgumentException(result.error());
        }
        if (result.status() == DocumentParseResult.Status.EMPTY) {
            throw new IllegalArgumentException("没有从文件中解析出可用文本");
        }
        // 文件上传入口只负责解析，真正切块、向量化、落库交给 KnowledgeService。
        return knowledgeService.ingest(result.source(), result.body());
    }

    public DocumentParseResult parse(String filename, byte[] bytes) {
        if (bytes.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件不能超过 10MB");
        }
        String source = sanitizeSource(filename);
        String title = titleFromSource(source);
        var markerResult = markerClient.parse(source, bytes);
        if (markerResult.isPresent()) {
            return markerResult.get();
        }
        List<DocumentParseResult.Page> pages;
        try {
            pages = extractPages(source, bytes);
        } catch (IOException exception) {
            return new DocumentParseResult(source, title, "", List.of(), List.of(),
                    DocumentParseResult.Status.FAILED, "PDF 文本解析失败：" + exception.getMessage());
        }
        String body = pages.stream()
                .map(DocumentParseResult.Page::body)
                .filter(page -> !page.isBlank())
                .map(String::strip)
                .collect(Collectors.joining("\n\n"));
        DocumentParseResult.Status status = body.isBlank()
                ? DocumentParseResult.Status.EMPTY : DocumentParseResult.Status.SUCCESS;
        return new DocumentParseResult(source, title, body, pages, List.of(), status, null);
    }

    private List<DocumentParseResult.Page> extractPages(String filename, byte[] bytes) throws IOException {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return extractPdf(bytes);
        }
        // Markdown 和 txt 都按 UTF-8 文本处理，适合管理员维护轻量知识库。
        if (lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")) {
            return List.of(new DocumentParseResult.Page(1, new String(bytes, StandardCharsets.UTF_8)));
        }
        throw new IllegalArgumentException("仅支持 PDF、Markdown 和 txt 文件");
    }

    private List<DocumentParseResult.Page> extractPdf(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<DocumentParseResult.Page> pages = new ArrayList<>();
            for (int number = 1; number <= document.getNumberOfPages(); number++) {
                stripper.setStartPage(number);
                stripper.setEndPage(number);
                pages.add(new DocumentParseResult.Page(number, stripper.getText(document)));
            }
            return pages;
        }
    }

    private String titleFromSource(String source) {
        int extension = source.lastIndexOf('.');
        return extension > 0 ? source.substring(0, extension) : source;
    }

    private String sanitizeSource(String filename) {
        String source = filename == null || filename.isBlank() ? "uploaded-knowledge" : filename.trim();
        // source 会进入数据库和后台列表，去掉路径分隔符避免显示本地路径。
        source = source.replaceAll("[\\\\/]+", "-");
        return source.length() > 180 ? source.substring(source.length() - 180) : source;
    }
}
