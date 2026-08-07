package com.agent.core.knowledge;

import java.util.Objects;

/** 一条可审计的知识上下文证据。 */
public record KnowledgeEvidence(
        KnowledgeEvidenceKind kind,
        String source,
        KnowledgeEvidenceStatus status,
        String detail,
        String errorStack) {

    /** 校验证据字段与降级堆栈契约。 */
    public KnowledgeEvidence {
        Objects.requireNonNull(kind, "kind 不能为空");
        source = requireText(source, "source");
        Objects.requireNonNull(status, "status 不能为空");
        detail = requireText(detail, "detail");
        if (status == KnowledgeEvidenceStatus.DEGRADED) {
            errorStack = requireText(errorStack, "DEGRADED evidence 必须保留 errorStack");
        } else if (errorStack != null) {
            throw new IllegalArgumentException("非 DEGRADED evidence 不允许包含 errorStack");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
