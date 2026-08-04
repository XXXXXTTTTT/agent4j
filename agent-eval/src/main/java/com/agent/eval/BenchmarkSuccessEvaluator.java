package com.agent.eval;

import com.agent.core.engine.RunCheckpoint;

/** 由调用方提供的强类型 Benchmark 成功判定端口。 */
@FunctionalInterface
public interface BenchmarkSuccessEvaluator {

    /** 根据任务和终态快照返回是否通过，不解析自由文本。 */
    boolean passed(BenchmarkTask task, RunCheckpoint terminalCheckpoint);
}
