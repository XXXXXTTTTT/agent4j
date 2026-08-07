package com.agent.core.engine;

/**
 * 图执行达到最大允许步数后仍未结束。
 */
public class MaxStepsExceededException extends ExecutionBudgetExceededException {

    private final int maxSteps;

    /**
     * 创建最大步数异常。
     *
     * @param maxSteps 最大允许步数
     */
    public MaxStepsExceededException(int maxSteps) {
        this(maxSteps, maxSteps, 0);
    }

    /** 创建携带实际步数和累计 token 的兼容异常。 */
    public MaxStepsExceededException(int observedSteps, int maxSteps, long consumedTokens) {
        super(ExecutionStopReason.MAX_STEPS, observedSteps, maxSteps, consumedTokens);
        this.maxSteps = maxSteps;
    }

    /**
     * 返回最大允许步数。
     *
     * @return 最大步数
     */
    public int maxSteps() {
        return maxSteps;
    }
}
