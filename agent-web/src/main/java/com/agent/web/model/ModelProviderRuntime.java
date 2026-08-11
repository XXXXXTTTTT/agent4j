package com.agent.web.model;

import java.util.UUID;

/** 动态模型路由使用的 Provider 私密运行时配置，不得直接返回前端。 */
public record ModelProviderRuntime(
        UUID providerId,
        String ownerUserId,
        String baseUrl,
        String chatCompletionsPath,
        String apiKey) {
}
