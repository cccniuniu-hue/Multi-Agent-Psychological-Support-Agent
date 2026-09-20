package com.mindbridge.agent.service.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.KnowledgeChunk;
import com.mindbridge.agent.domain.UserMemoryItem;
import com.mindbridge.agent.repository.KnowledgeChunkRepository;
import com.mindbridge.agent.repository.UserMemoryItemRepository;
import com.mindbridge.agent.service.knowledge.ChromaGateway;
import com.mindbridge.agent.service.memory.UserMemoryChromaGateway;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class IndexRebuildServiceTests {

    private final KnowledgeChunkRepository knowledgeRepository = mock(KnowledgeChunkRepository.class);
    private final UserMemoryItemRepository memoryRepository = mock(UserMemoryItemRepository.class);
    private final ChromaGateway knowledgeGateway = mock(ChromaGateway.class);
    private final UserMemoryChromaGateway memoryGateway = mock(UserMemoryChromaGateway.class);
    private final MindBridgeProperties properties = new MindBridgeProperties();
    private IndexRebuildService service;

    @BeforeEach
    void setUp() {
        properties.getKnowledge().setUseChroma(true);
        properties.getMemory().setUseChroma(true);
        properties.getMemory().getEmbedding().setEnabled(true);
        properties.getMemory().getEmbedding().setApiKey("test-key");
        service = new IndexRebuildService(
                knowledgeRepository, memoryRepository, knowledgeGateway, memoryGateway,
                properties, new ObjectMapper());
    }

    @Test
    void replaysStoredKnowledgeVectorsWithoutChangingDatabaseRows() {
        KnowledgeChunk indexed = chunk(1L, "[0.1,0.2,0.3]", 3);
        KnowledgeChunk unindexed = chunk(2L, null, null);
        when(knowledgeRepository.findAll()).thenReturn(List.of(indexed, unindexed));
        when(knowledgeGateway.mirror(indexed, List.of(0.1, 0.2, 0.3))).thenReturn(true);

        IndexRebuildService.Result result = service.rebuild("knowledge");

        assertThat(result.knowledgeIndexed()).isEqualTo(1);
        assertThat(result.knowledgeSkipped()).isEqualTo(1);
        verify(knowledgeRepository, never()).save(indexed);
        verify(knowledgeRepository, never()).delete(indexed);
        verify(memoryRepository, never()).findAll();
    }

    @Test
    void failedUpsertStopsRebuildSoItCanBeRetried() {
        KnowledgeChunk indexed = chunk(1L, "[0.1,0.2,0.3]", 3);
        when(knowledgeRepository.findAll()).thenReturn(List.of(indexed));
        when(knowledgeGateway.mirror(indexed, List.of(0.1, 0.2, 0.3)))
                .thenReturn(false, true);

        assertThatThrownBy(() -> service.rebuild("knowledge"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1");
        assertThat(service.rebuild("knowledge").knowledgeIndexed()).isEqualTo(1);
        verify(knowledgeGateway, times(2)).mirror(indexed, List.of(0.1, 0.2, 0.3));
    }

    @Test
    void replaysMemoryOnlyWithDedicatedEmbeddingPermission() {
        UserMemoryItem item = new UserMemoryItem();
        ReflectionTestUtils.setField(item, "id", 7L);
        when(memoryRepository.findAll()).thenReturn(List.of(item));
        when(memoryGateway.mirror(item)).thenReturn(true);

        assertThat(service.rebuild("memory").memoryIndexed()).isEqualTo(1);
        properties.getMemory().getEmbedding().setEnabled(false);
        assertThatThrownBy(() -> service.rebuild("memory"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MEMORY_EMBEDDING_ENABLED");
        verify(memoryGateway, times(1)).mirror(item);
    }

    private KnowledgeChunk chunk(Long id, String vector, Integer dimensions) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        ReflectionTestUtils.setField(chunk, "id", id);
        chunk.setSource("guide.md");
        chunk.setSourceIndex(id.intValue());
        chunk.setContent("支持性倾听");
        chunk.setEmbeddingJson(vector);
        chunk.setEmbeddingModel(vector == null ? null : "text-embedding-3-small");
        chunk.setEmbeddingDimensions(dimensions);
        return chunk;
    }
}
