package com.agent.web.terminal;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.RunCheckpoint;
import com.agent.web.log.InMemoryRunLogEventBus;
import com.agent.web.log.RunLogSubscription;
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

/** 提供 Run 终端快照与实时日志 SSE。 */
@RestController
@RequestMapping("/api/runs")
public final class RunTerminalController {

    private final AgentRunService runService;
    private final InMemoryRunLogEventBus eventBus;

    /** 创建 Run 终端 Controller。 */
    public RunTerminalController(
            AgentRunService runService,
            InMemoryRunLogEventBus eventBus) {
        this.runService = Objects.requireNonNull(runService, "runService 不能为空");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus 不能为空");
    }

    /** 先发送权威终端快照，再发送同一 Run 的实时日志。 */
    @GetMapping(value = "/{runId}/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<TerminalFrame>> logs(@PathVariable UUID runId) {
        RunLogSubscription subscription = eventBus.openSubscription(runId);
        RunCheckpoint checkpoint;
        TerminalSnapshot terminal;
        try {
            checkpoint = runService.get(runId);
            terminal = TerminalSnapshot.from(checkpoint);
        } catch (RuntimeException exception) {
            subscription.close();
            throw exception;
        }

        ServerSentEvent<TerminalFrame> snapshot = ServerSentEvent
                .builder(TerminalFrame.snapshot(terminal))
                .id(Long.toString(checkpoint.version()))
                .event("snapshot")
                .build();
        Flux<ServerSentEvent<TerminalFrame>> logs = subscription.events()
                .map(event -> ServerSentEvent
                        .builder(TerminalFrame.log(event))
                        .id(event.eventId().toString())
                        .event("log")
                        .build());
        return Flux.concat(Mono.just(snapshot), logs)
                .doFinally(signal -> subscription.close());
    }
}
