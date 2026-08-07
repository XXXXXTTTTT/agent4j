package com.agent.core.context;

/** 受保护上下文消息已经超过输入预算。 */
public final class ContextBudgetExceededException extends RuntimeException {

    public ContextBudgetExceededException(int protectedTokens, int maxInputTokens) {
        super("受保护消息 token 估算值 " + protectedTokens
                + " 超过上下文上限 " + maxInputTokens);
    }
}
