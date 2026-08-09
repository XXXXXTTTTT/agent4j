package com.agent.core.multiagent;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.GraphExecutionListener;
import com.agent.core.engine.GraphExecutionRequest;
import com.agent.core.engine.GraphExecutionResult;
import com.agent.core.engine.GraphRegistry;
import com.agent.core.engine.StateGraph;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 使用虚拟线程执行受目录、预算和状态所有权约束的 Agent 子运行。 */
public final class AgentHandoffExecutor implements AutoCloseable {

    private final AgentCatalog catalog;
    private final GraphRegistry graphRegistry;
    private final AgentHandoffEventPublisher eventPublisher;
    private final AgentStateProjector projector;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public AgentHandoffExecutor(
            AgentCatalog catalog,
            GraphRegistry graphRegistry,
            AgentHandoffEventPublisher eventPublisher) {
        this.catalog = Objects.requireNonNull(catalog, "catalog 不能为空");
        this.graphRegistry = Objects.requireNonNull(graphRegistry, "graphRegistry 不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher 不能为空");
        this.projector = new AgentStateProjector();
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("agent-handoff-", 0).factory());
    }

    public CompletableFuture<AgentHandoffResult> execute(
            UUID parentRunId,
            AgentState parentState,
            AgentHandoff handoff,
            HandoffExecutionContext context) {
        ensureOpen();
        Objects.requireNonNull(parentRunId, "parentRunId 不能为空");
        Objects.requireNonNull(parentState, "parentState 不能为空");
        Objects.requireNonNull(handoff, "handoff 不能为空");
        Objects.requireNonNull(context, "context 不能为空");

        AgentDescriptor source = catalog.require(handoff.fromAgent());
        AgentDescriptor target = catalog.require(handoff.toAgent());
        validateAuthorization(source, target, handoff, context);
        HandoffExecutionContext childContext = context.descend(target.agentId());
        AgentState initialChildState = projector.project(parentState, target, handoff);
        UUID childRunId = distinctRunId(parentRunId);

        CompletableFuture<AgentHandoffResult> result = new CompletableFuture<>();
        final Future<AgentHandoffResult> childTask;
        try {
            childTask = executor.submit(() -> runChild(
                    parentRunId,
                    childRunId,
                    parentState,
                    initialChildState,
                    source,
                    target,
                    handoff,
                    childContext));
            executor.execute(() -> awaitChild(
                    parentRunId, childRunId, source, target, handoff, childTask, result));
        } catch (RejectedExecutionException exception) {
            throw new IllegalStateException("Handoff 执行器已拒绝任务", exception);
        }
        return result;
    }

    private AgentHandoffResult runChild(
            UUID parentRunId,
            UUID childRunId,
            AgentState parentState,
            AgentState initialChildState,
            AgentDescriptor source,
            AgentDescriptor target,
            AgentHandoff handoff,
            HandoffExecutionContext childContext) {
        long startedAt = System.nanoTime();
        publish(new AgentHandoffEvent.Started(
                handoff.taskId(),
                parentRunId,
                childRunId,
                source.agentId(),
                target.agentId(),
                Instant.now()));

        GraphExecutionResult executionResult;
        try (StateGraph graph = graphRegistry.create(target.graphId())) {
            executionResult = graph.execute(
                    new GraphExecutionRequest(
                            childRunId, initialChildState, graph.entryPoint(), false),
                    listener(parentRunId, childRunId, source, target, handoff));
        }
        if (executionResult instanceof GraphExecutionResult.Interrupted interrupted) {
            throw new AgentHandoffInterruptedException(
                    handoff.taskId(), childRunId, interrupted.nodeName());
        }
        AgentState childState = ((GraphExecutionResult.Completed) executionResult).state();
        AgentState merged = projector.merge(
                parentState, initialChildState, childState, target, handoff, childRunId);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        publish(new AgentHandoffEvent.Completed(
                handoff.taskId(),
                parentRunId,
                childRunId,
                source.agentId(),
                target.agentId(),
                Instant.now(),
                elapsed));
        return new AgentHandoffResult(
                handoff.taskId(),
                parentRunId,
                childRunId,
                source.agentId(),
                target.agentId(),
                childState,
                merged,
                childContext,
                elapsed);
    }

    private GraphExecutionListener listener(
            UUID parentRunId,
            UUID childRunId,
            AgentDescriptor source,
            AgentDescriptor target,
            AgentHandoff handoff) {
        return new GraphExecutionListener() {
            @Override
            public void onNodeStarted(String nodeName, AgentState state) {
                publish(new AgentHandoffEvent.NodeStarted(
                        handoff.taskId(), parentRunId, childRunId,
                        source.agentId(), target.agentId(), Instant.now(), nodeName));
            }

            @Override
            public void onNodeProgress(String nodeName, String summary) {
                publish(new AgentHandoffEvent.NodeProgress(
                        handoff.taskId(), parentRunId, childRunId,
                        source.agentId(), target.agentId(), Instant.now(), nodeName, summary));
            }

            @Override
            public void onNodeCompleted(
                    String nodeName,
                    String nextNode,
                    AgentState state) {
                publish(new AgentHandoffEvent.NodeCompleted(
                        handoff.taskId(), parentRunId, childRunId,
                        source.agentId(), target.agentId(), Instant.now(), nodeName, nextNode));
            }
        };
    }

    private void awaitChild(
            UUID parentRunId,
            UUID childRunId,
            AgentDescriptor source,
            AgentDescriptor target,
            AgentHandoff handoff,
            Future<AgentHandoffResult> childTask,
            CompletableFuture<AgentHandoffResult> result) {
        try {
            AgentHandoffResult completed = childTask.get(
                    handoff.timeout().toNanos(), TimeUnit.NANOSECONDS);
            result.complete(completed);
        } catch (TimeoutException exception) {
            childTask.cancel(true);
            completeFailure(
                    parentRunId,
                    childRunId,
                    source,
                    target,
                    handoff,
                    new AgentHandoffTimeoutException(
                            handoff.taskId(), childRunId, handoff.timeout()),
                    result);
        } catch (InterruptedException exception) {
            childTask.cancel(true);
            Thread.currentThread().interrupt();
            completeFailure(
                    parentRunId,
                    childRunId,
                    source,
                    target,
                    handoff,
                    new AgentHandoffExecutionException(
                            handoff.taskId(), childRunId, target.agentId(), exception),
                    result);
        } catch (ExecutionException exception) {
            completeFailure(
                    parentRunId,
                    childRunId,
                    source,
                    target,
                    handoff,
                    mapFailure(handoff, childRunId, target, exception.getCause()),
                    result);
        }
    }

    private RuntimeException mapFailure(
            AgentHandoff handoff,
            UUID childRunId,
            AgentDescriptor target,
            Throwable failure) {
        if (failure instanceof AgentHandoffInterruptedException interrupted) {
            return interrupted;
        }
        if (failure instanceof AgentHandoffStateException stateFailure) {
            return stateFailure;
        }
        if (failure instanceof AgentStateMergeException mergeFailure) {
            return mergeFailure;
        }
        return new AgentHandoffExecutionException(
                handoff.taskId(), childRunId, target.agentId(), failure);
    }

    private void completeFailure(
            UUID parentRunId,
            UUID childRunId,
            AgentDescriptor source,
            AgentDescriptor target,
            AgentHandoff handoff,
            RuntimeException failure,
            CompletableFuture<AgentHandoffResult> result) {
        try {
            publish(new AgentHandoffEvent.Failed(
                    handoff.taskId(),
                    parentRunId,
                    childRunId,
                    source.agentId(),
                    target.agentId(),
                    Instant.now(),
                    stackTrace(failure)));
        } catch (RuntimeException publicationFailure) {
            failure.addSuppressed(publicationFailure);
        }
        result.completeExceptionally(failure);
    }

    private void validateAuthorization(
            AgentDescriptor source,
            AgentDescriptor target,
            AgentHandoff handoff,
            HandoffExecutionContext context) {
        String contextSource = context.visitedAgents().getLast();
        if (!source.agentId().equals(contextSource)) {
            throw new AgentHandoffDeniedException(
                    source.agentId(),
                    target.agentId(),
                    "Handoff 来源与访问链末项不一致");
        }
        if (!source.handoffTargets().contains(target.agentId())) {
            throw new AgentHandoffDeniedException(
                    source.agentId(),
                    target.agentId(),
                    "Handoff 目标不在来源 Agent 白名单: " + target.agentId());
        }
        for (String outputKey : handoff.requestedOutputKeys()) {
            if (!target.ownedStateKeys().contains(outputKey)) {
                throw new AgentHandoffStateException(
                        outputKey, "请求输出键不属于目标 Agent: " + outputKey);
            }
        }
    }

    private UUID distinctRunId(UUID parentRunId) {
        UUID childRunId;
        do {
            childRunId = UUID.randomUUID();
        } while (parentRunId.equals(childRunId));
        return childRunId;
    }

    private void publish(AgentHandoffEvent event) {
        eventPublisher.publish(event);
    }

    private String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Handoff 执行器已经关闭");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.close();
        }
    }
}
