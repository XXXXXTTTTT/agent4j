package com.agent.web.config;

import com.agent.core.llm.InferenceCapability;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Set;

/** 通过环境变量注入的 OpenAI 兼容模型网关配置。 */
@ConfigurationProperties(prefix = "agent.llm")
public record ModelGatewayProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String chatCompletionsPath,
        String imageGenerationPath,
        String codeModel,
        String visionModel,
        String imageModel,
        String quickClassificationModel,
        String fallbackModel,
        int maxConcurrentRequests,
        int maxRequestsPerMinute,
        Duration queueTimeout,
        Set<InferenceCapability> codeCapabilities,
        Set<InferenceCapability> visionCapabilities,
        Set<InferenceCapability> quickClassificationCapabilities,
        Set<InferenceCapability> fallbackCapabilities) {

    /** 保留已有调用方的默认推理预算与能力声明。 */
    public ModelGatewayProperties(
            boolean enabled,
            String baseUrl,
            String apiKey,
            String chatCompletionsPath,
            String codeModel,
            String visionModel,
            String quickClassificationModel,
            String fallbackModel) {
        this(
                enabled,
                baseUrl,
                apiKey,
                chatCompletionsPath,
                "/v1/images/generations",
                codeModel,
                visionModel,
                "",
                quickClassificationModel,
                fallbackModel,
                8,
                120,
                Duration.ofSeconds(2),
                Set.of(
                        InferenceCapability.CHAT_COMPLETIONS,
                        InferenceCapability.STREAMING,
                        InferenceCapability.TOOL_CALLING),
                Set.of(
                        InferenceCapability.CHAT_COMPLETIONS,
                        InferenceCapability.STREAMING,
                        InferenceCapability.VISION_INPUT),
                Set.of(
                        InferenceCapability.CHAT_COMPLETIONS,
                        InferenceCapability.STREAMING),
                Set.of(InferenceCapability.CHAT_COMPLETIONS));
    }

    /** 保留显式预算调用方在新增 Images API 路径后的构造兼容性。 */
    public ModelGatewayProperties(
            boolean enabled,
            String baseUrl,
            String apiKey,
            String chatCompletionsPath,
            String codeModel,
            String visionModel,
            String quickClassificationModel,
            String fallbackModel,
            int maxConcurrentRequests,
            int maxRequestsPerMinute,
            Duration queueTimeout,
            Set<InferenceCapability> codeCapabilities,
            Set<InferenceCapability> visionCapabilities,
            Set<InferenceCapability> quickClassificationCapabilities,
            Set<InferenceCapability> fallbackCapabilities) {
        this(
                enabled,
                baseUrl,
                apiKey,
                chatCompletionsPath,
                "/v1/images/generations",
                codeModel,
                visionModel,
                "",
                quickClassificationModel,
                fallbackModel,
                maxConcurrentRequests,
                maxRequestsPerMinute,
                queueTimeout,
                codeCapabilities,
                visionCapabilities,
                quickClassificationCapabilities,
                fallbackCapabilities);
    }

    /** 冻结文本配置，禁用网关时允许留空凭据。 */
    @ConstructorBinding
    public ModelGatewayProperties {
        baseUrl = textOrEmpty(baseUrl);
        apiKey = textOrEmpty(apiKey);
        chatCompletionsPath = textOrDefault(
                chatCompletionsPath, "/v1/chat/completions");
        imageGenerationPath = textOrDefault(
                imageGenerationPath, "/v1/images/generations");
        codeModel = textOrEmpty(codeModel);
        visionModel = textOrEmpty(visionModel);
        imageModel = textOrEmpty(imageModel);
        if (imageModel.isBlank()) {
            imageModel = visionModel;
        }
        quickClassificationModel = textOrEmpty(quickClassificationModel);
        fallbackModel = textOrEmpty(fallbackModel);
        if (maxConcurrentRequests <= 0) {
            throw new IllegalArgumentException(
                    "agent.llm.max-concurrent-requests 必须大于 0");
        }
        if (maxRequestsPerMinute <= 0) {
            throw new IllegalArgumentException(
                    "agent.llm.max-requests-per-minute 必须大于 0");
        }
        if (queueTimeout == null || queueTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "agent.llm.queue-timeout 不能为负数且不能为空");
        }
        codeCapabilities = freezeCapabilities(
                codeCapabilities, "agent.llm.code-capabilities");
        visionCapabilities = freezeCapabilities(
                visionCapabilities, "agent.llm.vision-capabilities");
        quickClassificationCapabilities = freezeCapabilities(
                quickClassificationCapabilities,
                "agent.llm.quick-classification-capabilities");
        fallbackCapabilities = freezeCapabilities(
                fallbackCapabilities, "agent.llm.fallback-capabilities");
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
        if (!imageGenerationPath.startsWith("/")) {
            throw new IllegalArgumentException(
                    "agent.llm.image-generation-path 必须以 / 开头");
        }
        requireText(codeModel, "agent.llm.code-model");
        requireText(visionModel, "agent.llm.vision-model");
        requireText(imageModel, "agent.llm.image-model");
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

    private static Set<InferenceCapability> freezeCapabilities(
            Set<InferenceCapability> capabilities,
            String property) {
        if (capabilities == null || capabilities.isEmpty()) {
            throw new IllegalArgumentException(property + " 不能为空");
        }
        if (capabilities.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException(property + " 不能包含空能力");
        }
        return Set.copyOf(capabilities);
    }
}
