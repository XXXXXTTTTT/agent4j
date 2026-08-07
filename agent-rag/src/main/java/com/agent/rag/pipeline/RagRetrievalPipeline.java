package com.agent.rag.pipeline;

import com.agent.core.context.TokenEstimator;
import com.agent.core.intent.TaskComplexity;
import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.RagHit;
import com.agent.rag.domain.RagQuery;
import com.agent.rag.embedding.EmbeddingModel;
import com.agent.rag.search.RagRetriever;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 编排查询增强、基础召回、融合、精排与 token 门禁。 */
public final class RagRetrievalPipeline {

    private final RagRetriever retriever;
    private final EmbeddingModel embeddingModel;
    private final QueryRewriter queryRewriter;
    private final HypotheticalDocumentGenerator hypotheticalDocumentGenerator;
    private final RagReranker reranker;
    private final ReciprocalRankFusion fusion;
    private final RagTokenBudgetSelector budgetSelector;

    /** 注入全部基础能力与可降级增强端口。 */
    public RagRetrievalPipeline(
            RagRetriever retriever,
            EmbeddingModel embeddingModel,
            QueryRewriter queryRewriter,
            HypotheticalDocumentGenerator hypotheticalDocumentGenerator,
            RagReranker reranker,
            TokenEstimator tokenEstimator) {
        this.retriever = Objects.requireNonNull(retriever, "retriever 不能为空");
        this.embeddingModel = Objects.requireNonNull(
                embeddingModel, "embeddingModel 不能为空");
        if (embeddingModel.dimensions() != ChildChunk.EMBEDDING_DIMENSIONS) {
            throw new IllegalArgumentException("EmbeddingModel dimensions 必须为 8");
        }
        this.queryRewriter = Objects.requireNonNull(
                queryRewriter, "queryRewriter 不能为空");
        this.hypotheticalDocumentGenerator = Objects.requireNonNull(
                hypotheticalDocumentGenerator,
                "hypotheticalDocumentGenerator 不能为空");
        this.reranker = Objects.requireNonNull(reranker, "reranker 不能为空");
        this.fusion = new ReciprocalRankFusion();
        this.budgetSelector = new RagTokenBudgetSelector(tokenEstimator);
    }

    /** 执行固定六阶段检索并返回完整审计证据。 */
    public RagRetrievalResult retrieve(RagRetrievalRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        List<RagStageEvidence> evidence = new ArrayList<>();
        List<String> queries = rewriteQueries(request, evidence);
        float[] originalEmbedding = generateHydeEmbedding(request, evidence);
        List<List<RagHit>> rankedLists = retrieveBaseline(
                request, queries, originalEmbedding, evidence);
        List<FusedHit> fusedHits = fuse(rankedLists, evidence);
        List<FusedHit> limitedHits = fusedHits.stream()
                .limit(request.policy().retrievalLimit())
                .toList();
        List<RerankedHit> rerankedHits = rerank(
                request, limitedHits, evidence);
        List<RagContextDocument> documents = budgetSelector.select(
                limitedHits,
                rerankedHits,
                request.policy().maxContextTokens());
        int estimatedTokens = documents.stream()
                .mapToInt(RagContextDocument::estimatedTokens)
                .sum();
        evidence.add(applied(
                RagStage.TOKEN_BUDGET,
                rerankedHits.size(),
                documents.size(),
                estimatedTokens,
                "按完整父子块执行 token 门禁"));
        boolean degraded = evidence.stream()
                .anyMatch(item -> item.status() == RagStageStatus.DEGRADED);
        return new RagRetrievalResult(
                documents, evidence, estimatedTokens, degraded);
    }

    private List<String> rewriteQueries(
            RagRetrievalRequest request, List<RagStageEvidence> evidence) {
        int maximumQueries = switch (request.complexity()) {
            case SIMPLE -> 1;
            case STANDARD -> Math.min(2, request.policy().rewriteLimit());
            case COMPLEX -> request.policy().rewriteLimit();
        };
        if (maximumQueries == 1) {
            evidence.add(skipped(
                    RagStage.QUERY_REWRITE, 1, 1,
                    "任务复杂度或策略只允许原始查询"));
            return List.of(request.query());
        }
        try {
            List<String> rewritten = Objects.requireNonNull(
                    queryRewriter.rewrite(request.query(), maximumQueries - 1),
                    "查询改写结果不能为空");
            Set<String> unique = new LinkedHashSet<>();
            unique.add(request.query());
            for (String item : rewritten) {
                if (item == null) {
                    continue;
                }
                String normalized = item.trim();
                if (!normalized.isEmpty()) {
                    unique.add(normalized);
                }
                if (unique.size() == maximumQueries) {
                    break;
                }
            }
            List<String> result = List.copyOf(unique);
            evidence.add(applied(
                    RagStage.QUERY_REWRITE, 1, result.size(), 0,
                    "完成查询改写与精确去重"));
            return result;
        } catch (RuntimeException exception) {
            evidence.add(degraded(
                    RagStage.QUERY_REWRITE, 1, 1,
                    "查询改写失败，保留原始查询", exception));
            return List.of(request.query());
        }
    }

    private float[] generateHydeEmbedding(
            RagRetrievalRequest request, List<RagStageEvidence> evidence) {
        if (request.complexity() != TaskComplexity.COMPLEX
                || !request.policy().hydeEnabled()) {
            evidence.add(skipped(
                    RagStage.HYDE, 1, 0,
                    "任务复杂度或策略未启用 HyDE"));
            return null;
        }
        String hypotheticalDocument;
        try {
            hypotheticalDocument = hypotheticalDocumentGenerator.generate(
                    request.query());
            if (hypotheticalDocument == null || hypotheticalDocument.isBlank()) {
                throw new IllegalArgumentException("HyDE 结果不能为空");
            }
        } catch (RuntimeException exception) {
            evidence.add(degraded(
                    RagStage.HYDE, 1, 0,
                    "HyDE 生成失败，使用原始查询 embedding", exception));
            return null;
        }
        try {
            float[] embedding = embeddingModel.embed(hypotheticalDocument);
            if (embedding == null
                    || embedding.length != ChildChunk.EMBEDDING_DIMENSIONS) {
                throw new IllegalArgumentException("HyDE embedding 维度必须为 8");
            }
            evidence.add(applied(
                    RagStage.HYDE, 1, 1, 0,
                    "HyDE 仅替换原始查询的向量"));
            return embedding;
        } catch (RuntimeException exception) {
            throw new RagPipelineException("生成 HyDE embedding 失败", exception);
        }
    }

    private List<List<RagHit>> retrieveBaseline(
            RagRetrievalRequest request,
            List<String> queries,
            float[] originalEmbedding,
            List<RagStageEvidence> evidence) {
        try {
            List<List<RagHit>> rankedLists = new ArrayList<>();
            int hitCount = 0;
            for (int index = 0; index < queries.size(); index++) {
                RagQuery query = new RagQuery(
                        request.repositoryId(),
                        queries.get(index),
                        index == 0 ? originalEmbedding : null,
                        request.policy().retrievalLimit());
                List<RagHit> hits = List.copyOf(Objects.requireNonNull(
                        retriever.search(query), "基础召回结果不能为空"));
                rankedLists.add(hits);
                hitCount += hits.size();
            }
            evidence.add(applied(
                    RagStage.BASELINE_RETRIEVAL,
                    queries.size(), hitCount, 0,
                    "完成全部查询的基础召回"));
            return List.copyOf(rankedLists);
        } catch (RuntimeException exception) {
            throw new RagPipelineException("RAG 基础召回失败", exception);
        }
    }

    private List<FusedHit> fuse(
            List<List<RagHit>> rankedLists, List<RagStageEvidence> evidence) {
        try {
            List<FusedHit> fusedHits = fusion.fuse(rankedLists);
            int inputCount = rankedLists.stream().mapToInt(List::size).sum();
            evidence.add(applied(
                    RagStage.FUSION, inputCount, fusedHits.size(), 0,
                    "使用固定 k=60 的 RRF 融合"));
            return fusedHits;
        } catch (RuntimeException exception) {
            throw new RagPipelineException("RAG 融合失败", exception);
        }
    }

    private List<RerankedHit> rerank(
            RagRetrievalRequest request,
            List<FusedHit> fusedHits,
            List<RagStageEvidence> evidence) {
        try {
            List<RerankedHit> result = RerankValidation.validate(
                    fusedHits,
                    reranker.rerank(
                            request.query(),
                            fusedHits,
                            request.policy().rerankLimit()),
                    request.policy().rerankLimit());
            evidence.add(applied(
                    RagStage.RERANK, fusedHits.size(), result.size(), 0,
                    "完成精排并校验返回协议"));
            return result;
        } catch (RuntimeException exception) {
            List<RerankedHit> fallback = fusedHits.stream()
                    .limit(request.policy().rerankLimit())
                    .map(hit -> new RerankedHit(
                            hit.hit().childChunk().childId(), hit.score()))
                    .toList();
            evidence.add(degraded(
                    RagStage.RERANK,
                    fusedHits.size(),
                    fallback.size(),
                    "精排失败，保留融合排序", exception));
            return fallback;
        }
    }

    private RagStageEvidence applied(
            RagStage stage,
            int inputCount,
            int outputCount,
            int estimatedTokens,
            String detail) {
        return new RagStageEvidence(
                stage, RagStageStatus.APPLIED,
                inputCount, outputCount, estimatedTokens, detail, "");
    }

    private RagStageEvidence skipped(
            RagStage stage, int inputCount, int outputCount, String detail) {
        return new RagStageEvidence(
                stage, RagStageStatus.SKIPPED,
                inputCount, outputCount, 0, detail, "");
    }

    private RagStageEvidence degraded(
            RagStage stage,
            int inputCount,
            int outputCount,
            String detail,
            RuntimeException exception) {
        return new RagStageEvidence(
                stage, RagStageStatus.DEGRADED,
                inputCount, outputCount, 0, detail, stackTrace(exception));
    }

    private String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
