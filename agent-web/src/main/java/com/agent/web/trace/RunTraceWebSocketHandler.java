package com.agent.web.trace;

import com.agent.core.engine.Checkpointer;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.trace.TraceEvent;
import com.agent.web.controller.RunView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.server.PathContainer;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.UUID;

/** 将当前 Run 快照和实时 Trace 推送到 WebSocket。 */
public final class RunTraceWebSocketHandler implements WebSocketHandler {

    private static final String RUN_ID_VARIABLE = "runId";
    private static final PathPattern TRACE_PATH = new PathPatternParser()
            .parse("/ws/runs/{runId}/trace");
    private static final CloseStatus RUN_NOT_FOUND =
            new CloseStatus(4404, "run not found");

    private final Checkpointer checkpointer;
    private final InMemoryTraceEventBus eventBus;
    private final ObjectMapper objectMapper;

    /** 创建 Run Trace WebSocket Handler。 */
    public RunTraceWebSocketHandler(
            Checkpointer checkpointer,
            InMemoryTraceEventBus eventBus,
            ObjectMapper objectMapper) {
        this.checkpointer = Objects.requireNonNull(checkpointer, "checkpointer 不能为空");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /** 先发送权威快照，再发送同一 Run 的实时事件。 */
    @Override
    public Mono<Void> handle(WebSocketSession session) {
        UUID runId = extractRunId(session);
        InMemoryTraceEventBus.TraceSubscription subscription =
                eventBus.openSubscription(runId);
        RunCheckpoint checkpoint;
        try {
            checkpoint = checkpointer.loadLatest(runId).orElse(null);
        } catch (RuntimeException exception) {
            subscription.close();
            throw exception;
        }
        if (checkpoint == null) {
            subscription.close();
            return session.close(RUN_NOT_FOUND);
        }

        Flux<Object> frames = Flux.concat(
                Mono.just(new TraceSnapshotFrame("SNAPSHOT", RunView.from(checkpoint))),
                subscription.events()
                        .map(event -> new TraceEventFrame("EVENT", event)));
        return session.send(frames.map(frame -> session.textMessage(writeJson(frame))))
                .doFinally(signal -> subscription.close());
    }

    private UUID extractRunId(WebSocketSession session) {
        String path = session.getHandshakeInfo().getUri().getPath();
        PathPattern.PathMatchInfo match = TRACE_PATH.matchAndExtract(
                PathContainer.parsePath(path));
        if (match == null) {
            throw new IllegalArgumentException("WebSocket 路径不匹配: " + path);
        }
        return UUID.fromString(match.getUriVariables().get(RUN_ID_VARIABLE));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Trace WebSocket JSON 序列化失败", exception);
        }
    }

    /** 当前 Run 快照帧。 */
    public record TraceSnapshotFrame(String kind, RunView run) {

        /** 校验快照帧。 */
        public TraceSnapshotFrame {
            if (!"SNAPSHOT".equals(kind)) {
                throw new IllegalArgumentException("kind 必须为 SNAPSHOT");
            }
            Objects.requireNonNull(run, "run 不能为空");
        }
    }

    /** Run 实时事件帧。 */
    public record TraceEventFrame(String kind, TraceEvent event) {

        /** 校验事件帧。 */
        public TraceEventFrame {
            if (!"EVENT".equals(kind)) {
                throw new IllegalArgumentException("kind 必须为 EVENT");
            }
            Objects.requireNonNull(event, "event 不能为空");
        }
    }
}
