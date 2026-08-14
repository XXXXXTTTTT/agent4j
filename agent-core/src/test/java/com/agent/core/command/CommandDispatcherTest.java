package com.agent.core.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CommandDispatcherTest {

    @Test
    void systemHandlerRunsLocallyAndReturnsCompletedResult() {
        AtomicInteger invocations = new AtomicInteger();
        CommandDefinition definition = definition("help", CommandChannel.SYSTEM_DIRECTIVE,
                (invocation, context) -> {
                    invocations.incrementAndGet();
                    return CommandResult.success("本地帮助");
                });
        CommandDispatcher dispatcher = dispatcher(List.of(definition),
                (command, context) -> CommandAuthorizationDecision.allow());

        CommandResult result = dispatcher.dispatch("/help", context());

        assertThat(result.status()).isEqualTo(CommandResult.Status.COMPLETED);
        assertThat(result.message()).isEqualTo("本地帮助");
        assertThat(invocations).hasValue(1);
    }

    @Test
    void unknownAndDeniedCommandsDoNotInvokeTheirHandlers() {
        AtomicInteger invocations = new AtomicInteger();
        CommandDefinition definition = definition("plan", CommandChannel.WORKFLOW_SKILL,
                (invocation, context) -> {
                    invocations.incrementAndGet();
                    return CommandResult.success("不应执行");
                });
        CommandDispatcher dispatcher = dispatcher(List.of(definition),
                (command, context) -> CommandAuthorizationDecision.deny("权限不足"));

        assertThat(dispatcher.dispatch("/missing", context()).status())
                .isEqualTo(CommandResult.Status.NOT_FOUND);
        assertThat(dispatcher.dispatch("/plan", context()).status())
                .isEqualTo(CommandResult.Status.DENIED);
        assertThat(invocations).hasValue(0);
    }

    private CommandDefinition definition(
            String name,
            CommandChannel channel,
            CommandHandler handler) {
        return new CommandDefinition(
                name,
                name,
                "测试命令",
                List.of(),
                List.of(),
                channel,
                CommandSource.BUILT_IN,
                CommandPermission.VIEWER,
                handler);
    }

    private CommandDispatcher dispatcher(
            List<CommandDefinition> definitions,
            CommandAuthorizationPolicy authorizationPolicy) {
        InMemoryCommandRegistry registry = new InMemoryCommandRegistry();
        registry.replace(definitions);
        return new CommandDispatcher(
                registry,
                authorizationPolicy,
                event -> { });
    }

    private CommandContext context() {
        return new CommandContext("actor-1", "workspace-1", "conversation-1");
    }
}
