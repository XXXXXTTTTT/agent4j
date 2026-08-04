package com.agent.eval;

/** 单次 Benchmark 任务执行端口，由调用方注入真实 Agent 执行逻辑。 */
@FunctionalInterface
public interface BenchmarkTaskExecutor {

    /** 执行一次任务并返回完整时间线结果。 */
    BenchmarkTaskResult execute(BenchmarkTask task, int repetition, java.time.Duration timeout);
}
