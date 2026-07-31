package com.agent.core.engine;

/**
 * 图执行达到最大允许步数后仍未结束。
 */
public class MaxStepsExceededException extends RuntimeException {

    private final int maxSteps;

    /**
     * 创建最大步数异常。
     *
     * @param maxSteps 最大允许步数
     */
    public MaxStepsExceededException(int maxSteps) {
        super("图执行达到最大步数: " + maxSteps);
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
