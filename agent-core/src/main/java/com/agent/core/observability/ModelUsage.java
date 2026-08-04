package com.agent.core.observability;

/** 一次模型响应的精确 Token 用量。 */
public record ModelUsage(
        int promptTokens,
        int completionTokens,
        int totalTokens) {

    /** 校验 Token 数值及总数。 */
    public ModelUsage {
        requireNonNegative(promptTokens, "promptTokens");
        requireNonNegative(completionTokens, "completionTokens");
        requireNonNegative(totalTokens, "totalTokens");
        if ((long) promptTokens + completionTokens != totalTokens) {
            throw new IllegalArgumentException("totalTokens 必须等于 promptTokens + completionTokens");
        }
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " 不能为负数");
        }
    }
}
