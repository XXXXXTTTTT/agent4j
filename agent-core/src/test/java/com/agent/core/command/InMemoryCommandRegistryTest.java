package com.agent.core.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryCommandRegistryTest {

    @Test
    void workspaceDefinitionOverridesGlobalDefinitionAndAliasResolvesToIt() {
        InMemoryCommandRegistry registry = new InMemoryCommandRegistry();
        registry.replace(List.of(definition("deploy", CommandSource.GLOBAL),
                definition("deploy", CommandSource.WORKSPACE)));

        assertThat(registry.find("deploy").orElseThrow().source())
                .isEqualTo(CommandSource.WORKSPACE);
        assertThat(registry.find("ship").orElseThrow().source())
                .isEqualTo(CommandSource.WORKSPACE);
        assertThat(registry.revision()).isEqualTo(1L);
    }

    @Test
    void duplicateNamesWithinOneSourceRejectTheWholeReplacement() {
        InMemoryCommandRegistry registry = new InMemoryCommandRegistry();

        assertThatThrownBy(() -> registry.replace(List.of(
                definition("deploy", CommandSource.GLOBAL),
                definition("deploy", CommandSource.GLOBAL))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
        assertThat(registry.list()).isEmpty();
        assertThat(registry.revision()).isZero();
    }

    private CommandDefinition definition(String name, CommandSource source) {
        return new CommandDefinition(
                name,
                name,
                "测试命令",
                List.of("ship"),
                List.of(),
                CommandChannel.WORKFLOW_SKILL,
                source,
                CommandPermission.VIEWER,
                (invocation, context) -> CommandResult.success("ok"));
    }
}
