package com.agent.cli;

/** Agent4J HTTP 请求失败，保留服务端状态和 ProblemDetail 原文。 */
public final class Agent4jHttpException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public Agent4jHttpException(int statusCode, String responseBody) {
        super("Agent4J HTTP 请求失败: " + statusCode);
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? "" : responseBody;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }
}
