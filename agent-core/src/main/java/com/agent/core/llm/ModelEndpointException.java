package com.agent.core.llm;

/** 单个模型端点执行失败。 */
public final class ModelEndpointException extends RuntimeException {

    /**
     * 保留失败端点、模型和原始异常。
     *
     * @param endpointName 端点名称
     * @param model        模型名称
     * @param cause        原始异常
     */
    public ModelEndpointException(String endpointName, String model, Throwable cause) {
        super("模型端点执行失败: endpoint=" + endpointName + ", model=" + model, cause);
    }
}
