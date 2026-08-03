package com.agent.rag.memory;

import com.agent.rag.embedding.EmbeddingModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** 长期记忆的提取、持久化、召回和稳定排序协调器。 */
public final class MemoryManager {

    private static final double VECTOR_WEIGHT = 0.65;
    private static final double LEXICAL_WEIGHT = 0.35;

    private final MemoryExtractor extractor;
    private final MemoryStore store;
    private final EmbeddingModel embeddingModel;
    private final Clock clock;
    private final Supplier<UUID> idSupplier;

    /** 创建无请求共享可变状态的记忆管理器。 */
    public MemoryManager(
            MemoryExtractor extractor,
            MemoryStore store,
            EmbeddingModel embeddingModel,
            Clock clock,
            Supplier<UUID> idSupplier) {
        this.extractor = Objects.requireNonNull(extractor, "extractor 不能为空");
        this.store = Objects.requireNonNull(store, "store 不能为空");
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier 不能为空");
        if (embeddingModel.dimensions() != 8) {
            throw new IllegalArgumentException("embeddingModel 维度必须为 8");
        }
    }

    /** 提取原始观察并在同一 store 事务中 upsert 全部记忆。 */
    public List<MemoryEntry> capture(MemoryCapture capture) {
        Objects.requireNonNull(capture, "capture 不能为空");
        List<MemoryDraft> drafts = Objects.requireNonNull(
                extractor.extract(capture), "extractor 返回值不能为空");
        if (drafts.size() > 20) {
            throw new IllegalArgumentException("单次提取不能超过 20 项");
        }
        if (drafts.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        List<MemoryEntry> entries = new ArrayList<>(drafts.size());
        for (MemoryDraft draft : drafts) {
            Objects.requireNonNull(draft, "extractor 不能返回 null 草稿");
            String hash = contentHash(draft);
            float[] embedding = embed(draft.title() + "\n" + draft.content());
            entries.add(new MemoryEntry(
                    Objects.requireNonNull(idSupplier.get(), "idSupplier 返回 null"),
                    capture.repositoryId(),
                    capture.userId(),
                    draft.type(),
                    draft.title(),
                    draft.content(),
                    hash,
                    embedding,
                    now,
                    now));
        }
        List<MemoryEntry> saved = Objects.requireNonNull(
                store.upsertAll(List.copyOf(entries)), "store 返回值不能为空");
        if (saved.size() != entries.size()) {
            throw new IllegalStateException("store 返回条目数量与写入数量不一致");
        }
        return List.copyOf(saved);
    }

    /** 执行两路召回、独立归一化并按最终分数稳定排序。 */
    public List<MemoryHit> recall(MemoryQuery query) {
        Objects.requireNonNull(query, "query 不能为空");
        float[] queryEmbedding = embed(query.query());
        List<MemoryRetrievalRow> vectorRows = Objects.requireNonNull(
                store.findByVector(query, queryEmbedding, query.limit()),
                "vectorRows 不能为空");
        List<MemoryRetrievalRow> lexicalRows = Objects.requireNonNull(
                store.findByLexical(query, query.limit()),
                "lexicalRows 不能为空");
        Map<UUID, CombinedRow> merged = new HashMap<>();
        for (MemoryRetrievalRow row : vectorRows) {
            validateScope(query, row);
            merged.computeIfAbsent(row.entry().memoryId(), ignored -> new CombinedRow(row.entry()))
                    .vectorScore = row.retrievalScore();
        }
        for (MemoryRetrievalRow row : lexicalRows) {
            validateScope(query, row);
            CombinedRow combined = merged.computeIfAbsent(
                    row.entry().memoryId(), ignored -> new CombinedRow(row.entry()));
            if (!combined.entry.equals(row.entry())) {
                throw new IllegalStateException("同一 memoryId 的数据库条目不一致");
            }
            combined.lexicalScore = row.retrievalScore();
        }
        List<CombinedRow> rows = new ArrayList<>(merged.values());
        double vectorMin = rows.stream().mapToDouble(row -> row.vectorScore).min().orElse(0);
        double vectorMax = rows.stream().mapToDouble(row -> row.vectorScore).max().orElse(0);
        double lexicalMin = rows.stream().mapToDouble(row -> row.lexicalScore).min().orElse(0);
        double lexicalMax = rows.stream().mapToDouble(row -> row.lexicalScore).max().orElse(0);
        return rows.stream()
                .map(row -> {
                    double vector = normalize(row.vectorScore, vectorMin, vectorMax);
                    double lexical = normalize(row.lexicalScore, lexicalMin, lexicalMax);
                    return new MemoryHit(
                            row.entry,
                            vector,
                            lexical,
                            VECTOR_WEIGHT * vector + LEXICAL_WEIGHT * lexical);
                })
                .sorted(Comparator
                        .comparingDouble(MemoryHit::finalScore).reversed()
                        .thenComparing(hit -> hit.entry().updatedAt(), Comparator.reverseOrder())
                        .thenComparing(hit -> hit.entry().memoryId()))
                .limit(query.limit())
                .toList();
    }

    private float[] embed(String text) {
        float[] embedding = embeddingModel.embed(text);
        if (embedding == null || embedding.length != 8) {
            throw new IllegalArgumentException("embedding 必须为 8 维");
        }
        for (float element : embedding) {
            if (!Float.isFinite(element)) {
                throw new IllegalArgumentException("embedding 必须只包含有限数");
            }
        }
        return embedding;
    }

    private void validateScope(MemoryQuery query, MemoryRetrievalRow row) {
        Objects.requireNonNull(row, "store 不能返回 null 行");
        MemoryEntry entry = Objects.requireNonNull(row.entry(), "store 行 entry 不能为空");
        if (!query.repositoryId().equals(entry.repositoryId())
                || !query.userId().equals(entry.userId())
                || !query.types().contains(entry.type())) {
            throw new IllegalStateException("store 返回了不匹配的 memory scope/type");
        }
    }

    private double normalize(double value, double min, double max) {
        if (max == min) {
            return 0;
        }
        return (value - min) / (max - min);
    }

    private String contentHash(MemoryDraft draft) {
        String input = draft.type().name() + "\n" + draft.title() + "\n" + draft.content();
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static final class CombinedRow {
        private final MemoryEntry entry;
        private double vectorScore;
        private double lexicalScore;

        private CombinedRow(MemoryEntry entry) {
            this.entry = entry;
        }
    }
}
