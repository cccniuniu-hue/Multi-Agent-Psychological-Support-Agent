package com.mindbridge.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.KnowledgeChunk;
import com.mindbridge.agent.repository.KnowledgeChunkRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class KnowledgeServiceEmbeddingTests {

    private static final String MODEL = "text-embedding-test";
    private static final List<Double> EMBEDDING = List.of(0.1, 0.2, 0.3);

    private KnowledgeChunkRepository repository;
    private ChromaGateway chromaGateway;
    private EmbeddingClient embeddingClient;
    private KnowledgeReranker reranker;
    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        repository = mock(KnowledgeChunkRepository.class);
        chromaGateway = mock(ChromaGateway.class);
        embeddingClient = mock(EmbeddingClient.class);
        reranker = mock(KnowledgeReranker.class);
        when(embeddingClient.modelName()).thenReturn(MODEL);
        service = new KnowledgeService(
                repository,
                new MindBridgeProperties(),
                chromaGateway,
                embeddingClient,
                reranker,
                new ObjectMapper());
    }

    @Test
    void storesEmbeddingVersionAndPassesTheSameVectorToChroma() {
        when(embeddingClient.embed("支持性倾听")).thenReturn(EMBEDDING);
        when(repository.save(org.mockito.ArgumentMatchers.any(KnowledgeChunk.class)))
                .thenAnswer(invocation -> {
                    KnowledgeChunk chunk = invocation.getArgument(0);
                    ReflectionTestUtils.setField(chunk, "id", 42L);
                    return chunk;
                });

        assertThat(service.ingest("guide.md", "支持性倾听")).isEqualTo(1);

        ArgumentCaptor<KnowledgeChunk> chunkCaptor = ArgumentCaptor.forClass(KnowledgeChunk.class);
        verify(repository).save(chunkCaptor.capture());
        KnowledgeChunk saved = chunkCaptor.getValue();
        assertThat(saved.getEmbeddingJson()).isEqualTo("[0.1,0.2,0.3]");
        assertThat(saved.getEmbeddingModel()).isEqualTo(MODEL);
        assertThat(saved.getEmbeddingDimensions()).isEqualTo(3);
        verify(chromaGateway).mirror(saved, EMBEDDING);
        verify(embeddingClient, times(1)).embed("支持性倾听");
    }

    @Test
    void storesSectionAndBlockTypeFromParsedMarkdown() {
        MarkdownDocument.Heading guide = new MarkdownDocument.Heading(1, "Guide");
        MarkdownDocument.Heading sleep = new MarkdownDocument.Heading(2, "Sleep");
        MarkdownDocument markdown = new MarkdownDocument(
                List.of(guide, sleep),
                List.of(new MarkdownDocument.Block(
                        MarkdownDocument.BlockType.PARAGRAPH,
                        "支持性倾听",
                        List.of(guide, sleep))),
                List.of());
        DocumentParseResult parsed = new DocumentParseResult(
                "guide.md",
                "guide",
                "支持性倾听",
                List.of(),
                List.of(),
                markdown,
                DocumentParseResult.Status.SUCCESS,
                null);
        when(embeddingClient.embed("支持性倾听")).thenReturn(EMBEDDING);
        when(repository.save(org.mockito.ArgumentMatchers.any(KnowledgeChunk.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.ingest(parsed)).isEqualTo(1);

        ArgumentCaptor<KnowledgeChunk> chunkCaptor = ArgumentCaptor.forClass(KnowledgeChunk.class);
        verify(repository).save(chunkCaptor.capture());
        assertThat(chunkCaptor.getValue().getSectionPath()).isEqualTo("Guide / Sleep");
        assertThat(chunkCaptor.getValue().getContentType()).isEqualTo("PARAGRAPH");
    }

    @Test
    void storesImageDescriptionAsAnIndependentRetrievableChunkWithOriginalPath() {
        String body = "# Report\n![Chart](images/first/chart.png) 图片描述：Score rose";
        ImageDescription description = new ImageDescription("bar_chart", "Score rose",
                List.of("Week"), List.of("later week is higher"), List.of("rarepeak"));
        var described = new DocumentParseResult.ImageReference(
                "images/first/chart.png", 1, "cG5n", description);
        var failed = new DocumentParseResult.ImageReference("images/other/chart.png", 2, "cG5n");
        var parsed = new DocumentParseResult("report.pdf", "Report", body,
                List.of(new DocumentParseResult.Page(1, body)), List.of(described, failed),
                new MarkdownDocumentParser().parse(body), DocumentParseResult.Status.SUCCESS, null);
        List<KnowledgeChunk> saved = new ArrayList<>();
        AtomicLong ids = new AtomicLong();
        when(repository.save(org.mockito.ArgumentMatchers.any(KnowledgeChunk.class)))
                .thenAnswer(invocation -> {
                    KnowledgeChunk chunk = invocation.getArgument(0);
                    ReflectionTestUtils.setField(chunk, "id", ids.incrementAndGet());
                    saved.add(chunk);
                    return chunk;
                });

        assertThat(service.ingest(parsed)).isEqualTo(2);
        assertThat(saved).filteredOn(chunk -> "IMAGE".equals(chunk.getContentType()))
                .singleElement().satisfies(image -> {
                    assertThat(image.getSource()).isEqualTo("report.pdf");
                    assertThat(image.getSourceIndex()).isEqualTo(1);
                    assertThat(image.getSectionPath()).isEqualTo("Report");
                    assertThat(image.getImagePath()).isEqualTo(described.path());
                    assertThat(image.getContent()).contains(described.path(), "Score rose", "Week", "rarepeak")
                            .doesNotContain("cG5n", failed.path());
                });
        when(repository.findAll()).thenReturn(saved);
        when(reranker.rerank(eq("rarepeak"), anyList(), eq(1)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        assertThat(service.retrieve("rarepeak", 1)).singleElement().satisfies(result -> {
            assertThat(result.source()).isEqualTo("report.pdf");
            assertThat(result.content()).contains(described.path(), "rarepeak");
        });
    }

    @Test
    void reusesOneQueryEmbeddingForChromaAndLocalFallback() {
        String query = "如何提供支持";
        KnowledgeChunk chunk = new KnowledgeChunk();
        ReflectionTestUtils.setField(chunk, "id", 42L);
        chunk.setSource("guide.md");
        chunk.setSourceIndex(0);
        chunk.setContent("支持性倾听");
        chunk.setEmbeddingJson("[0.1,0.2,0.3]");
        chunk.setEmbeddingModel(MODEL);
        chunk.setEmbeddingDimensions(3);
        KnowledgeChunk staleChunk = new KnowledgeChunk();
        ReflectionTestUtils.setField(staleChunk, "id", 43L);
        staleChunk.setSource("old-guide.md");
        staleChunk.setSourceIndex(0);
        staleChunk.setContent("旧模型生成的向量");
        staleChunk.setEmbeddingJson("[0.1,0.2,0.3]");
        staleChunk.setEmbeddingModel("old-model");
        staleChunk.setEmbeddingDimensions(3);
        when(embeddingClient.embed(query)).thenReturn(EMBEDDING);
        when(repository.findAll()).thenReturn(List.of(staleChunk, chunk));
        when(repository.findById(42L)).thenReturn(Optional.empty());
        when(chromaGateway.query(EMBEDDING, MODEL, 20)).thenReturn(List.of());
        when(reranker.rerank(eq(query), anyList(), eq(1)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        assertThat(service.retrieve(query, 1)).singleElement()
                .satisfies(result -> assertThat(result.chunkId()).isEqualTo(42L));

        verify(embeddingClient, times(1)).embed(query);
        verify(chromaGateway).query(EMBEDDING, MODEL, 20);
    }
}
