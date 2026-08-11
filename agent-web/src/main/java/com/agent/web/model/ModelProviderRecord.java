package com.agent.web.model;

import java.time.Instant;
import java.util.UUID;

/** 用户级模型供应商展示记录，API Key 只以掩码形式返回。 */
public record ModelProviderRecord(
        UUID providerId,
        String ownerUserId,
        String displayName,
        String baseUrl,
        String chatCompletionsPath,
        String apiKeyMasked,
        Instant createdAt,
        Instant updatedAt) {

    /** 兼容 V5 记录构造器，使用标准 Chat Completions 路径。 */
    public ModelProviderRecord(
            UUID providerId,
            String ownerUserId,
            String displayName,
            String baseUrl,
            String apiKeyMasked,
            Instant createdAt,
            Instant updatedAt) {
        this(providerId, ownerUserId, displayName, baseUrl,
                "/v1/chat/completions", apiKeyMasked, createdAt, updatedAt);
    }
}
