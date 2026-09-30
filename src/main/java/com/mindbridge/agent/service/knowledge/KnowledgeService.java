package com.mindbridge.agent.service.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.KnowledgeChunk;
import com.mindbridge.agent.repository.KnowledgeChunkRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
/**
 * RAG 知识库核心服务。
 *
 * <p>负责知识入库、向量写入、检索排序和命中上下文扩展，是咨询/风险回答的知识来源。</p>
 */
public class KnowledgeService {

    private static final double VECTOR_WEIGHT = 0.65;
    private static final double BM25_WEIGHT = 0.35;

    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final MindBridgeProperties properties;
    private final ChromaGateway chromaGateway;
    private final EmbeddingClient embeddingClient;
    private final KnowledgeReranker knowledgeReranker;
    private final ObjectMapper objectMapper;
    private final KnowledgeChunker chunker = new KnowledgeChunker();
    private final Bm25Scorer bm25Scorer = new Bm25Scorer();

    public KnowledgeService(
            KnowledgeChunkRepository knowledgeChunkRepository,
            MindBridgeProperties properties,
            ChromaGateway chromaGateway,
            EmbeddingClient embeddingClient,
            KnowledgeReranker knowledgeReranker,
            ObjectMapper objectMapper
    ) {
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.properties = properties;
        this.chromaGateway = chromaGateway;
        this.embeddingClient = embeddingClient;
        this.knowledgeReranker = knowledgeReranker;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int ingest(String source, String content) {
        return ingest(source, textChunks(content));
    }

    private List<KnowledgeChunker.Chunk> textChunks(String content) {
        return chunker.chunk(
                content,
                properties.getKnowledge().getChunkSize(),
                properties.getKnowledge().getChunkOverlap()).stream()
                .map(text -> new KnowledgeChunker.Chunk(
                        text, "", MarkdownDocument.BlockType.PARAGRAPH, 0))
                .toList();
    }

    @Transactional
    public int ingest(DocumentParseResult parsed) {
        List<KnowledgeChunker.Chunk> chunks = new ArrayList<>(parsed.markdown().blocks().isEmpty()
                ? textChunks(parsed.body())
                : chunker.chunk(parsed.markdown(), properties.getKnowledge().getChunkSize()));
        chunks.addAll(chunker.chunkImages(parsed.images(), parsed.markdown(),
                properties.getKnowledge().getChunkSize()));
        return ingest(parsed.source(), chunks);
    }

    private int ingest(String source, List<KnowledgeChunker.Chunk> chunks) {
        // 同一 source 重新上传时先清旧数据，保证后台知识库展示的是最新文件内容。
        knowledgeChunkRepository.deleteBySource(source);
        chromaGateway.deleteSource(source);
        for (int index = 0; index < chunks.size(); index++) {
            KnowledgeChunker.Chunk parsedChunk = chunks.get(index);
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setSource(source);
            chunk.setSourceIndex(index);
            chunk.setContent(parsedChunk.content());
            chunk.setSectionPath(parsedChunk.sectionPath());
            chunk.setContentType(parsedChunk.type().name());
            chunk.setImagePath(parsedChunk.imagePath());
            // 有 embedding 配置时写入向量；没有配置时保持为空，检索会自动走本地兜底。
            List<Double> embedding = safeEmbedding(parsedChunk.content());
            chunk.setEmbeddingJson(serializeEmbedding(embedding));
            String embeddingModel = embedding.isEmpty() ? "" : embeddingClient.modelName();
            if (embeddingModel != null && !embeddingModel.isBlank()) {
                chunk.setEmbeddingModel(embeddingModel);
                chunk.setEmbeddingDimensions(embedding.size());
            }
            KnowledgeChunk saved = knowledgeChunkRepository.save(chunk);
            chromaGateway.mirror(saved, embedding);
        }
        return chunks.size();
    }

    @Transactional(readOnly = true)
    public List<SearchResult> retrieve(String query, int topK) {
        if (topK <= 0 || query == null || query.isBlank()) {
            return List.of();
        }
        int candidateLimit = Math.max(Math.max(topK * 4, 20), properties.getKnowledge().getRerankerCandidateLimit());
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findAll();
        List<SearchResult> vectorResults = retrieveByVector(query, candidateLimit, chunks);
        List<SearchResult> bm25Results = bm25Scorer.rank(query, chunks, candidateLimit);
        List<SearchResult> hybridCandidates = mergeHybridResults(vectorResults, bm25Results, candidateLimit);
        List<SearchResult> reranked = knowledgeReranker.rerank(query, hybridCandidates, topK);
        return expandBestContext(reranked, topK);
    }

    private List<SearchResult> retrieveByVector(String query, int limit, List<KnowledgeChunk> chunks) {
        List<Double> queryEmbedding = safeEmbedding(query);
        if (queryEmbedding.isEmpty()) {
            return List.of();
        }
        String embeddingModel = embeddingClient.modelName();
        if (embeddingModel == null || embeddingModel.isBlank()) {
            return List.of();
        }
        List<SearchResult> chromaResults = chromaGateway.query(queryEmbedding, embeddingModel, limit);
        if (!chromaResults.isEmpty()) {
            return chromaResults;
        }
        return retrieveByEmbedding(queryEmbedding, embeddingModel, limit, chunks);
    }

    private List<SearchResult> retrieveByEmbedding(
            List<Double> queryEmbedding,
            String embeddingModel,
            int topK,
            List<KnowledgeChunk> chunks
    ) {
        return chunks.stream()
                .filter(chunk -> embeddingModel.equals(chunk.getEmbeddingModel()))
                .filter(chunk -> Integer.valueOf(queryEmbedding.size()).equals(chunk.getEmbeddingDimensions()))
                .map(chunk -> new SearchResult(
                        chunk.getId(),
                        chunk.getSource(),
                        chunk.getContent(),
                        cosine(queryEmbedding, parseEmbedding(chunk.getEmbeddingJson()))))
                .filter(result -> result.score() > 0.0)
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(topK)
                .toList();
    }

    private List<SearchResult> mergeHybridResults(
            List<SearchResult> vectorResults,
            List<SearchResult> bm25Results,
            int topK
    ) {
        Map<String, HybridCandidate> candidates = new LinkedHashMap<>();
        double maxVectorScore = maxScore(vectorResults);
        double maxBm25Score = maxScore(bm25Results);
        mergeRoute(candidates, vectorResults, maxVectorScore, true);
        mergeRoute(candidates, bm25Results, maxBm25Score, false);
        return candidates.values().stream()
                .map(HybridCandidate::toSearchResult)
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(topK)
                .toList();
    }

    private void mergeRoute(
            Map<String, HybridCandidate> candidates,
            List<SearchResult> results,
            double maxScore,
            boolean vectorRoute
    ) {
        if (results.isEmpty() || maxScore <= 0.0) {
            return;
        }
        for (int rank = 0; rank < results.size(); rank++) {
            SearchResult result = results.get(rank);
            double normalizedScore = Math.max(0.0, result.score()) / maxScore;
            double rankBoost = 1.0 / (rank + 1.0);
            double routeScore = normalizedScore * 0.85 + rankBoost * 0.15;
            HybridCandidate candidate = candidates.computeIfAbsent(candidateKey(result), key -> new HybridCandidate(result));
            if (vectorRoute) {
                candidate.vectorScore = Math.max(candidate.vectorScore, routeScore);
            } else {
                candidate.bm25Score = Math.max(candidate.bm25Score, routeScore);
            }
        }
    }

    private double maxScore(List<SearchResult> results) {
        return results.stream()
                .mapToDouble(SearchResult::score)
                .filter(score -> score > 0.0)
                .max()
                .orElse(0.0);
    }

    private String candidateKey(SearchResult result) {
        if (result.chunkId() != null) {
            return "id:" + result.chunkId();
        }
        return "content:" + result.source() + ":" + result.content();
    }

    private List<SearchResult> expandBestContext(List<SearchResult> ranked, int topK) {
        if (ranked.isEmpty()) {
            return ranked;
        }
        // 命中片段前后各补一段，减少切块边界导致的上下文断裂。
        SearchResult best = ranked.get(0);
        SearchResult expanded = expand(best);
        List<SearchResult> results = new ArrayList<>();
        results.add(expanded);
        ranked.stream()
                .skip(1)
                .filter(result -> !sameChunk(result, expanded))
                .limit(Math.max(0, topK - 1))
                .forEach(results::add);
        return results;
    }

    private SearchResult expand(SearchResult result) {
        if (result.chunkId() == null) {
            return result;
        }
        return knowledgeChunkRepository.findById(result.chunkId())
                .map(chunk -> {
                    List<KnowledgeChunk> neighbors = knowledgeChunkRepository
                            .findBySourceAndSourceIndexBetweenOrderBySourceIndexAsc(
                                    chunk.getSource(),
                                    Math.max(0, chunk.getSourceIndex() - 1),
                                    chunk.getSourceIndex() + 1);
                    String expandedContent = String.join("\n\n", neighbors.stream()
                            .map(KnowledgeChunk::getContent)
                            .toList());
                    return new SearchResult(chunk.getId(), chunk.getSource(), expandedContent, result.score());
                })
                .orElse(result);
    }

    private boolean sameChunk(SearchResult result, SearchResult expanded) {
        return result.chunkId() != null && result.chunkId().equals(expanded.chunkId());
    }

    private List<Double> safeEmbedding(String text) {
        try {
            return embeddingClient.embed(text);
        } catch (Exception ignored) {
            // embedding 失败不影响知识库可用性，后续会回退到本地检索。
            return List.of();
        }
    }

    private String serializeEmbedding(List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(embedding);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Double> parseEmbedding(String embeddingJson) {
        if (embeddingJson == null || embeddingJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(embeddingJson, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.isEmpty() || right.isEmpty() || left.size() != right.size()) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.size(); i++) {
            double a = left.get(i);
            double b = right.get(i);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static class HybridCandidate {
        private final SearchResult result;
        private double vectorScore;
        private double bm25Score;

        private HybridCandidate(SearchResult result) {
            this.result = result;
        }

        private SearchResult toSearchResult() {
            double score = vectorScore * VECTOR_WEIGHT + bm25Score * BM25_WEIGHT;
            return new SearchResult(result.chunkId(), result.source(), result.content(), score);
        }
    }
}
