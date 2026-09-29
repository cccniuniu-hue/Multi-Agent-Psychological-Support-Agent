package com.mindbridge.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnowledgeFileServiceTests {

    private final KnowledgeService knowledgeService = mock(KnowledgeService.class);
    private final MarkerDocumentClient markerClient = mock(MarkerDocumentClient.class);
    private final ImageDescriptionClient imageClient = mock(ImageDescriptionClient.class);
    private final KnowledgeFileService fileService = new KnowledgeFileService(
            knowledgeService, markerClient, new MarkdownDocumentParser(), imageClient);

    @BeforeEach
    void useLocalParserWhenMarkerHasNoResult() {
        when(markerClient.parse(anyString(), any())).thenReturn(Optional.empty());
    }

    @Test
    void usesMarkerResultForDocxUpload() {
        byte[] bytes = "docx bytes".getBytes(StandardCharsets.UTF_8);
        DocumentParseResult markerResult = new DocumentParseResult(
                "folder-guide.docx",
                "folder-guide",
                "# Guide\nUseful advice",
                List.of(new DocumentParseResult.Page(1, "# Guide\nUseful advice")),
                List.of(new DocumentParseResult.ImageReference("images/chart.png", null)),
                DocumentParseResult.Status.SUCCESS,
                null);
        when(markerClient.parse("folder-guide.docx", bytes)).thenReturn(Optional.of(markerResult));

        DocumentParseResult parsed = fileService.parse("folder/guide.docx", bytes);
        assertThat(parsed.markdown().headings())
                .containsExactly(new MarkdownDocument.Heading(1, "Guide"));
        fileService.ingest("folder/guide.docx", bytes);

        verify(knowledgeService).ingest(argThat(ingested ->
                ingested.source().equals("folder-guide.docx") && !ingested.markdown().blocks().isEmpty()));
    }

    @Test
    void associatesDescriptionsWithOriginalPathsAndContinuesAfterAnImageFails() throws Exception {
        byte[] bytes = "docx bytes".getBytes(StandardCharsets.UTF_8);
        String body = "# Guide\n![First](images/first/chart.png)\n![Second](images/second/chart.png)";
        String imageContent = "cG5nLWRhdGE=";
        var failedImage = new DocumentParseResult.ImageReference("images/first/chart.png", 1, imageContent);
        var successfulImage = new DocumentParseResult.ImageReference("images/second/chart.png", 2, imageContent);
        var missingImage = new DocumentParseResult.ImageReference("images/missing.png", null);
        var description = new ImageDescription("bar_chart", "Peak score: 8", List.of("Score"), List.of(), List.of("8"));
        var markerResult = new DocumentParseResult("guide.docx", "guide", body,
                List.of(new DocumentParseResult.Page(1, body)),
                List.of(failedImage, successfulImage, missingImage), DocumentParseResult.Status.SUCCESS, null);
        when(markerClient.parse("guide.docx", bytes)).thenReturn(Optional.of(markerResult));
        when(imageClient.describe(failedImage.path(), imageContent)).thenReturn(Optional.empty());
        when(imageClient.describe(successfulImage.path(), imageContent)).thenReturn(Optional.of(description));

        DocumentParseResult result = fileService.parse("guide.docx", bytes);

        assertThat(result.body()).isEqualTo(body);
        assertThat(result.pages()).isEqualTo(markerResult.pages());
        assertThat(result.images()).containsExactly(failedImage, successfulImage.withDescription(description), missingImage);
        assertThat(result.markdown().images()).extracting(MarkdownDocument.ImageReference::destination)
                .containsExactly(failedImage.path(), successfulImage.path());
        assertThat(new ObjectMapper().writeValueAsString(result.images()))
                .contains("images/second/chart.png", "Peak score: 8").doesNotContain(imageContent, "base64Content");

        fileService.ingest("guide.docx", bytes);
        verify(knowledgeService).ingest(argThat(ingested ->
                description.equals(ingested.images().get(1).description()) && ingested.body().equals(body)));
    }

    @Test
    void keepsPdfPageNumbersAndDocumentMetadata() throws Exception {
        DocumentParseResult result = fileService.parse("guide.pdf", twoPagePdf());

        assertThat(result.status()).isEqualTo(DocumentParseResult.Status.SUCCESS);
        assertThat(result.source()).isEqualTo("guide.pdf");
        assertThat(result.title()).isEqualTo("guide");
        assertThat(result.body()).contains("First page", "Second page");
        assertThat(result.pages()).extracting(DocumentParseResult.Page::number).containsExactly(1, 2);
        assertThat(result.pages().get(0).body()).contains("First page");
        assertThat(result.pages().get(1).body()).contains("Second page");
        assertThat(result.images()).isEmpty();
    }

    @Test
    void textUploadUsesParsedBodyAndReportsEmptyText() {
        DocumentParseResult result = fileService.parse("folder/guide.md", "# Guide\nUseful advice".getBytes(StandardCharsets.UTF_8));

        assertThat(result.status()).isEqualTo(DocumentParseResult.Status.SUCCESS);
        assertThat(result.source()).isEqualTo("folder-guide.md");
        assertThat(result.title()).isEqualTo("folder-guide");
        assertThat(result.pages()).extracting(DocumentParseResult.Page::number).containsExactly(1);
        assertThat(result.images()).isEmpty();
        assertThat(result.markdown().headings())
                .containsExactly(new MarkdownDocument.Heading(1, "Guide"));
        assertThat(result.markdown().blocks()).extracting(MarkdownDocument.Block::text)
                .containsExactly("Useful advice");

        fileService.ingest("folder/guide.md", "# Guide\nUseful advice".getBytes(StandardCharsets.UTF_8));
        verify(knowledgeService).ingest(argThat(ingested ->
                ingested.source().equals("folder-guide.md") && !ingested.markdown().blocks().isEmpty()));

        DocumentParseResult empty = fileService.parse("empty.txt", " \n".getBytes(StandardCharsets.UTF_8));
        assertThat(empty.status()).isEqualTo(DocumentParseResult.Status.EMPTY);
        assertThatThrownBy(() -> fileService.ingest("empty.txt", " \n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有从文件中解析出可用文本");
    }

    @Test
    void invalidPdfReportsFailureWithoutIngesting() {
        byte[] invalidPdf = "not a pdf".getBytes(StandardCharsets.UTF_8);

        DocumentParseResult result = fileService.parse("broken.pdf", invalidPdf);
        assertThat(result.status()).isEqualTo(DocumentParseResult.Status.FAILED);
        assertThat(result.error()).contains("PDF 文本解析失败");
        assertThat(result.pages()).isEmpty();
        assertThatThrownBy(() -> fileService.ingest("broken.pdf", invalidPdf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PDF 文本解析失败");
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void unsupportedFormatKeepsItsValidationError() {
        assertThatThrownBy(() -> fileService.parse("guide.docx", "content".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持 PDF、Markdown 和 txt 文件");
    }

    private byte[] twoPagePdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            addPage(document, "First page");
            addPage(document, "Second page");
            document.save(output);
            return output.toByteArray();
        }
    }

    private void addPage(PDDocument document, String text) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            stream.beginText();
            stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            stream.newLineAtOffset(50, 700);
            stream.showText(text);
            stream.endText();
        }
    }
}
