package com.agent.core.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** EGG：验证系统控制命令本地完成，工作流命令才进入桥接端口。 */
class CommandEngineEggTest {

    @Test
    void systemCommandsNeverEnterWorkflowBridge() {
        InMemoryCommandRegistry registry = new InMemoryCommandRegistry();
        AtomicInteger workflowCalls = new AtomicInteger();
        AtomicInteger localCalls = new AtomicInteger();
        registry.replace(List.of(
                new CommandDefinition(
                        "context", "上下文", "本地上下文统计", List.of(), List.of(),
                        CommandChannel.SYSTEM_DIRECTIVE, CommandSource.BUILT_IN,
                        CommandPermission.VIEWER,
                        (invocation, context) -> {
                            localCalls.incrementAndGet();
                            return CommandResult.success("local");
                        }),
                new CommandDefinition(
                        "plan", "计划", "工作流计划", List.of(), List.of(),
                        CommandChannel.WORKFLOW_SKILL, CommandSource.BUILT_IN,
                        CommandPermission.VIEWER,
                        (invocation, context) -> {
                            workflowCalls.incrementAndGet();
                            return CommandResult.forwarded("workflow");
                        })));
        CommandDispatcher dispatcher = new CommandDispatcher(
                registry, (definition, context) -> CommandAuthorizationDecision.allow(), event -> { });

        assertThat(dispatcher.dispatch("/context", context()).status())
                .isEqualTo(CommandResult.Status.COMPLETED);
        assertThat(localCalls).hasValue(1);
        assertThat(workflowCalls).hasValue(0);
    }

    @Test
    void workflowCommandUsesBridgeOnlyWhenExplicitlySelected() {
        InMemoryCommandRegistry registry = new InMemoryCommandRegistry();
        AtomicInteger workflowCalls = new AtomicInteger();
        registry.replace(List.of(new CommandDefinition(
                "plan", "计划", "工作流计划", List.of(), List.of(),
                CommandChannel.WORKFLOW_SKILL, CommandSource.BUILT_IN,
                CommandPermission.VIEWER,
                (invocation, context) -> {
                    workflowCalls.incrementAndGet();
                    return CommandResult.forwarded("workflow");
                })));
        CommandDispatcher dispatcher = new CommandDispatcher(
                registry, (definition, context) -> CommandAuthorizationDecision.allow(), event -> { });

        assertThat(dispatcher.dispatch("/plan", context()).status())
                .isEqualTo(CommandResult.Status.FORWARDED);
        assertThat(workflowCalls).hasValue(1);
    }

    private CommandContext context() {
        return new CommandContext("egg-user", "egg-workspace", "egg-conversation");
    }
}
