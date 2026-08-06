package com.agent.web.observability;

import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.llm.TaskType;
import com.agent.core.observability.ModelCallObserver;
import com.agent.core.observability.ModelCallSpan;
import com.agent.core.observability.ModelCallStart;
import com.agent.core.observability.ModelCallSuccess;
import com.agent.core.observability.ModelUsage;
import com.agent.core.trace.TraceEvent;
import com.agent.core.trace.TraceEventPublisher;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 将 Run 生命周期和模型调用转换为显式父子 OpenTelemetry Span。 */
public final class OpenTelemetryRunTracePublisher
        implements TraceEventPublisher, ModelCallObserver, AutoCloseable {

    private static final AttributeKey<String> TRACE_NAME =
            AttributeKey.stringKey("langfuse.trace.name");
    private static final AttributeKey<String> SESSION_ID =
            AttributeKey.stringKey("langfuse.session.id");
    private static final AttributeKey<String> RUN_ID =
            AttributeKey.stringKey("agent.run.id");
    private static final AttributeKey<Long> CHECKPOINT_VERSION =
            AttributeKey.longKey("agent.checkpoint.version");
    private static final AttributeKey<String> TRACE_CHECKPOINT_VERSION =
            AttributeKey.stringKey("langfuse.trace.metadata.checkpoint_version");
    private static final AttributeKey<String> NODE_NAME =
            AttributeKey.stringKey("agent.node.name");
    private static final AttributeKey<String> NEXT_NODE =
            AttributeKey.stringKey("agent.next_node");
    private static final AttributeKey<String> PROGRESS_SUMMARY =
            AttributeKey.stringKey("agent.progress.summary");
    private static final AttributeKey<String> OBSERVATION_TYPE =
            AttributeKey.stringKey("langfuse.observation.type");
    private static final AttributeKey<String> OPERATION_NAME =
            AttributeKey.stringKey("gen_ai.operation.name");
    private static final AttributeKey<String> REQUEST_MODEL =
            AttributeKey.stringKey("gen_ai.request.model");
    private static final AttributeKey<String> RESPONSE_MODEL =
            AttributeKey.stringKey("gen_ai.response.model");
    private static final AttributeKey<Long> INPUT_TOKENS =
            AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long> OUTPUT_TOKENS =
            AttributeKey.longKey("gen_ai.usage.output_tokens");
    private static final AttributeKey<Long> TOTAL_TOKENS =
            AttributeKey.longKey("agent.model.total_tokens");
    private static final AttributeKey<String> MODEL_ENDPOINT =
            AttributeKey.stringKey("agent.model.endpoint");
    private static final AttributeKey<String> MODEL_TASK_TYPE =
            AttributeKey.stringKey("agent.model.task_type");
    private static final AttributeKey<String> ERROR_MESSAGE =
            AttributeKey.stringKey("error.message");

    private final Tracer tracer;
    private final ConcurrentMap<UUID, RunSpanState> runStates = new ConcurrentHashMap<>();
    private final ConcurrentMap<GenerationSpan, Boolean> generations = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 注入 OTel Tracer。 */
    public OpenTelemetryRunTracePublisher(Tracer tracer) {
        this.tracer = java.util.Objects.requireNonNull(tracer, "tracer 不能为空");
    }

    /** 发布一个强类型 Run 生命周期事件。 */
    @Override
    public void publish(TraceEvent event) {
        ensureOpen();
        java.util.Objects.requireNonNull(event, "event 不能为空");
        RunSpanState state = runStates.computeIfAbsent(
                event.runId(), ignored -> new RunSpanState(event.runId()));
        synchronized (state) {
            state.accept(event);
        }
    }

    /** 开始一个模型端点 Generation Span。 */
    @Override
    public ModelCallSpan start(ModelCallStart start) {
        ensureOpen();
        java.util.Objects.requireNonNull(start, "start 不能为空");
        Span parent = null;
        Optional<NodeExecutionContext> context = start.nodeContext();
        if (context.isPresent()) {
            NodeExecutionContext nodeContext = context.orElseThrow();
            RunSpanState state = runStates.get(nodeContext.runId());
            if (state == null) {
                throw new IllegalStateException("模型调用 Run 上下文不存在: " + nodeContext.runId());
            }
            synchronized (state) {
                parent = state.activeNode(nodeContext.nodeName());
            }
            if (parent == null) {
                throw new IllegalStateException("模型调用节点上下文不存在: " + nodeContext.nodeName());
            }
        }

        SpanBuilder builder = tracer.spanBuilder("chat " + start.requestedModel())
                .setParent(parent == null
                        ? Context.root()
                        : Context.root().with(parent));
        Span span = builder.startSpan();
        span.setAttribute(OBSERVATION_TYPE, "generation");
        span.setAttribute(OPERATION_NAME, "chat");
        span.setAttribute(REQUEST_MODEL, start.requestedModel());
        span.setAttribute(MODEL_ENDPOINT, start.endpointName());
        span.setAttribute(MODEL_TASK_TYPE, start.taskType().name());
        GenerationSpan generation = new GenerationSpan(span);
        generations.put(generation, Boolean.TRUE);
        return generation;
    }

    /** 关闭发布器并结束所有仍活动的 Span。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        generations.keySet().forEach(GenerationSpan::forceClose);
        runStates.values().forEach(state -> {
            synchronized (state) {
                state.forceClose();
            }
        });
        generations.clear();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("OpenTelemetryRunTracePublisher 已关闭");
        }
    }

    private final class RunSpanState {
        private final UUID runId;
        private Span runSpan;
        private Span nodeSpan;
        private String nodeName;

        private RunSpanState(UUID runId) {
            this.runId = runId;
        }

        private void accept(TraceEvent event) {
            switch (event) {
                case TraceEvent.NodeStarted started -> nodeStarted(started);
                case TraceEvent.NodeProgress progress -> nodeProgress(progress);
                case TraceEvent.NodeCompleted completed -> nodeCompleted(completed);
                case TraceEvent.Interrupted interrupted -> interrupted(interrupted);
                case TraceEvent.Approved approved -> approved(approved);
                case TraceEvent.Rejected rejected -> rejected(rejected);
                case TraceEvent.Failed failed -> failed(failed);
                case TraceEvent.Completed completed -> completed(completed);
            }
        }

        private void nodeStarted(TraceEvent.NodeStarted event) {
            ensureRun(event.checkpointVersion(), event.occurredAt());
            if (nodeSpan != null) {
                throw new IllegalStateException("节点已开始: " + nodeName);
            }
            nodeName = event.nodeName();
            nodeSpan = tracer.spanBuilder("agent.node " + nodeName)
                    .setParent(Context.root().with(runSpan))
                    .setStartTimestamp(event.occurredAt())
                    .startSpan();
            nodeSpan.setAttribute(NODE_NAME, nodeName);
            nodeSpan.setAttribute(CHECKPOINT_VERSION, event.checkpointVersion());
        }

        private void nodeCompleted(TraceEvent.NodeCompleted event) {
            requireRun();
            requireNode(event.nodeName());
            nodeSpan.setAttribute(NEXT_NODE, event.nextNode());
            nodeSpan.end(event.occurredAt());
            nodeSpan = null;
            nodeName = null;
        }

        private void nodeProgress(TraceEvent.NodeProgress event) {
            requireRun();
            requireNode(event.nodeName());
            nodeSpan.addEvent(
                    "agent.node.progress",
                    Attributes.of(PROGRESS_SUMMARY, event.summary()),
                    event.occurredAt());
        }

        private void interrupted(TraceEvent.Interrupted event) {
            requireRun();
            requireNode(event.nodeName());
            nodeSpan.end(event.occurredAt());
            nodeSpan = null;
            nodeName = null;
            runSpan.end(event.occurredAt());
            runSpan = null;
        }

        private void approved(TraceEvent.Approved event) {
            if (runSpan != null) {
                throw new IllegalStateException("审批批准时 Run 仍处于活动状态");
            }
            ensureRun(event.checkpointVersion(), event.occurredAt());
            runSpan.setAttribute("agent.approval.reason", event.reason());
        }

        private void rejected(TraceEvent.Rejected event) {
            if (runSpan == null) {
                ensureRun(event.checkpointVersion(), event.occurredAt());
            }
            if (nodeSpan != null) {
                nodeSpan.setStatus(StatusCode.ERROR, event.reason());
                nodeSpan.end(event.occurredAt());
                nodeSpan = null;
                nodeName = null;
            }
            runSpan.setStatus(StatusCode.ERROR, event.reason());
            runSpan.setAttribute("agent.rejection.reason", event.reason());
            runSpan.end(event.occurredAt());
            runSpan = null;
        }

        private void failed(TraceEvent.Failed event) {
            requireRun();
            markError(nodeSpan, event.error(), event.occurredAt());
            nodeSpan = null;
            nodeName = null;
            markError(runSpan, event.error(), event.occurredAt());
            runSpan = null;
        }

        private void completed(TraceEvent.Completed event) {
            requireRun();
            if (nodeSpan != null) {
                throw new IllegalStateException("Run 完成时节点仍处于活动状态: " + nodeName);
            }
            runSpan.end(event.occurredAt());
            runSpan = null;
        }

        private void ensureRun(long checkpointVersion, Instant occurredAt) {
            if (runSpan != null) {
                return;
            }
            runSpan = tracer.spanBuilder("agent.run")
                    .setParent(Context.root())
                    .setStartTimestamp(occurredAt)
                    .startSpan();
            runSpan.setAttribute(TRACE_NAME, "agent.run");
            runSpan.setAttribute(SESSION_ID, runId.toString());
            runSpan.setAttribute(RUN_ID, runId.toString());
            runSpan.setAttribute(CHECKPOINT_VERSION, checkpointVersion);
            runSpan.setAttribute(TRACE_CHECKPOINT_VERSION,
                    Long.toString(checkpointVersion));
        }

        private void requireRun() {
            if (runSpan == null) {
                throw new IllegalStateException("Run 尚未开始: " + runId);
            }
        }

        private void requireNode(String expectedNode) {
            if (nodeSpan == null) {
                throw new IllegalStateException("节点尚未开始: " + expectedNode);
            }
            if (!nodeName.equals(expectedNode)) {
                throw new IllegalStateException("节点名称不一致: " + expectedNode);
            }
        }

        private Span activeNode(String expectedNode) {
            if (nodeSpan == null || !nodeName.equals(expectedNode)) {
                return null;
            }
            return nodeSpan;
        }

        private void forceClose() {
            if (nodeSpan != null) {
                markError(nodeSpan, "publisher closed", Instant.now());
                nodeSpan = null;
                nodeName = null;
            }
            if (runSpan != null) {
                markError(runSpan, "publisher closed", Instant.now());
                runSpan = null;
            }
        }
    }

    private final class GenerationSpan implements ModelCallSpan {
        private final Span span;
        private GenerationState state = GenerationState.ACTIVE;

        private GenerationSpan(Span span) {
            this.span = span;
        }

        @Override
        public synchronized void succeed(ModelCallSuccess success) {
            requireActive();
            java.util.Objects.requireNonNull(success, "success 不能为空");
            success.responseModel().ifPresent(model -> span.setAttribute(RESPONSE_MODEL, model));
            success.usage().ifPresent(this::setUsage);
            state = GenerationState.SUCCEEDED;
        }

        @Override
        public synchronized void fail(Throwable failure) {
            requireActive();
            java.util.Objects.requireNonNull(failure, "failure 不能为空");
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR, failure.toString());
            state = GenerationState.FAILED;
        }

        @Override
        public synchronized void close() {
            if (state == GenerationState.CLOSED) {
                throw new IllegalStateException("模型 Generation Span 已关闭");
            }
            span.end();
            state = GenerationState.CLOSED;
            generations.remove(this);
        }

        private synchronized void forceClose() {
            if (state == GenerationState.CLOSED) {
                return;
            }
            span.setStatus(StatusCode.ERROR, "publisher closed");
            span.end();
            state = GenerationState.CLOSED;
            generations.remove(this);
        }

        private void setUsage(ModelUsage usage) {
            span.setAttribute(INPUT_TOKENS, (long) usage.promptTokens());
            span.setAttribute(OUTPUT_TOKENS, (long) usage.completionTokens());
            span.setAttribute(TOTAL_TOKENS, (long) usage.totalTokens());
        }

        private void requireActive() {
            if (state != GenerationState.ACTIVE) {
                throw new IllegalStateException("模型 Generation Span 已终结");
            }
        }
    }

    private static void markError(Span span, String error, Instant occurredAt) {
        span.setStatus(StatusCode.ERROR, error);
        span.setAttribute(ERROR_MESSAGE, error);
        span.end(occurredAt);
    }

    private enum GenerationState {
        ACTIVE,
        SUCCEEDED,
        FAILED,
        CLOSED
    }
}
