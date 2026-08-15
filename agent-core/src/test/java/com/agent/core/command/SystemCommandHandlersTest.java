package com.agent.core.command;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SystemCommandHandlersTest {

    @Test
    void registersAllLocalControlCommandsAndDoesNotUseWorkflowBridge() {
        InMemoryCommandRegistry registry = new InMemoryCommandRegistry();
        RecordingContextService contextService = new RecordingContextService();
        List<String> rewindCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
        registry.replace(SystemCommandHandlers.definitions(
                registry,
                contextService,
                (context, checkpoint) -> {
                    rewindCalls.add(checkpoint);
                    return CommandResult.success("已回滚");
                }));
        CommandDispatcher dispatcher = new CommandDispatcher(
                registry,
                (definition, context) -> CommandAuthorizationDecision.allow(),
                event -> { });

        for (String command : List.of(
                "/help", "/context", "/compact focus", "/clear", "/cost",
                "/permissions", "/rewind cp-1", "/new", "/reset", "/status",
                "/memory")) {
            assertThat(dispatcher.dispatch(command, context()).status())
                    .isEqualTo(CommandResult.Status.COMPLETED);
        }

        assertThat(contextService.calls()).containsExactlyInAnyOrder(
                "context", "compact", "compact", "clear", "clear", "clear",
                "cost", "permissions");
        assertThat(rewindCalls).containsExactly("cp-1");
        assertThat(registry.find("usage")).isPresent()
                .get().extracting(CommandDefinition::name).isEqualTo("cost");
    }

    @Test
    void statusReturnsTheExactExecutionContextAndMemoryBuildsAStableSummary() {
        InMemoryCommandRegistry registry = new InMemoryCommandRegistry();
        RecordingContextService contextService = new RecordingContextService();
        registry.replace(SystemCommandHandlers.definitions(
                registry, contextService, (context, checkpoint) -> CommandResult.success("已回滚")));
        CommandDispatcher dispatcher = new CommandDispatcher(
                registry,
                (definition, context) -> CommandAuthorizationDecision.allow(),
                event -> { });
        CommandContext context = context();

        CommandResult status = dispatcher.dispatch("/status", context);
        CommandResult memory = dispatcher.dispatch("/memory", context);

        assertThat(status.message()).isEqualTo("会话状态");
        assertThat(status.data()).containsEntry("actorId", "actor-1")
                .containsEntry("workspaceId", "workspace-1")
                .containsEntry("conversationId", "conversation-1");
        assertThat(memory.status()).isEqualTo(CommandResult.Status.COMPLETED);
        assertThat(contextService.calls()).contains("compact");
    }

    @Test
    void helpUsesTheCurrentRegistrySnapshot() {
        InMemoryCommandRegistry registry = new InMemoryCommandRegistry();
        RecordingContextService contextService = new RecordingContextService();
        registry.replace(SystemCommandHandlers.definitions(
                registry,
                contextService,
                (context, checkpoint) -> CommandResult.success("已回滚")));
        registry.replace(List.of(new CommandDefinition(
                "custom",
                "Custom",
                "工作区命令",
                List.of(),
                List.of(),
                CommandChannel.SYSTEM_DIRECTIVE,
                CommandSource.WORKSPACE,
                CommandPermission.VIEWER,
                (invocation, context) -> CommandResult.success("custom"))));

        CommandResult result = registry.find("custom").orElseThrow()
                .handler().handle(new CommandInvocation("custom", List.of(), "/custom"), context());

        assertThat(result.message()).isEqualTo("custom");
    }

    @Test
    void helpReturnsStructuredCommandMetadataForDiscovery() {
        InMemoryCommandRegistry registry = new InMemoryCommandRegistry();
        RecordingContextService contextService = new RecordingContextService();
        List<CommandDefinition> definitions = new ArrayList<>(SystemCommandHandlers.definitions(
                registry, contextService, (context, checkpoint) -> CommandResult.success("已回滚")));
        definitions.add(new CommandDefinition(
                "review", "审查", "审查变更", List.of("code-review"),
                List.of(new CommandParameter("request", "工作请求", true)),
                CommandChannel.WORKFLOW_SKILL, CommandSource.BUILT_IN, CommandPermission.OPERATOR,
                (invocation, context) -> CommandResult.forwarded("已提交")));
        registry.replace(definitions);

        CommandResult result = registry.find("help").orElseThrow().handler()
                .handle(new CommandInvocation("help", List.of(), "/help"), context());

        assertThat(result.data()).containsKey("commands");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> commands = (List<Map<String, Object>>) result.data().get("commands");
        assertThat(commands).anySatisfy(command -> assertThat(command)
                .containsEntry("name", "review")
                .containsEntry("description", "审查变更")
                .containsEntry("channel", "WORKFLOW_SKILL")
                .containsEntry("permission", "OPERATOR")
                .containsEntry("aliases", List.of("code-review")));
        assertThat(commands).anySatisfy(command -> assertThat(command)
                .containsEntry("name", "help")
                .containsEntry("channel", "SYSTEM_DIRECTIVE"));
    }

    private CommandContext context() {
        return new CommandContext("actor-1", "workspace-1", "conversation-1");
    }

    private static final class RecordingContextService implements CommandContextService {
        private final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override
        public CommandResult context(CommandContext context) {
            calls.add("context");
            return CommandResult.success("上下文");
        }

        @Override
        public CommandResult compact(CommandContext context, String focus) {
            calls.add("compact");
            return CommandResult.success(focus);
        }

        @Override
        public CommandResult clear(CommandContext context) {
            calls.add("clear");
            return CommandResult.success("新会话");
        }

        @Override
        public CommandResult cost(CommandContext context) {
            calls.add("cost");
            return CommandResult.success("0");
        }

        @Override
        public CommandResult permissions(CommandContext context, List<String> arguments) {
            calls.add("permissions");
            return CommandResult.success("权限");
        }

        List<String> calls() {
            return calls;
        }

    }
}
