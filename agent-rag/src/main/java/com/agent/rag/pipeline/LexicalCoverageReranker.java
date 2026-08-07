package com.agent.rag.pipeline;

import com.agent.rag.search.Bm25Scorer;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 使用查询词覆盖率和融合分执行无外部调用的确定性精排。 */
public final class LexicalCoverageReranker implements RagReranker {

    private static final double COVERAGE_WEIGHT = 0.7;
    private static final double RETRIEVAL_WEIGHT = 0.3;

    /** 返回最多 limit 条确定性精排结果。 */
    @Override
    public List<RerankedHit> rerank(
            String query, List<FusedHit> hits, int limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        Objects.requireNonNull(hits, "hits 不能为空");
        if (hits.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("hits 不能包含 null");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        if (hits.isEmpty()) {
            return List.of();
        }

        Set<String> queryTokens = new LinkedHashSet<>(Bm25Scorer.tokenize(query));
        double minimum = hits.stream().mapToDouble(FusedHit::score).min().orElse(0);
        double maximum = hits.stream().mapToDouble(FusedHit::score).max().orElse(0);
        return hits.stream()
                .map(hit -> score(hit, queryTokens, minimum, maximum))
                .sorted(Comparator.comparingDouble(ScoredHit::rerankScore).reversed()
                        .thenComparing(
                                item -> item.hit().score(),
                                Comparator.reverseOrder())
                        .thenComparing(item -> item.hit().hit().childChunk().path())
                        .thenComparingInt(
                                item -> item.hit().hit().childChunk().ordinal())
                        .thenComparing(
                                item -> item.hit().hit().childChunk().childId()))
                .limit(limit)
                .map(item -> new RerankedHit(
                        item.hit().hit().childChunk().childId(),
                        item.rerankScore()))
                .toList();
    }

    private ScoredHit score(
            FusedHit hit,
            Set<String> queryTokens,
            double minimum,
            double maximum) {
        double coverage = coverage(hit, queryTokens);
        double normalizedRetrieval = maximum == minimum
                ? 0
                : (hit.score() - minimum) / (maximum - minimum);
        double rerankScore = COVERAGE_WEIGHT * coverage
                + RETRIEVAL_WEIGHT * normalizedRetrieval;
        return new ScoredHit(hit, rerankScore);
    }

    private double coverage(FusedHit hit, Set<String> queryTokens) {
        if (queryTokens.isEmpty()) {
            return 0;
        }
        var child = hit.hit().childChunk();
        var parent = hit.hit().parentChunk();
        String searchableText = String.join(" ",
                child.content(),
                parent.content(),
                child.symbol() == null ? "" : child.symbol(),
                child.path());
        Set<String> documentTokens = new LinkedHashSet<>(
                Bm25Scorer.tokenize(searchableText));
        long matched = queryTokens.stream().filter(documentTokens::contains).count();
        return (double) matched / queryTokens.size();
    }

    private record ScoredHit(FusedHit hit, double rerankScore) {
    }
}
