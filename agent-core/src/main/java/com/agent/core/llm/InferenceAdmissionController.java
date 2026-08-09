package com.agent.core.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 以端点为边界执行并发和最近一分钟速率准入。 */
public final class InferenceAdmissionController {

    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private final InferenceBudget budget;
    private final Clock clock;
    private final Semaphore concurrentPermits;
    private final Object rateMonitor = new Object();
    private final Deque<Instant> admittedRequests = new ArrayDeque<>();
    private final AtomicLong concurrencyRejections = new AtomicLong();
    private final AtomicLong rateLimitRejections = new AtomicLong();

    /** 使用系统 UTC 时钟创建控制器。 */
    public InferenceAdmissionController(InferenceBudget budget) {
        this(budget, Clock.systemUTC());
    }

    /** 使用指定时钟创建控制器。 */
    public InferenceAdmissionController(InferenceBudget budget, Clock clock) {
        this.budget = Objects.requireNonNull(budget, "budget 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.concurrentPermits = new Semaphore(
                budget.maxConcurrentRequests(), true);
    }

    /** 创建兼容已有端点的不限制控制器。 */
    public static InferenceAdmissionController unlimited() {
        return new InferenceAdmissionController(InferenceBudget.unlimited());
    }

    /** 在预算范围内获取一次请求许可。 */
    public InferencePermit acquire() {
        boolean acquired;
        try {
            acquired = concurrentPermits.tryAcquire(
                    budget.queueTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            concurrencyRejections.incrementAndGet();
            throw new InferenceAdmissionException(
                    InferenceRejectionReason.CONCURRENCY_LIMIT,
                    "等待推理端点并发许可时被中断");
        }
        if (!acquired) {
            concurrencyRejections.incrementAndGet();
            throw new InferenceAdmissionException(
                    InferenceRejectionReason.CONCURRENCY_LIMIT,
                    "推理端点并发请求已达到上限");
        }

        try {
            admitRate(clock.instant());
        } catch (RuntimeException exception) {
            concurrentPermits.release();
            throw exception;
        }

        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                concurrentPermits.release();
            }
        };
    }

    /** 返回当前端点准入指标。 */
    public InferenceAdmissionSnapshot snapshot() {
        int requestsInWindow;
        synchronized (rateMonitor) {
            pruneExpired(clock.instant());
            requestsInWindow = admittedRequests.size();
        }
        return new InferenceAdmissionSnapshot(
                budget.maxConcurrentRequests() - concurrentPermits.availablePermits(),
                requestsInWindow,
                concurrencyRejections.get(),
                rateLimitRejections.get());
    }

    private void admitRate(Instant now) {
        if (budget.maxRequestsPerMinute() == Integer.MAX_VALUE) {
            return;
        }
        synchronized (rateMonitor) {
            pruneExpired(now);
            if (admittedRequests.size() >= budget.maxRequestsPerMinute()) {
                rateLimitRejections.incrementAndGet();
                throw new InferenceAdmissionException(
                        InferenceRejectionReason.RATE_LIMIT,
                        "推理端点最近一分钟请求数已达到上限");
            }
            admittedRequests.addLast(now);
        }
    }

    private void pruneExpired(Instant now) {
        Instant cutoff = now.minus(RATE_WINDOW);
        while (!admittedRequests.isEmpty()
                && !admittedRequests.getFirst().isAfter(cutoff)) {
            admittedRequests.removeFirst();
        }
    }
}
