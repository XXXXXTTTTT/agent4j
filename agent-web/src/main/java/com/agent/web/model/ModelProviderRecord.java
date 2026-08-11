package com.agent.web.model;

import java.time.Instant;
import java.util.UUID;

/** 用户级模型供应商展示记录，API Key 只以掩码形式返回。 */
public record ModelProviderRecord(
        UUID providerId,
        String ownerUserId,
        String displayName,
        String baseUrl,
        String apiKeyMasked,
        Instant createdAt,
        Instant updatedAt) {
}
