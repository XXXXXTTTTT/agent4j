package com.agent.web.rag;

import com.agent.rag.domain.ChildChunk;
import com.agent.rag.embedding.EmbeddingModel;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 调用 OpenAI 兼容 Embeddings API 的固定八维模型适配器。 */
public final class OpenAiEmbeddingModel implements EmbeddingModel {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiEmbeddingModel.class);
    private static final int DIMENSIONS = ChildChunk.EMBEDDING_DIMENSIONS;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String embeddingsPath;
    private final String model;
    private final String requestUrl;

    /** 创建不拥有共享 RestClient 生命周期的 Embedding 模型。 */
    public OpenAiEmbeddingModel(
            RestClient restClient,
            ObjectMapper objectMapper,
            String embeddingsPath,
            String model,
            String requestUrl) {
        this.restClient = Objects.requireNonNull(restClient, "restClient 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        if (embeddingsPath == null || embeddingsPath.isBlank()
                || !embeddingsPath.startsWith("/")) {
            throw new IllegalArgumentException("embeddingsPath 必须以 / 开头");
        }
        this.embeddingsPath = embeddingsPath;
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空");
        }
        this.model = model;
        if (requestUrl == null || requestUrl.isBlank()) {
            throw new IllegalArgumentException("requestUrl 不能为空");
        }
        this.requestUrl = requestUrl;
    }

    /** 返回数据库 schema 固定要求的八维向量。 */
    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    /** 发送单项 input 并严格解析唯一 index=0 的向量。 */
    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text 不能为空");
        }
        long startedAt = System.nanoTime();
        int[] status = {-1};
        try {
            float[] result = restClient.post()
                    .uri(embeddingsPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new EmbeddingRequest(model, List.of(text), DIMENSIONS))
                    .exchange((request, response) -> {
                        status[0] = response.getStatusCode().value();
                        if (response.getStatusCode().isError()) {
                            byte[] body = response.getBody().readAllBytes();
                            RestClientResponseException httpFailure =
                                    new RestClientResponseException(
                                            "Embedding HTTP 请求失败",
                                            response.getStatusCode(),
                                            response.getStatusText(),
                                            response.getHeaders(),
                                            body,
                                            StandardCharsets.UTF_8);
                            throw new OpenAiEmbeddingException(
                                    "Embedding HTTP 请求失败，状态码 " + status[0], httpFailure);
                        }
                        return parseResponse(response.getBody().readAllBytes());
                    });
            LOGGER.info(
                    "Embedding 请求完成 url={} model={} inputCount={} httpStatus={} durationMs={}",
                    requestUrl, model, 1, status[0], elapsedMillis(startedAt));
            return result;
        } catch (OpenAiEmbeddingException exception) {
            LOGGER.warn(
                    "Embedding 请求失败 url={} model={} inputCount={} httpStatus={} durationMs={}",
                    requestUrl, model, 1, status[0], elapsedMillis(startedAt), exception);
            throw exception;
        } catch (Exception exception) {
            LOGGER.warn(
                    "Embedding 请求失败 url={} model={} inputCount={} httpStatus={} durationMs={}",
                    requestUrl, model, 1, status[0], elapsedMillis(startedAt), exception);
            throw new OpenAiEmbeddingException("Embedding 请求执行失败", exception);
        }
    }

    private float[] parseResponse(byte[] body) throws IOException {
        JsonNode root;
        try (JsonParser parser = objectMapper.getFactory().createParser(body)) {
            root = parser.readValueAsTree();
            if (root == null) {
                throw protocol("响应 JSON 为空", null);
            }
            if (parser.nextToken() != null) {
                throw protocol("响应包含尾随 JSON", null);
            }
        }
        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.size() != 1) {
            throw protocol("data 必须包含唯一一项", null);
        }
        JsonNode item = data.get(0);
        JsonNode index = item.get("index");
        if (index == null || !index.isIntegralNumber() || index.intValue() != 0) {
            throw protocol("data index 必须精确为 0", null);
        }
        JsonNode embedding = item.get("embedding");
        if (embedding == null || !embedding.isArray() || embedding.size() != DIMENSIONS) {
            throw protocol("embedding 必须是 8 维数组", null);
        }
        float[] values = new float[DIMENSIONS];
        for (int position = 0; position < DIMENSIONS; position++) {
            JsonNode value = embedding.get(position);
            if (value == null || !value.isNumber()
                    || !Double.isFinite(value.doubleValue())) {
                throw protocol("embedding 必须全部是有限数", null);
            }
            values[position] = value.floatValue();
            if (!Float.isFinite(values[position])) {
                throw protocol("embedding 必须全部是有限数", null);
            }
        }
        return values;
    }

    private OpenAiEmbeddingException protocol(String message, Throwable cause) {
        return new OpenAiEmbeddingException(message, cause);
    }

    private long elapsedMillis(long startedAt) {
        return java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private record EmbeddingRequest(String model, List<String> input, int dimensions) {
    }
}
