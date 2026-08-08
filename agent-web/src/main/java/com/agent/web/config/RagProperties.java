package com.agent.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/** Codebase RAG 端点、增强策略和索引等待配置。 */
@ConfigurationProperties(prefix = "agent.rag")
public record RagProperties(
        boolean enabled,
        String embeddingsPath,
        String embeddingModel,
        boolean rewriteEnabled,
        boolean hydeEnabled,
        boolean strict,
        Duration indexTimeout) {

    /** 规范化文本并保留显式配置值。 */
    public RagProperties {
        embeddingsPath = embeddingsPath == null ? "" : embeddingsPath.trim();
        embeddingModel = embeddingModel == null ? "" : embeddingModel.trim();
        indexTimeout = Objects.requireNonNull(indexTimeout, "indexTimeout 不能为空");
    }

    /** 校验 RAG 启用时所需的协议与模型配置。 */
    public void validate() {
        if (indexTimeout.isZero() || indexTimeout.isNegative()) {
            throw new IllegalArgumentException("agent.rag.index-timeout 必须大于 0");
        }
        if (!embeddingsPath.startsWith("/")) {
            throw new IllegalArgumentException("agent.rag.embeddings-path 必须以 / 开头");
        }
        if (enabled && embeddingModel.isBlank()) {
            throw new IllegalArgumentException("agent.rag.embedding-model 不能为空");
        }
    }
}
