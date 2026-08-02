package com.agent.web.terminal;

import com.agent.core.engine.Checkpointer;
import com.agent.core.engine.RunCheckpoint;
import com.agent.web.log.InMemoryRunLogEventBus;
import com.agent.web.log.RunLogSubscription;
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

/** 将终端快照和实时日志推送到 WebSocket。 */
public final class RunTerminalWebSocketHandler implements WebSocketHandler {

    private static final String RUN_ID_VARIABLE = "runId";
    private static final PathPattern TERMINAL_PATH = new PathPatternParser()
            .parse("/ws/runs/{runId}/terminal");
    private static final CloseStatus RUN_NOT_FOUND =
            new CloseStatus(4404, "run not found");

    private final Checkpointer checkpointer;
    private final InMemoryRunLogEventBus eventBus;
    private final ObjectMapper objectMapper;

    /** 创建 Run 终端 WebSocket Handler。 */
    public RunTerminalWebSocketHandler(
            Checkpointer checkpointer,
            InMemoryRunLogEventBus eventBus,
            ObjectMapper objectMapper) {
        this.checkpointer = Objects.requireNonNull(checkpointer, "checkpointer 不能为空");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /** 先发送权威终端快照，再发送同一 Run 的实时日志。 */
    @Override
    public Mono<Void> handle(WebSocketSession session) {
        UUID runId = extractRunId(session);
        RunLogSubscription subscription = eventBus.openSubscription(runId);
        RunCheckpoint checkpoint;
        TerminalSnapshot terminal;
        try {
            checkpoint = checkpointer.loadLatest(runId).orElse(null);
            terminal = checkpoint == null ? null : TerminalSnapshot.from(checkpoint);
        } catch (RuntimeException exception) {
            subscription.close();
            throw exception;
        }
        if (checkpoint == null) {
            subscription.close();
            return session.close(RUN_NOT_FOUND);
        }

        Flux<TerminalFrame> frames = Flux.concat(
                Mono.just(TerminalFrame.snapshot(terminal)),
                subscription.events().map(TerminalFrame::log));
        return session.send(frames.map(frame ->
                        session.textMessage(writeJson(frame))))
                .doFinally(signal -> subscription.close());
    }

    private UUID extractRunId(WebSocketSession session) {
        String path = session.getHandshakeInfo().getUri().getPath();
        PathPattern.PathMatchInfo match = TERMINAL_PATH.matchAndExtract(
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
            throw new IllegalStateException("终端 WebSocket JSON 序列化失败", exception);
        }
    }
}
