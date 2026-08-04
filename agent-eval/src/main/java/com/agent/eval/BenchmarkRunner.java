package com.agent.eval;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** 使用 Java 21 虚拟线程执行有界并发 Benchmark。 */
public final class BenchmarkRunner implements AutoCloseable {

    private final BenchmarkTaskExecutor taskExecutor;
    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();

    public BenchmarkRunner(BenchmarkTaskExecutor taskExecutor) {
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor 不能为空");
    }

    /** 按任务集顺序提交每个任务的恰好 repetitions 次执行。 */
    public BenchmarkReport run(BenchmarkRunRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        if (closed.get()) {
            throw new IllegalStateException("BenchmarkRunner 已关闭");
        }
        Semaphore permits = new Semaphore(request.maxConcurrency());
        List<Future<BenchmarkTaskResult>> futures = new ArrayList<>();
        for (BenchmarkTask task : request.taskSet().tasks()) {
            for (int repetition = 1; repetition <= request.repetitions(); repetition++) {
                int currentRepetition = repetition;
                futures.add(virtualExecutor.submit(() -> executeOne(
                        task, currentRepetition, request, permits)));
            }
        }
        List<BenchmarkTaskResult> results = new ArrayList<>(futures.size());
        for (Future<BenchmarkTaskResult> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 Benchmark 任务时被中断", exception);
            } catch (ExecutionException exception) {
                throw new IllegalStateException("Benchmark 调度失败", exception.getCause());
            }
        }
        return BenchmarkMetrics.calculate(request.taskSet(), request.repetitions(), results);
    }

    private BenchmarkTaskResult executeOne(BenchmarkTask task, int repetition,
                                           BenchmarkRunRequest request, Semaphore permits) {
        try {
            permits.acquire();
            Instant started = Instant.now();
            try {
                BenchmarkTaskResult result = taskExecutor.execute(task, repetition, request.timeout());
                if (result == null) {
                    throw new IllegalStateException("任务执行器返回 null");
                }
                if (!result.taskId().equals(task.id()) || result.repetition() != repetition) {
                    throw new IllegalArgumentException("任务执行器返回了不匹配的结果");
                }
                return result;
            } catch (Throwable throwable) {
                Instant finished = Instant.now();
                if (finished.isBefore(started)) {
                    finished = started;
                }
                return new BenchmarkTaskResult(task.id(), repetition, false, started,
                        java.util.Optional.empty(), finished, stackTrace(throwable));
            } finally {
                permits.release();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            Instant now = Instant.now();
            return new BenchmarkTaskResult(task.id(), repetition, false, now,
                    java.util.Optional.empty(), now, stackTrace(exception));
        }
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter buffer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(buffer));
        return buffer.toString();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            virtualExecutor.close();
        }
    }
}
