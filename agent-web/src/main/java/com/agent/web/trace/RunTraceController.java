package com.agent.web.trace;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.RunCheckpoint;
import com.agent.web.controller.RunView;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.UUID;

/** 提供 Run 权威快照与实时 Trace 事件 SSE。 */
@RestController
@RequestMapping("/api/runs")
public final class RunTraceController {

    private final AgentRunService runService;
    private final InMemoryTraceEventBus eventBus;

    /** 创建 Run Trace SSE Controller。 */
    public RunTraceController(
            AgentRunService runService,
            InMemoryTraceEventBus eventBus) {
        this.runService = Objects.requireNonNull(runService, "runService 不能为空");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus 不能为空");
    }

    /** 先发送权威快照，再发送同一 Run 的实时 Trace 事件。 */
    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> events(@PathVariable UUID runId) {
        InMemoryTraceEventBus.TraceSubscription subscription =
                eventBus.openSubscription(runId);
        RunCheckpoint checkpoint;
        try {
            checkpoint = runService.get(runId);
        } catch (RuntimeException exception) {
            subscription.close();
            throw exception;
        }

        ServerSentEvent<Object> snapshot = ServerSentEvent.builder()
                .data(new RunTraceWebSocketHandler.TraceSnapshotFrame(
                        "SNAPSHOT", RunView.from(checkpoint)))
                .id(Long.toString(checkpoint.version()))
                .event("snapshot")
                .build();
        Flux<ServerSentEvent<Object>> events = subscription.events()
                .map(event -> ServerSentEvent.builder()
                        .data(new RunTraceWebSocketHandler.TraceEventFrame("EVENT", event))
                        .id(event.eventId().toString())
                        .event("trace")
                        .build());
        return Flux.concat(Mono.just(snapshot), events)
                .doFinally(signal -> subscription.close());
    }
}
