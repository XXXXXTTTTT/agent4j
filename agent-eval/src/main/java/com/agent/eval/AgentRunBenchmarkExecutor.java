package com.agent.eval;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.concurrent.locks.LockSupport;

/** 将版本化 Benchmark 任务接线到现有 AgentRunService 的执行适配器。 */
public final class AgentRunBenchmarkExecutor implements BenchmarkTaskExecutor {

    /** 初始状态中的任务 ID 变量。 */
    public static final String TASK_ID_VARIABLE = "benchmark.taskId";
    /** 初始状态中的任务类别变量。 */
    public static final String CATEGORY_VARIABLE = "benchmark.category";
    /** 初始状态中的任务提示变量。 */
    public static final String PROMPT_VARIABLE = "benchmark.prompt";
    /** 初始状态中的成功标准变量。 */
    public static final String SUCCESS_CRITERIA_VARIABLE = "benchmark.successCriteria";

    private final AgentRunService runService;
    private final String graphId;
    private final BenchmarkSuccessEvaluator successEvaluator;
    private final Function<UUID, Optional<Instant>> firstTokenSource;

    /** 创建不提供首事件时间源的适配器。 */
    public AgentRunBenchmarkExecutor(AgentRunService runService, String graphId,
                                     BenchmarkSuccessEvaluator successEvaluator) {
        this(runService, graphId, successEvaluator, ignored -> Optional.empty());
    }

    /** 创建带首事件时间源的适配器。 */
    public AgentRunBenchmarkExecutor(AgentRunService runService, String graphId,
                                     BenchmarkSuccessEvaluator successEvaluator,
                                     Function<UUID, Optional<Instant>> firstTokenSource) {
        this.runService = Objects.requireNonNull(runService, "runService 不能为空");
        if (graphId == null || graphId.isBlank()) {
            throw new IllegalArgumentException("graphId 不能为空");
        }
        this.graphId = graphId;
        this.successEvaluator = Objects.requireNonNull(
                successEvaluator, "successEvaluator 不能为空");
        this.firstTokenSource = Objects.requireNonNull(
                firstTokenSource, "firstTokenSource 不能为空");
    }

    @Override
    public BenchmarkTaskResult execute(BenchmarkTask task, int repetition, Duration timeout) {
        Objects.requireNonNull(task, "task 不能为空");
        Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
        Instant startedAt = Instant.now();
        try {
            AgentState initialState = AgentState.empty()
                    .withVariable(TASK_ID_VARIABLE, task.id())
                    .withVariable(CATEGORY_VARIABLE, task.category())
                    .withVariable(PROMPT_VARIABLE, task.prompt())
                    .withVariable(SUCCESS_CRITERIA_VARIABLE, task.successCriteria());
            RunCheckpoint created = runService.start(graphId, initialState);
            RunCheckpoint terminal = awaitTerminal(created.runId(), timeout);
            Instant finishedAt = terminal.createdAt();
            Optional<Instant> firstTokenAt = firstTokenSource.apply(created.runId());
            boolean passed = terminal.status() == RunStatus.COMPLETED
                    && successEvaluator.passed(task, terminal);
            if (!passed) {
                String failure = terminal.error();
                if (failure == null) {
                    failure = "Benchmark 成功评估器判定任务未通过: " + task.id();
                }
                return new BenchmarkTaskResult(task.id(), repetition, false, startedAt,
                        firstTokenAt, finishedAt, failure);
            }
            return new BenchmarkTaskResult(task.id(), repetition, true, startedAt,
                    firstTokenAt, finishedAt, null);
        } catch (Throwable throwable) {
            Instant finishedAt = Instant.now();
            if (finishedAt.isBefore(startedAt)) {
                finishedAt = startedAt;
            }
            return new BenchmarkTaskResult(task.id(), repetition, false, startedAt,
                    Optional.empty(), finishedAt, stackTrace(throwable));
        }
    }

    private RunCheckpoint awaitTerminal(UUID runId, Duration timeout) {
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("timeout 超出可计算范围", exception);
        }
        long deadline = System.nanoTime() + timeoutNanos;
        while (true) {
            RunCheckpoint checkpoint = runService.get(runId);
            if (checkpoint.status() != RunStatus.RUNNING) {
                return checkpoint;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new IllegalStateException("Agent Run 等待终态超时: " + runId);
            }
            LockSupport.parkNanos(Math.min(Duration.ofMillis(5).toNanos(), timeoutNanos));
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Agent Run 等待终态时被中断");
            }
        }
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
