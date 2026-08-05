package com.agent.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;

/** 通过环境变量注入的 OpenAI 兼容模型网关配置。 */
@ConfigurationProperties(prefix = "agent.llm")
public record ModelGatewayProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String chatCompletionsPath,
        String codeModel,
        String visionModel,
        String quickClassificationModel,
        String fallbackModel) {

    /** 冻结文本配置，禁用网关时允许留空凭据。 */
    public ModelGatewayProperties {
        baseUrl = textOrEmpty(baseUrl);
        apiKey = textOrEmpty(apiKey);
        chatCompletionsPath = textOrDefault(
                chatCompletionsPath, "/v1/chat/completions");
        codeModel = textOrEmpty(codeModel);
        visionModel = textOrEmpty(visionModel);
        quickClassificationModel = textOrEmpty(quickClassificationModel);
        fallbackModel = textOrEmpty(fallbackModel);
    }

    /** 校验启用模型网关所需的完整配置。 */
    public void validate() {
        if (!enabled) {
            return;
        }
        URI endpoint;
        try {
            endpoint = new URI(baseUrl);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(
                    "agent.llm.base-url 必须是绝对 HTTP/HTTPS URI", exception);
        }
        String scheme = endpoint.getScheme();
        if (!endpoint.isAbsolute()
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(
                    "agent.llm.base-url 必须是绝对 HTTP/HTTPS URI");
        }
        requireText(apiKey, "agent.llm.api-key");
        if (!chatCompletionsPath.startsWith("/")) {
            throw new IllegalArgumentException(
                    "agent.llm.chat-completions-path 必须以 / 开头");
        }
        requireText(codeModel, "agent.llm.code-model");
        requireText(visionModel, "agent.llm.vision-model");
        requireText(quickClassificationModel, "agent.llm.quick-classification-model");
        requireText(fallbackModel, "agent.llm.fallback-model");
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String textOrDefault(String value, String defaultValue) {
        String normalized = textOrEmpty(value);
        return normalized.isEmpty() ? defaultValue : normalized;
    }

    private static void requireText(String value, String property) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(property + " 不能为空");
        }
    }
}
