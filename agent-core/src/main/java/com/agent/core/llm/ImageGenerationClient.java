package com.agent.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Base64;
import java.util.Objects;

/** 独立的 OpenAI Images API 客户端，不复用 Chat Completions 请求协议。 */
public final class ImageGenerationClient implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImageGenerationClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String requestPath;
    private final String model;

    public ImageGenerationClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            String requestPath,
            String model) {
        this.restClient = Objects.requireNonNull(restClient, "restClient 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        if (requestPath == null || !requestPath.startsWith("/")) {
            throw new IllegalArgumentException("requestPath 必须以 / 开头");
        }
        this.requestPath = requestPath;
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空");
        }
        this.model = model.trim();
    }

    /** 生成一张图片并将网关响应统一为前端可直接渲染的图片 URL。 */
    public GeneratedImage generate(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        long startedAt = System.nanoTime();
        try {
            String body = restClient.post()
                    .uri(requestPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(objectMapper.createObjectNode()
                            .put("model", model)
                            .put("prompt", prompt))
                    .exchange((request, response) -> {
                        String responseBody = new String(
                                response.getBody().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                        long durationMs = java.time.Duration.ofNanos(
                                System.nanoTime() - startedAt).toMillis();
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            LOGGER.warn(
                                    "图片生成请求失败 path={} model={} httpStatus={} durationMs={}",
                                    requestPath, model,
                                    response.getStatusCode().value(), durationMs);
                            throw new ImageGenerationException(
                                    "图片生成网关返回 HTTP "
                                            + response.getStatusCode().value()
                                            + ": " + responseBody);
                        }
                        LOGGER.info(
                                "图片生成请求完成 path={} model={} httpStatus={} durationMs={}",
                                requestPath, model,
                                response.getStatusCode().value(), durationMs);
                        return responseBody;
                    });
            try {
                JsonNode root = objectMapper.readTree(body);
                JsonNode data = root == null ? null : root.get("data");
                if (data == null || !data.isArray() || data.isEmpty()) {
                    throw new ImageGenerationException("图片生成响应缺少非空 data 数组");
                }
                JsonNode item = data.get(0);
                String revisedPrompt = item.path("revised_prompt").asText("");
                JsonNode base64 = item.get("b64_json");
                if (base64 != null
                        && base64.isTextual()
                        && !base64.textValue().isBlank()) {
                    validateBase64(base64.textValue());
                    return new GeneratedImage(
                            "data:image/png;base64," + base64.textValue(),
                            revisedPrompt,
                            model);
                }
                JsonNode url = item.get("url");
                if (url != null && url.isTextual()) {
                    URI parsed = URI.create(url.textValue());
                    if (!parsed.isAbsolute()
                            || (!"http".equalsIgnoreCase(parsed.getScheme())
                            && !"https".equalsIgnoreCase(parsed.getScheme()))) {
                        throw new ImageGenerationException(
                                "图片响应 URL 必须是 HTTP/HTTPS");
                    }
                    return new GeneratedImage(url.textValue(), revisedPrompt, model);
                }
                throw new ImageGenerationException("图片生成响应缺少 b64_json 或 url");
            } catch (ImageGenerationException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ImageGenerationException("图片生成响应解析失败", exception);
            }
        } catch (ImageGenerationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "图片生成请求异常 path={} model={} durationMs={}",
                    requestPath, model,
                    java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                    exception);
            throw new ImageGenerationException("图片生成请求执行失败", exception);
        }
    }

    private void validateBase64(String value) {
        try {
            Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new ImageGenerationException("图片响应 b64_json 不是合法 Base64", exception);
        }
    }

    @Override
    public void close() {
        // RestClient 不持有本客户端创建的可关闭资源。
    }

    /** 统一的图片工件。 */
    public record GeneratedImage(String dataUrl, String revisedPrompt, String model) {
        public GeneratedImage {
            if (dataUrl == null || dataUrl.isBlank()) {
                throw new IllegalArgumentException("dataUrl 不能为空");
            }
            revisedPrompt = revisedPrompt == null ? "" : revisedPrompt;
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model 不能为空");
            }
        }
    }

    /** 图片生成上游或协议错误。 */
    public static final class ImageGenerationException extends RuntimeException {
        public ImageGenerationException(String message) {
            super(message);
        }

        public ImageGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
