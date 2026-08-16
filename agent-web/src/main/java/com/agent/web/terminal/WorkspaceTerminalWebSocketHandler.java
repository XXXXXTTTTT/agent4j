package com.agent.web.terminal;

import com.agent.sandbox.pty.InteractivePtySession;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.server.PathContainer;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Objects;
import java.util.UUID;

/** 提供工作区隔离、双向输入输出的交互式 PTY WebSocket。 */
public final class WorkspaceTerminalWebSocketHandler implements WebSocketHandler {

    private static final PathPattern TERMINAL_PATH = new PathPatternParser()
            .parse("/ws/workspaces/{workspaceId}/terminal");

    private final ActorResolver actorResolver;
    private final WorkspaceAccessService workspaceAccess;
    private final WorkspaceInteractiveTerminalService terminalService;
    private final ObjectMapper objectMapper;

    /** 创建交互终端 WebSocket Handler。 */
    public WorkspaceTerminalWebSocketHandler(
            ActorResolver actorResolver,
            WorkspaceAccessService workspaceAccess,
            WorkspaceInteractiveTerminalService terminalService,
            ObjectMapper objectMapper) {
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
        this.workspaceAccess = Objects.requireNonNull(workspaceAccess, "workspaceAccess 不能为空");
        this.terminalService = Objects.requireNonNull(terminalService, "terminalService 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    @Override
    public Mono<Void> handle(WebSocketSession webSocket) {
        UUID workspaceId = extractWorkspaceId(webSocket);
        Actor actor = actorResolver.current();
        WorkspaceRecord workspace = workspaceAccess.requireWorkspace(
                workspaceId, actor.userId(), WorkspacePermission.OPERATOR);
        Sinks.Many<InteractiveTerminalFrame> outbound = Sinks.many()
                .unicast().onBackpressureBuffer();
        String terminalSessionId = UUID.randomUUID().toString();
        InteractivePtySession pty;
        try {
            pty = terminalService.open(
                    workspace,
                    text -> emit(outbound, new InteractiveTerminalFrame.Output("output", text)),
                    exitCode -> {
                        emit(outbound, new InteractiveTerminalFrame.Exit("exit", exitCode));
                        outbound.tryEmitComplete();
                    });
        } catch (RuntimeException exception) {
            emit(outbound, new InteractiveTerminalFrame.Error("error", exception.getMessage()));
            outbound.tryEmitComplete();
            return webSocket.send(outbound.asFlux().map(frame -> webSocket.textMessage(writeJson(frame))));
        }
        emit(outbound, new InteractiveTerminalFrame.Ready(
                "ready", terminalSessionId, workspace.workspacePath().toString(),
                pty.getClass().getSimpleName()));

        Flux<WebSocketMessage> output = outbound.asFlux()
                .map(frame -> webSocket.textMessage(writeJson(frame)));
        Mono<Void> receive = webSocket.receive()
                .doOnNext(message -> handleMessage(message, pty, outbound))
                .then();
        Mono<Void> send = webSocket.send(output);
        return Mono.firstWithSignal(send, receive)
                .doFinally(signal -> {
                    pty.close();
                    outbound.tryEmitComplete();
                });
    }

    private void handleMessage(
            WebSocketMessage message,
            InteractivePtySession pty,
            Sinks.Many<InteractiveTerminalFrame> outbound) {
        if (message.getType() != WebSocketMessage.Type.TEXT) {
            emit(outbound, new InteractiveTerminalFrame.Error("error", "交互终端只接受文本帧"));
            return;
        }
        try {
            switch (InteractiveTerminalMessage.decode(objectMapper, message.getPayloadAsText())) {
                case InteractiveTerminalMessage.Input input -> pty.write(input.data());
                case InteractiveTerminalMessage.Resize resize -> pty.resize(resize.cols(), resize.rows());
                case InteractiveTerminalMessage.Interrupt ignored -> pty.interrupt();
                case InteractiveTerminalMessage.Close ignored -> {
                    pty.close();
                    outbound.tryEmitComplete();
                }
            }
        } catch (RuntimeException exception) {
            emit(outbound, new InteractiveTerminalFrame.Error("error", exception.getMessage()));
        }
    }

    private UUID extractWorkspaceId(WebSocketSession session) {
        String path = session.getHandshakeInfo().getUri().getPath();
        PathPattern.PathMatchInfo match = TERMINAL_PATH.matchAndExtract(PathContainer.parsePath(path));
        if (match == null) throw new IllegalArgumentException("WebSocket 路径不匹配: " + path);
        return UUID.fromString(match.getUriVariables().get("workspaceId"));
    }

    private void emit(Sinks.Many<InteractiveTerminalFrame> sink, InteractiveTerminalFrame frame) {
        if (sink.tryEmitNext(frame).isFailure()) sink.tryEmitComplete();
    }

    private String writeJson(InteractiveTerminalFrame frame) {
        try {
            return objectMapper.writeValueAsString(frame);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("交互终端 WebSocket JSON 序列化失败", exception);
        }
    }
}
