package com.agent.core.knowledge;

import java.util.List;
import java.util.Objects;

/** 可注入规划 Prompt 的不可变知识上下文。 */
public record KnowledgeContext(
        String prompt,
        int sourceCount,
        String fingerprint,
        int estimatedTokens,
        boolean degraded,
        List<KnowledgeEvidence> evidence) {

    /** 冻结证据并确保降级标志与证据状态一致。 */
    public KnowledgeContext {
        Objects.requireNonNull(prompt, "prompt 不能为空");
        if (sourceCount < 0) {
            throw new IllegalArgumentException("sourceCount 不能为负数");
        }
        Objects.requireNonNull(fingerprint, "fingerprint 不能为空");
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("estimatedTokens 不能为负数");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence 不能为空"));
        boolean hasDegraded = evidence.stream()
                .anyMatch(item -> item.status() == KnowledgeEvidenceStatus.DEGRADED);
        if (degraded != hasDegraded) {
            throw new IllegalArgumentException("degraded 必须与 evidence 中的 DEGRADED 状态一致");
        }
    }

    /** 返回确定性的空知识上下文。 */
    public static KnowledgeContext empty() {
        return EmptyHolder.INSTANCE;
    }

    private static final class EmptyHolder {
        private static final KnowledgeContext INSTANCE =
                new KnowledgeContext("", 0, "", 0, false, List.of());
    }
}
