package com.agent.core.engine;

import com.agent.core.trace.TraceEvent;
import com.agent.core.trace.TraceEventPublisher;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于虚拟线程驱动持久化 Run 生命周期。
 */
public final class AgentRunService implements AutoCloseable {

    private static final Logger LOGGER = System.getLogger(AgentRunService.class.getName());

    private final Checkpointer checkpointer;
    private final GraphRegistry graphRegistry;
    private final TraceEventPublisher tracePublisher;
    private final ExecutorService executor;
    private final ConcurrentMap<UUID, CompletableFuture<Void>> interruptPublications =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ConcurrentMap<Future<?>, Boolean>> activeExecutions =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 创建运行服务。
     *
     * @param checkpointer   权威 Checkpoint 端口
     * @param graphRegistry 图注册表
     * @param tracePublisher Trace 发布端口
     */
    public AgentRunService(
            Checkpointer checkpointer,
            GraphRegistry graphRegistry,
            TraceEventPublisher tracePublisher) {
        this.checkpointer = Objects.requireNonNull(checkpointer, "checkpointer 不能为空");
        this.graphRegistry = Objects.requireNonNull(graphRegistry, "graphRegistry 不能为空");
        this.tracePublisher = Objects.requireNonNull(tracePublisher, "tracePublisher 不能为空");
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("agent-run-", 0).factory());
    }

    /**
     * 创建并异步启动 Run。
     *
     * @param graphId      精确图标识
     * @param initialState 初始不可变状态
     * @return 版本 0 快照
     */
    public RunCheckpoint start(String graphId, AgentState initialState) {
        ensureOpen();
        requireText(graphId, "graphId");
        Objects.requireNonNull(initialState, "initialState 不能为空");

        StateGraph graph = graphRegistry.create(graphId);
        RunCheckpoint created;
        try {
            created = checkpointer.create(
                    UUID.randomUUID(), graphId, initialState, graph.entryPoint());
        } catch (RuntimeException exception) {
            graph.close();
            throw exception;
        }
        dispatch(created, false, graph);
        return created;
    }

    /**
     * 读取 Run 最新权威快照。
     *
     * @param runId Run 标识
     * @return 最新快照
     */
    public RunCheckpoint get(UUID runId) {
        Objects.requireNonNull(runId, "runId 不能为空");
        return checkpointer.loadLatest(runId)
                .orElseThrow(() -> new RunNotFoundException(runId));
    }

    /**
     * 按版本升序读取 Run 的全部权威快照。
     *
     * @param runId Run 标识
     * @return 不可变 Checkpoint 历史
     */
    public List<RunCheckpoint> history(UUID runId) {
        Objects.requireNonNull(runId, "runId 不能为空");
        List<RunCheckpoint> history = checkpointer.loadHistory(runId);
        if (history.isEmpty()) {
            throw new RunNotFoundException(runId);
        }
        return List.copyOf(history);
    }

    /**
     * 取消仍在运行的 Run，并以完整取消堆栈写入权威失败快照。
     *
     * @param runId Run 标识
     * @param reason 取消原因
     * @return 取消后的失败快照，或已存在的非运行快照
     */
    public RunCheckpoint cancel(UUID runId, String reason) {
        ensureOpen();
        Objects.requireNonNull(runId, "runId 不能为空");
        requireText(reason, "reason");
        CancellationException cancellation = new CancellationException(reason);
        String error = stackTrace(cancellation);
        while (true) {
            RunCheckpoint current = get(runId);
            if (current.status() != RunStatus.RUNNING) {
                return current;
            }
            final RunCheckpoint failed;
            try {
                failed = checkpointer.append(new CheckpointAppend(
                        runId,
                        current.version(),
                        RunStatus.FAILED,
                        current.state(),
                        null,
                        null,
                        null,
                        null,
                        error));
            } catch (CheckpointConflictException exception) {
                continue;
            }
            cancelActiveExecutions(runId);
            publish(new TraceEvent.Failed(
                    UUID.randomUUID(), runId, failed.version(), Instant.now(), error));
            return failed;
        }
    }

    /**
     * 批准或拒绝等待中的 Run。
     *
     * @param runId   Run 标识
     * @param command 审批命令
     * @return 审批产生的新版本快照
     */
    public RunCheckpoint decide(UUID runId, ApprovalCommand command) {
        ensureOpen();
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(command, "command 不能为空");
        RunCheckpoint waiting = get(runId);
        if (waiting.status() != RunStatus.WAITING_APPROVAL
                || waiting.version() != command.expectedVersion()) {
            throw new CheckpointConflictException(runId, command.expectedVersion());
        }

        InterruptRequest interrupt = Objects.requireNonNull(
                waiting.interruptRequest(), "等待审批快照缺少 interruptRequest");
        awaitInterruptPublication(runId);
        if (command.decision() == ApprovalDecision.REJECT) {
            RunCheckpoint rejected = checkpointer.append(new CheckpointAppend(
                    runId,
                    command.expectedVersion(),
                    RunStatus.REJECTED,
                    waiting.state(),
                    null,
                    interrupt,
                    ApprovalDecision.REJECT,
                    command.reason(),
                    null));
            publish(new TraceEvent.Rejected(
                    UUID.randomUUID(),
                    runId,
                    rejected.version(),
                    Instant.now(),
                    interrupt.nodeName(),
                    command.reason()));
            return rejected;
        }

        AgentState approvedState = applyVariableUpdates(
                waiting.state(), interrupt, command.variableUpdates());
        RunCheckpoint approved = checkpointer.append(new CheckpointAppend(
                runId,
                command.expectedVersion(),
                RunStatus.RUNNING,
                approvedState,
                waiting.nextNode(),
                null,
                ApprovalDecision.APPROVE,
                command.reason(),
                null));
        publish(new TraceEvent.Approved(
                UUID.randomUUID(),
                runId,
                approved.version(),
                Instant.now(),
                interrupt.nodeName(),
                command.reason()));
        dispatch(approved, true);
        return approved;
    }

    private AgentState applyVariableUpdates(
            AgentState state,
            InterruptRequest interrupt,
            Map<String, String> variableUpdates) {
        AgentState updated = state;
        for (Map.Entry<String, String> entry : variableUpdates.entrySet()) {
            String key = entry.getKey();
            if (!interrupt.details().containsKey(key)) {
                throw new IllegalArgumentException("中断未公开状态变量: " + key);
            }
            if (!state.variables().containsKey(key)) {
                throw new IllegalArgumentException("状态变量不存在: " + key);
            }
            updated = updated.withVariable(key, entry.getValue());
        }
        return updated;
    }

    /** 恢复所有最新状态为 RUNNING 的 Run。 */
    public void recoverRunningRuns() {
        ensureOpen();
        for (RunCheckpoint checkpoint : checkpointer.loadLatestByStatus(RunStatus.RUNNING)) {
            boolean bypass = checkpoint.approvalDecision() == ApprovalDecision.APPROVE;
            dispatch(checkpoint, bypass);
        }
    }

    private void dispatch(RunCheckpoint checkpoint, boolean bypassInterruptAtStart) {
        StateGraph graph;
        try {
            graph = graphRegistry.create(checkpoint.graphId());
        } catch (RuntimeException exception) {
            storeFailure(checkpoint, exception);
            return;
        }
        dispatch(checkpoint, bypassInterruptAtStart, graph);
    }

    private void dispatch(
            RunCheckpoint checkpoint,
            boolean bypassInterruptAtStart,
            StateGraph graph) {
        FutureTask<Void> task = new FutureTask<>(() -> {
            executeCheckpoint(checkpoint, bypassInterruptAtStart, graph);
            return null;
        }) {
            @Override
            protected void done() {
                removeActiveExecution(checkpoint.runId(), this);
            }
        };
        activeExecutions.computeIfAbsent(
                checkpoint.runId(), ignored -> new ConcurrentHashMap<>())
                .put(task, Boolean.TRUE);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException exception) {
            removeActiveExecution(checkpoint.runId(), task);
            graph.close();
            storeFailure(checkpoint, exception);
        }
    }

    private void cancelActiveExecutions(UUID runId) {
        ConcurrentMap<Future<?>, Boolean> executions = activeExecutions.remove(runId);
        if (executions != null) {
            executions.keySet().forEach(execution -> execution.cancel(true));
        }
    }

    private void removeActiveExecution(UUID runId, Future<?> execution) {
        ConcurrentMap<Future<?>, Boolean> executions = activeExecutions.get(runId);
        if (executions != null) {
            executions.remove(execution);
            if (executions.isEmpty()) {
                activeExecutions.remove(runId, executions);
            }
        }
    }

    private void executeCheckpoint(
            RunCheckpoint initial,
            boolean bypassInterruptAtStart,
            StateGraph graph) {
        AtomicReference<RunCheckpoint> current = new AtomicReference<>(initial);
        try (graph) {
            GraphExecutionResult result = graph.execute(
                    new GraphExecutionRequest(
                            initial.runId(),
                            initial.state(),
                            initial.nextNode(),
                            bypassInterruptAtStart),
                    executionListener(current));
            if (result instanceof GraphExecutionResult.Interrupted interrupted) {
                CompletableFuture<Void> publication = new CompletableFuture<>();
                interruptPublications.put(initial.runId(), publication);
                try {
                    RunCheckpoint waiting = appendAndSet(
                            current,
                            new CheckpointAppend(
                                    initial.runId(),
                                    current.get().version(),
                                    RunStatus.WAITING_APPROVAL,
                                    interrupted.state(),
                                    interrupted.nodeName(),
                                    interrupted.request(),
                                    null,
                                    null,
                                    null));
                    publish(new TraceEvent.Interrupted(
                            UUID.randomUUID(),
                            initial.runId(),
                            waiting.version(),
                            Instant.now(),
                            interrupted.nodeName(),
                            interrupted.request()));
                    publication.complete(null);
                } catch (RuntimeException exception) {
                    publication.completeExceptionally(exception);
                    throw exception;
                } finally {
                    interruptPublications.remove(initial.runId(), publication);
                }
            }
        } catch (RuntimeException exception) {
            storeFailure(current.get(), exception);
        }
    }

    private GraphExecutionListener executionListener(
            AtomicReference<RunCheckpoint> current) {
        return new GraphExecutionListener() {
            @Override
            public void onNodeStarted(String nodeName, AgentState state) {
                RunCheckpoint checkpoint = current.get();
                publish(new TraceEvent.NodeStarted(
                        UUID.randomUUID(),
                        checkpoint.runId(),
                        checkpoint.version(),
                        Instant.now(),
                        nodeName));
            }

            @Override
            public void onNodeCompleted(
                    String nodeName,
                    String nextNode,
                    AgentState state) {
                RunCheckpoint previous = current.get();
                boolean completed = StateGraph.END.equals(nextNode);
                RunCheckpoint updated = appendAndSet(
                        current,
                        new CheckpointAppend(
                                previous.runId(),
                                previous.version(),
                                completed ? RunStatus.COMPLETED : RunStatus.RUNNING,
                                state,
                                completed ? null : nextNode,
                                null,
                                null,
                                null,
                                null));
                publish(new TraceEvent.NodeCompleted(
                        UUID.randomUUID(),
                        updated.runId(),
                        updated.version(),
                        Instant.now(),
                        nodeName,
                        nextNode));
                if (completed) {
                    publish(new TraceEvent.Completed(
                            UUID.randomUUID(),
                            updated.runId(),
                            updated.version(),
                            Instant.now()));
                }
            }
        };
    }

    private RunCheckpoint appendAndSet(
            AtomicReference<RunCheckpoint> current,
            CheckpointAppend append) {
        RunCheckpoint updated = checkpointer.append(append);
        current.set(updated);
        return updated;
    }

    private void awaitInterruptPublication(UUID runId) {
        CompletableFuture<Void> publication = interruptPublications.get(runId);
        if (publication == null) {
            return;
        }
        try {
            publication.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待中断 Trace 发布被中断", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("中断 Trace 发布失败", exception.getCause());
        }
    }

    private void storeFailure(RunCheckpoint current, RuntimeException exception) {
        String error = stackTrace(exception);
        RunCheckpoint latest = checkpointer.loadLatest(current.runId()).orElse(current);
        if (latest.status() != RunStatus.RUNNING) {
            return;
        }
        try {
            RunCheckpoint failed = checkpointer.append(new CheckpointAppend(
                    latest.runId(),
                    latest.version(),
                    RunStatus.FAILED,
                    latest.state(),
                    null,
                    null,
                    null,
                    null,
                    error));
            publish(new TraceEvent.Failed(
                    UUID.randomUUID(),
                    failed.runId(),
                    failed.version(),
                    Instant.now(),
                    error));
        } catch (RuntimeException persistenceFailure) {
            RunCheckpoint concurrent = checkpointer.loadLatest(current.runId()).orElse(current);
            if (concurrent.status() != RunStatus.RUNNING) {
                return;
            }
            persistenceFailure.addSuppressed(exception);
            LOGGER.log(Level.ERROR, "无法保存 Run 失败 Checkpoint", persistenceFailure);
        }
    }

    private void publish(TraceEvent event) {
        try {
            tracePublisher.publish(event);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.ERROR, "Trace 事件发布失败: " + event.type(), exception);
        }
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("AgentRunService 已关闭");
        }
    }

    /** 停止接收新任务并等待已提交的虚拟线程任务结束。 */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.close();
        }
    }
}
