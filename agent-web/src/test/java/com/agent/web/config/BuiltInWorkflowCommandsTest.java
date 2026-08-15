package com.agent.web.config;

import com.agent.core.command.CommandChannel;
import com.agent.core.command.CommandContext;
import com.agent.core.command.CommandDefinition;
import com.agent.core.command.CommandInvocation;
import com.agent.core.command.CommandResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuiltInWorkflowCommandsTest {

    @Test
    void exposesTheCompleteBuiltInWorkflowCatalog() throws Exception {
        com.agent.web.command.ConversationWorkflowCommandBridge bridge = mock(
                com.agent.web.command.ConversationWorkflowCommandBridge.class);
        AtomicReference<String> renderedPrompt = new AtomicReference<>();
        when(bridge.submit(any(), any(), any())).thenAnswer(invocation -> {
            renderedPrompt.set(invocation.getArgument(2));
            return CommandResult.forwarded("已提交");
        });
        Method method = CommandRegistryConfiguration.class.getDeclaredMethod(
                "builtInWorkflows", com.agent.web.command.ConversationWorkflowCommandBridge.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<CommandDefinition> commands = (List<CommandDefinition>) method.invoke(
                new CommandRegistryConfiguration(), bridge);
        Map<String, CommandDefinition> byName = commands.stream().collect(
                java.util.stream.Collectors.toMap(CommandDefinition::name, Function.identity()));

        assertThat(byName).containsKeys(
                "plan", "review", "debug", "fix", "test", "explain", "refactor",
                "security-review", "research", "document", "implement", "verify",
                "inspect", "architecture", "release");
        assertThat(byName.values()).allMatch(command ->
                command.channel() == CommandChannel.WORKFLOW_SKILL);

        CommandResult result = byName.get("debug").handler().handle(
                new CommandInvocation("debug", List.of("登录失败"), "/debug 登录失败"),
                new CommandContext("actor", "workspace", "conversation"));
        assertThat(result.status()).isEqualTo(CommandResult.Status.FORWARDED);
        assertThat(renderedPrompt)
                .hasValueSatisfying(prompt -> assertThat(prompt)
                .contains("登录失败")
                .contains("调试"));
    }
}
