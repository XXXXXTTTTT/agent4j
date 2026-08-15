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

    @Test
    void builtInSystemDirectiveCannotBeReplacedByWorkspaceWorkflowNameOrAlias() {
        InMemoryCommandRegistry registry = new InMemoryCommandRegistry();
        CommandDefinition system = definition(
                "help", List.of("commands"), CommandChannel.SYSTEM_DIRECTIVE, CommandSource.BUILT_IN);
        CommandDefinition conflictingName = definition(
                "help", List.of(), CommandChannel.WORKFLOW_SKILL, CommandSource.WORKSPACE);
        CommandDefinition conflictingAlias = definition(
                "guide", List.of("commands"), CommandChannel.WORKFLOW_SKILL, CommandSource.WORKSPACE);

        registry.replace(List.of(system, conflictingName, conflictingAlias));

        assertThat(registry.find("help")).contains(system);
        assertThat(registry.find("commands")).contains(system);
        assertThat(registry.find("guide")).isPresent()
                .get().extracting(CommandDefinition::source, CommandDefinition::channel)
                .containsExactly(CommandSource.WORKSPACE, CommandChannel.WORKFLOW_SKILL);
        assertThat(registry.list()).extracting(CommandDefinition::name)
                .containsExactlyInAnyOrder("help", "guide");
        assertThat(registry.find("guide").orElseThrow().aliases()).isEmpty();
    }

    private CommandDefinition definition(String name, CommandSource source) {
        return definition(name, List.of("ship"), CommandChannel.WORKFLOW_SKILL, source);
    }

    private CommandDefinition definition(
            String name,
            List<String> aliases,
            CommandChannel channel,
            CommandSource source) {
        return new CommandDefinition(
                name,
                name,
                "测试命令",
                aliases,
                List.of(),
                channel,
                source,
                CommandPermission.VIEWER,
                (invocation, context) -> CommandResult.success("ok"));
    }
}
