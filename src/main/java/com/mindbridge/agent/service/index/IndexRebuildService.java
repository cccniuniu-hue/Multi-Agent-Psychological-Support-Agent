package com.mindbridge.agent.service.index;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.KnowledgeChunk;
import com.mindbridge.agent.repository.KnowledgeChunkRepository;
import com.mindbridge.agent.repository.UserMemoryItemRepository;
import com.mindbridge.agent.service.knowledge.ChromaGateway;
import com.mindbridge.agent.service.memory.UserMemoryChromaGateway;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 从关系库主数据重放 Chroma 索引；使用稳定 ID upsert，失败后可以直接重试。 */
@Service
public class IndexRebuildService {

    private final KnowledgeChunkRepository knowledgeRepository;
    private final UserMemoryItemRepository memoryRepository;
    private final ChromaGateway knowledgeGateway;
    private final UserMemoryChromaGateway memoryGateway;
    private final MindBridgeProperties properties;
    private final ObjectMapper objectMapper;

    public IndexRebuildService(
            KnowledgeChunkRepository knowledgeRepository,
            UserMemoryItemRepository memoryRepository,
            ChromaGateway knowledgeGateway,
            UserMemoryChromaGateway memoryGateway,
            MindBridgeProperties properties,
            ObjectMapper objectMapper
    ) {
        this.knowledgeRepository = knowledgeRepository;
        this.memoryRepository = memoryRepository;
        this.knowledgeGateway = knowledgeGateway;
        this.memoryGateway = memoryGateway;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Result rebuild(String target) {
        if (!"knowledge".equals(target) && !"memory".equals(target) && !"all".equals(target)) {
            throw new IllegalArgumentException("--rebuild-index must be knowledge, memory, or all");
        }
        boolean includeKnowledge = !"memory".equals(target);
        boolean includeMemory = !"knowledge".equals(target);
        if (includeKnowledge && !properties.getKnowledge().isUseChroma()) {
            throw new IllegalStateException("USE_CHROMA must be true to rebuild the knowledge index");
        }
        boolean memoryEnabled = properties.getMemory().isUseChroma()
                && properties.getMemory().getEmbedding().isEnabled()
                && properties.getMemory().getEmbedding().getApiKey() != null
                && !properties.getMemory().getEmbedding().getApiKey().isBlank();
        if ("memory".equals(target) && !memoryEnabled) {
            throw new IllegalStateException(
                    "MEMORY_USE_CHROMA, MEMORY_EMBEDDING_ENABLED and MEMORY_EMBEDDING_API_KEY are required");
        }

        int knowledgeIndexed = 0;
        int knowledgeSkipped = 0;
        if (includeKnowledge) {
            for (KnowledgeChunk chunk : knowledgeRepository.findAll()) {
                List<Double> embedding = storedEmbedding(chunk);
                if (embedding.isEmpty()) {
                    knowledgeSkipped++;
                    continue;
                }
                if (!knowledgeGateway.mirror(chunk, embedding)) {
                    throw new IllegalStateException("Knowledge index upsert failed for chunk " + chunk.getId());
                }
                knowledgeIndexed++;
            }
        }

        int memoryIndexed = 0;
        if (includeMemory && memoryEnabled) {
            for (var item : memoryRepository.findAll()) {
                if (!memoryGateway.mirror(item)) {
                    throw new IllegalStateException("Memory index upsert failed for item " + item.getId());
                }
                memoryIndexed++;
            }
        }
        return new Result(knowledgeIndexed, knowledgeSkipped, memoryIndexed, includeMemory && !memoryEnabled);
    }

    private List<Double> storedEmbedding(KnowledgeChunk chunk) {
        if (chunk.getId() == null || chunk.getEmbeddingModel() == null
                || chunk.getEmbeddingModel().isBlank() || chunk.getEmbeddingDimensions() == null
                || chunk.getEmbeddingDimensions() < 1 || chunk.getEmbeddingJson() == null) {
            return List.of();
        }
        try {
            List<Double> embedding = objectMapper.readValue(chunk.getEmbeddingJson(), new TypeReference<>() {
            });
            return embedding != null && embedding.size() == chunk.getEmbeddingDimensions()
                    ? embedding : List.of();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public record Result(int knowledgeIndexed, int knowledgeSkipped, int memoryIndexed, boolean memoryDisabled) {
    }
}
