package com.agent.core.command;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkdownCommandLoaderTest {

    @Test
    void loadsGlobalAndWorkspaceCommandsWithExactSources() throws IOException {
        Path root = Files.createTempDirectory("agent4j-command-loader-");
        Path global = Files.createDirectories(root.resolve("global"));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        write(global.resolve("review.md"), markdown(
                "review", "WORKFLOW_SKILL", "检查 ${request}", "request"));
        write(workspace.resolve("deploy.md"), markdown(
                "deploy", "WORKFLOW_SKILL", "发布 ${request}", "request"));

        List<CommandDefinition> definitions = new MarkdownCommandLoader(
                4096,
                (invocation, context, template) -> CommandResult.forwarded(template))
                .load(global, workspace);

        assertThat(definitions).extracting(CommandDefinition::name)
                .containsExactly("review", "deploy");
        assertThat(definitions).extracting(CommandDefinition::source)
                .containsExactly(CommandSource.GLOBAL, CommandSource.WORKSPACE);
    }

    @Test
    void rejectsUnknownTemplateVariableAndSystemChannel() throws IOException {
        Path root = Files.createTempDirectory("agent4j-command-invalid-");
        Path global = Files.createDirectories(root.resolve("global"));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        write(global.resolve("unknown.md"), markdown(
                "unknown", "WORKFLOW_SKILL", "${missing}", "request"));

        MarkdownCommandLoader loader = new MarkdownCommandLoader(
                4096,
                (invocation, context, template) -> CommandResult.forwarded(template));
        assertThatThrownBy(() -> loader.load(global, workspace))
                .isInstanceOf(MarkdownCommandLoadException.class)
                .hasMessageContaining("模板变量");

        Files.delete(global.resolve("unknown.md"));
        write(global.resolve("system.md"), markdown(
                "system", "SYSTEM_DIRECTIVE", "本地", "request"));
        assertThatThrownBy(() -> loader.load(global, workspace))
                .isInstanceOf(MarkdownCommandLoadException.class)
                .hasMessageContaining("SYSTEM_DIRECTIVE");
    }

    @Test
    void rejectsOversizedAndDuplicateCommandsAtomically() throws IOException {
        Path root = Files.createTempDirectory("agent4j-command-limits-");
        Path global = Files.createDirectories(root.resolve("global"));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        write(global.resolve("large.md"), "x".repeat(200));
        MarkdownCommandLoader sizeLimitedLoader = new MarkdownCommandLoader(
                64,
                (invocation, context, template) -> CommandResult.forwarded(template));
        assertThatThrownBy(() -> sizeLimitedLoader.load(global, workspace))
                .isInstanceOf(MarkdownCommandLoadException.class)
                .hasMessageContaining("大小");

        Files.delete(global.resolve("large.md"));
        write(global.resolve("one.md"), markdown("same", "WORKFLOW_SKILL", "one", "request"));
        write(global.resolve("two.md"), markdown("same", "WORKFLOW_SKILL", "two", "request"));
        MarkdownCommandLoader duplicateLoader = new MarkdownCommandLoader(
                4096,
                (invocation, context, template) -> CommandResult.forwarded(template));
        assertThatThrownBy(() -> duplicateLoader.load(global, workspace))
                .isInstanceOf(MarkdownCommandLoadException.class)
                .hasMessageContaining("重复");
    }

    private void write(Path path, String value) throws IOException {
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }

    private String markdown(String name, String channel, String body, String argument) {
        return "---\n"
                + "name: " + name + "\n"
                + "description: 测试命令\n"
                + "channel: " + channel + "\n"
                + "arguments:\n"
                + "  - name: " + argument + "\n"
                + "    required: true\n"
                + "---\n"
                + body + "\n";
    }
}
