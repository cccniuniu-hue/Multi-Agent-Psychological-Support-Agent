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
    private MindBridgeProperties properties;
    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        repository = mock(KnowledgeChunkRepository.class);
        chromaGateway = mock(ChromaGateway.class);
        embeddingClient = mock(EmbeddingClient.class);
        reranker = mock(KnowledgeReranker.class);
        properties = new MindBridgeProperties();
        when(embeddingClient.modelName()).thenReturn(MODEL);
        service = new KnowledgeService(
                repository,
                properties,
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
        when(chromaGateway.query(EMBEDDING, MODEL, 50)).thenReturn(List.of());
        when(reranker.rerank(eq(query), anyList(), eq(1)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        assertThat(service.retrieve(query, 1)).singleElement()
                .satisfies(result -> assertThat(result.chunkId()).isEqualTo(42L));

        verify(embeddingClient, times(1)).embed(query);
        verify(chromaGateway).query(EMBEDDING, MODEL, 50);
    }

    @Test
    void usesLocalVectorWhenChromaQueryThrows() {
        KnowledgeChunk chunk = new KnowledgeChunk();
        ReflectionTestUtils.setField(chunk, "id", 42L);
        chunk.setSource("guide.md");
        chunk.setContent("support answer");
        chunk.setEmbeddingJson("[0.1,0.2,0.3]");
        chunk.setEmbeddingModel(MODEL);
        chunk.setEmbeddingDimensions(3);
        when(repository.findAll()).thenReturn(List.of(chunk));
        when(embeddingClient.embed("needle")).thenReturn(EMBEDDING);
        when(chromaGateway.query(EMBEDDING, MODEL, 50)).thenThrow(new IllegalStateException("Chroma unavailable"));
        when(reranker.rerank(eq("needle"), anyList(), eq(1)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        assertThat(service.retrieve("needle", 1)).singleElement()
                .satisfies(result -> assertThat(result.chunkId()).isEqualTo(42L));
    }

    @Test
    void usesBm25WhenChromaQueryThrowsAndNoLocalVectorExists() {
        KnowledgeChunk chunk = new KnowledgeChunk();
        ReflectionTestUtils.setField(chunk, "id", 43L);
        chunk.setSource("guide.md");
        chunk.setContent("needle answer");
        when(repository.findAll()).thenReturn(List.of(chunk));
        when(embeddingClient.embed("needle")).thenReturn(EMBEDDING);
        when(chromaGateway.query(EMBEDDING, MODEL, 50)).thenThrow(new IllegalStateException("Chroma unavailable"));
        when(reranker.rerank(eq("needle"), anyList(), eq(1)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        assertThat(service.retrieve("needle", 1)).singleElement()
                .satisfies(result -> assertThat(result.chunkId()).isEqualTo(43L));
    }

    @Test
    void appliesConfigurableCoarseRecallLimitToBothVectorAndBm25() {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            ReflectionTestUtils.setField(chunk, "id", (long) index + 1);
            chunk.setSource("guide.md");
            chunk.setContent("needle " + index);
            chunks.add(chunk);
        }
        when(repository.findAll()).thenReturn(chunks);
        when(embeddingClient.embed("needle")).thenReturn(EMBEDDING);
        when(reranker.rerank(eq("needle"), anyList(), eq(1)))
                .thenAnswer(invocation -> {
                    List<SearchResult> candidates = invocation.getArgument(1);
                    return candidates.stream().limit(1).toList();
                });

        assertThat(service.retrieve("needle", 1)).hasSize(1);
        verify(chromaGateway).query(EMBEDDING, MODEL, 50);
        verify(reranker).rerank(eq("needle"), org.mockito.ArgumentMatchers.argThat(
                candidates -> candidates.size() == 50), eq(1));

        properties.getKnowledge().setCoarseRecallLimit(30);
        assertThat(service.retrieve("needle", 1)).hasSize(1);
        verify(chromaGateway).query(EMBEDDING, MODEL, 30);
        verify(reranker).rerank(eq("needle"), org.mockito.ArgumentMatchers.argThat(
                candidates -> candidates.size() == 30), eq(1));
    }

    @Test
    void rrfPrefersAChunkFoundByBothRoutesWhileWeightedFusionRemainsAvailable() {
        KnowledgeChunk keywordChunk = new KnowledgeChunk();
        ReflectionTestUtils.setField(keywordChunk, "id", 2L);
        keywordChunk.setSource("guide.md");
        keywordChunk.setContent("needle answer");
        when(repository.findAll()).thenReturn(List.of(keywordChunk));
        when(embeddingClient.embed("needle")).thenReturn(EMBEDDING);
        when(chromaGateway.query(EMBEDDING, MODEL, 50)).thenReturn(List.of(
                new SearchResult(1L, "vector.md", "unrelated", 1.0),
                new SearchResult(2L, "guide.md", "needle answer", 0.0001)));
        when(reranker.rerank(eq("needle"), anyList(), eq(1)))
                .thenAnswer(invocation -> {
                    List<SearchResult> candidates = invocation.getArgument(1);
                    return candidates.stream().limit(1).toList();
                });

        assertThat(service.retrieve("needle", 1)).singleElement().satisfies(result -> {
            assertThat(result.chunkId()).isEqualTo(2L);
            assertThat(result.score()).isCloseTo(1.0 / 62 + 1.0 / 61,
                    org.assertj.core.data.Offset.offset(1e-9));
        });

        properties.getKnowledge().setFusionStrategy(MindBridgeProperties.Knowledge.FusionStrategy.WEIGHTED);
        assertThat(service.retrieve("needle", 1)).singleElement()
                .satisfies(result -> assertThat(result.chunkId()).isEqualTo(1L));
    }
}
