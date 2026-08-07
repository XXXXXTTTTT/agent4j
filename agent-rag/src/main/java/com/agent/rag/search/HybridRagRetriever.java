package com.agent.rag.search;

import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;
import com.agent.rag.domain.RagHit;
import com.agent.rag.domain.RagQuery;
import com.agent.rag.embedding.EmbeddingModel;
import com.agent.rag.store.RagStore;
import com.agent.rag.store.RagStoreException;
import com.agent.rag.store.RetrievalRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 合并向量、BM25 和符号分的三层代码检索器。 */
public final class HybridRagRetriever implements RagRetriever {

    private static final int EMBEDDING_DIMENSIONS = ChildChunk.EMBEDDING_DIMENSIONS;
    private static final double VECTOR_WEIGHT = 0.55;
    private static final double BM25_WEIGHT = 0.30;
    private static final double SYMBOL_WEIGHT = 0.15;

    private final RagStore ragStore;
    private final EmbeddingModel embeddingModel;
    private final Bm25Scorer bm25Scorer;

    /** 创建检索器并校验注入模型维度。 */
    public HybridRagRetriever(RagStore ragStore, EmbeddingModel embeddingModel) {
        this.ragStore = Objects.requireNonNull(ragStore, "ragStore 不能为空");
        this.embeddingModel = Objects.requireNonNull(
                embeddingModel, "embeddingModel 不能为空");
        if (embeddingModel.dimensions() != EMBEDDING_DIMENSIONS) {
            throw new IllegalArgumentException("EmbeddingModel dimensions 必须为 8");
        }
        this.bm25Scorer = new Bm25Scorer();
    }

    /** 返回最多 limit 条稳定排序的混合命中。 */
    @Override
    public List<RagHit> search(RagQuery query) {
        Objects.requireNonNull(query, "query 不能为空");
        float[] queryEmbedding = query.queryEmbedding();
        if (queryEmbedding == null) {
            try {
                queryEmbedding = embeddingModel.embed(query.query());
            } catch (RuntimeException exception) {
                throw new RagStoreException("生成查询 embedding 失败", exception);
            }
        }
        validateEmbedding(queryEmbedding, "queryEmbedding");
        int retrievalLimit = Math.min(100, Math.max(query.limit(), query.limit() * 4));
        List<RetrievalRow> vectorRows = ragStore.findByVector(
                query.repositoryId(), queryEmbedding, retrievalLimit);
        List<RetrievalRow> lexicalRows = ragStore.findByLexical(
                query.repositoryId(), query.query(), retrievalLimit);
        Map<UUID, MergedRow> merged = new LinkedHashMap<>();
        Set<UUID> vectorIds = new HashSet<>();
        Set<UUID> lexicalIds = new HashSet<>();
        for (RetrievalRow row : vectorRows) {
            validateRepository(query.repositoryId(), row);
            vectorIds.add(row.childChunk().childId());
            mergeVectorRow(merged, row);
        }
        for (RetrievalRow row : lexicalRows) {
            validateRepository(query.repositoryId(), row);
            lexicalIds.add(row.childChunk().childId());
            mergeLexicalRow(merged, row);
        }
        if (merged.isEmpty()) {
            return List.of();
        }

        Map<UUID, Double> normalizedVector = normalize(
                merged, vectorIds, id -> merged.get(id).vectorScore());
        Map<UUID, Double> bm25Scores = bm25Scores(
                query, merged, lexicalIds);
        Map<UUID, Double> normalizedBm25 = normalize(
                merged, lexicalIds, bm25Scores::get);
        List<RagHit> hits = new ArrayList<>();
        for (MergedRow row : merged.values()) {
            double vectorScore = normalizedVector.getOrDefault(row.childChunk().childId(), 0.0);
            double bm25Score = normalizedBm25.getOrDefault(row.childChunk().childId(), 0.0);
            double symbolScore = symbolScore(query.query(), row.childChunk(), row.parentChunk());
            double finalScore = VECTOR_WEIGHT * vectorScore
                    + BM25_WEIGHT * bm25Score
                    + SYMBOL_WEIGHT * symbolScore;
            hits.add(new RagHit(
                    row.childChunk(), row.parentChunk(), vectorScore,
                    bm25Score, symbolScore, finalScore));
        }
        return hits.stream()
                .sorted(Comparator.comparingDouble(RagHit::finalScore).reversed()
                        .thenComparing(hit -> hit.childChunk().path())
                        .thenComparingInt(hit -> hit.childChunk().ordinal())
                        .thenComparing(hit -> hit.childChunk().childId()))
                .limit(query.limit())
                .toList();
    }

    private void mergeVectorRow(Map<UUID, MergedRow> merged, RetrievalRow row) {
        UUID childId = row.childChunk().childId();
        MergedRow existing = merged.get(childId);
        if (existing == null) {
            merged.put(childId, new MergedRow(
                    row.childChunk(), row.parentChunk(), row.retrievalScore()));
        } else {
            ensureSameRow(existing, row);
            merged.put(childId, existing.withVectorScore(row.retrievalScore()));
        }
    }

    private void mergeLexicalRow(Map<UUID, MergedRow> merged, RetrievalRow row) {
        UUID childId = row.childChunk().childId();
        MergedRow existing = merged.get(childId);
        if (existing == null) {
            merged.put(childId, new MergedRow(row.childChunk(), row.parentChunk(), null));
        } else {
            ensureSameRow(existing, row);
        }
    }

    private void ensureSameRow(MergedRow existing, RetrievalRow row) {
        if (!existing.childChunk().equals(row.childChunk())
                || !existing.parentChunk().equals(row.parentChunk())) {
            throw new RagStoreException("同一 childId 的召回行内容不一致", null);
        }
    }

    private Map<UUID, Double> bm25Scores(
            RagQuery query,
            Map<UUID, MergedRow> merged,
            Set<UUID> lexicalIds) {
        if (lexicalIds.isEmpty()) {
            return Map.of();
        }
        List<String> terms = Bm25Scorer.tokenize(query.query()).stream().distinct().toList();
        long corpusSize = ragStore.countChildren(query.repositoryId());
        double averageLength = ragStore.averageDocumentLength(query.repositoryId());
        Map<String, Long> frequencies = ragStore.documentFrequencies(
                query.repositoryId(), terms);
        Map<UUID, Double> scores = new HashMap<>();
        for (UUID id : lexicalIds) {
            MergedRow row = merged.get(id);
            scores.put(id, bm25Scorer.score(
                    query.query(), row.childChunk().content(), corpusSize,
                    averageLength, frequencies));
        }
        return scores;
    }

    private Map<UUID, Double> normalize(
            Map<UUID, MergedRow> merged,
            Set<UUID> ids,
            java.util.function.Function<UUID, Double> scoreFunction) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        Map<UUID, Double> raw = new HashMap<>();
        for (UUID id : ids) {
            Double value = scoreFunction.apply(id);
            double score = value == null ? 0 : value;
            raw.put(id, score);
            minimum = Math.min(minimum, score);
            maximum = Math.max(maximum, score);
        }
        Map<UUID, Double> normalized = new HashMap<>();
        for (UUID id : merged.keySet()) {
            if (!raw.containsKey(id) || maximum == minimum) {
                normalized.put(id, 0.0);
            } else {
                normalized.put(id, (raw.get(id) - minimum) / (maximum - minimum));
            }
        }
        return normalized;
    }

    private double symbolScore(String query, ChildChunk child, ParentChunk parent) {
        if (child.symbol() != null && query.contains(child.symbol())) {
            return 1;
        }
        if (parent.symbol() != null && query.contains(parent.symbol())) {
            return 1;
        }
        for (String segment : child.path().split("/")) {
            if (!segment.isEmpty() && query.contains(segment)) {
                return 1;
            }
        }
        return 0;
    }

    private void validateRepository(String repositoryId, RetrievalRow row) {
        if (!repositoryId.equals(row.childChunk().repositoryId())
                || !repositoryId.equals(row.parentChunk().repositoryId())) {
            throw new RagStoreException("召回行 repositoryId 不一致", null);
        }
    }

    private void validateEmbedding(float[] embedding, String field) {
        if (embedding == null || embedding.length != EMBEDDING_DIMENSIONS) {
            throw new IllegalArgumentException(field + " 维度必须为 8");
        }
    }

    private record MergedRow(
            ChildChunk childChunk,
            ParentChunk parentChunk,
            Double vectorScore) {

        private MergedRow withVectorScore(double score) {
            return new MergedRow(childChunk, parentChunk, score);
        }
    }
}
