package com.agent.core.llm;

import java.net.URI;
import java.net.URISyntaxException;

/** OpenAI 兼容接口的传输基础地址、请求路径与审计地址。 */
public record OpenAiEndpoint(
        String transportBaseUrl,
        String requestPath,
        String requestUrl) {

    /**
     * 将网关基础地址和 Chat Completions 路径解析为唯一请求地址。
     *
     * @param baseUrl        网关基础地址
     * @param configuredPath Chat Completions 请求路径
     * @return 已去除重复路径前缀的端点
     */
    public static OpenAiEndpoint resolve(String baseUrl, String configuredPath) {
        URI base;
        try {
            base = new URI(baseUrl);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("agent.llm.base-url URI 无效", exception);
        }
        String basePath = normalizePath(base.getPath());
        String path = configuredPath == null ? "" : configuredPath.trim();
        if (path.isEmpty() || !path.startsWith("/")) {
            throw new IllegalArgumentException(
                    "agent.llm.chat-completions-path 必须以 / 开头");
        }
        String finalPath = basePath.isEmpty() || path.equals(basePath)
                || path.startsWith(basePath + "/")
                ? path
                : joinPaths(basePath, path);
        String transportBaseUrl;
        try {
            transportBaseUrl = new URI(
                    base.getScheme(),
                    base.getUserInfo(),
                    base.getHost(),
                    base.getPort(),
                    null,
                    null,
                    null).toString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("agent.llm.base-url URI 无效", exception);
        }
        return new OpenAiEndpoint(
                transportBaseUrl,
                finalPath,
                transportBaseUrl + finalPath);
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }
        String normalized = path.trim();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private static String joinPaths(String left, String right) {
        return normalizePath(left) + "/" + right.substring(1);
    }
}
