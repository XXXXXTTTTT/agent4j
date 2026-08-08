package com.agent.core.tool;

import com.agent.core.intent.RequiredCapability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolDefinitionTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesDefinitionBoundariesAndFreezesCapabilities() {
        Set<RequiredCapability> capabilities = new HashSet<>();
        capabilities.add(RequiredCapability.CODE_READ);
        ToolDefinition definition = new ToolDefinition(
                "code.read-file",
                "读取工作区文件",
                objectSchema(),
                capabilities,
                ToolRiskLevel.LOW,
                Duration.ofMinutes(10),
                (call, context) -> JsonNodeFactory.instance.objectNode());
        capabilities.add(RequiredCapability.CODE_WRITE);

        assertThat(definition.requiredCapabilities())
                .containsExactly(RequiredCapability.CODE_READ);
        assertThatThrownBy(() -> definition.requiredCapabilities().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> definition("Code.Read"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> definition("code/read"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> definition("a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> new ToolDefinition(
                "code.read",
                "测".repeat(4001),
                objectSchema(),
                Set.of(),
                ToolRiskLevel.LOW,
                Duration.ofSeconds(1),
                (call, context) -> JsonNodeFactory.instance.objectNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
        assertThatThrownBy(() -> definitionWithTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
        assertThatThrownBy(() -> definitionWithTimeout(Duration.ofMinutes(10).plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void deepCopiesSchemaArgumentsAndOutputOnConstructionAndAccess() {
        ObjectNode schema = objectSchema();
        ToolDefinition definition = new ToolDefinition(
                "code.read",
                "读取文件",
                schema,
                Set.of(RequiredCapability.CODE_READ),
                ToolRiskLevel.LOW,
                Duration.ofSeconds(2),
                (call, context) -> JsonNodeFactory.instance.objectNode());
        schema.put("mutated", true);
        ((ObjectNode) definition.inputSchema()).put("accessorMutation", true);

        ObjectNode arguments = JsonNodeFactory.instance.objectNode().put("path", "src/App.java");
        ToolCall call = new ToolCall("call-1", "code.read", arguments);
        arguments.put("path", "outside.txt");
        ((ObjectNode) call.arguments()).put("path", "accessor.txt");

        ObjectNode output = JsonNodeFactory.instance.objectNode().put("content", "source");
        ToolResult result = new ToolResult(
                "call-1", "code.read", ToolResultStatus.SUCCEEDED, output, "", 4);
        output.put("content", "mutated");
        ((ObjectNode) result.output()).put("content", "accessor");

        assertThat(definition.inputSchema().has("mutated")).isFalse();
        assertThat(definition.inputSchema().has("accessorMutation")).isFalse();
        assertThat(call.arguments().path("path").textValue()).isEqualTo("src/App.java");
        assertThat(result.output().path("content").textValue()).isEqualTo("source");
    }

    @Test
    void validatesCallContextAndResultStateInvariants() {
        Path unnormalized = temporaryDirectory.resolve("nested").resolve("..").resolve("workspace");
        Set<RequiredCapability> capabilities = new HashSet<>();
        capabilities.add(RequiredCapability.TERMINAL);
        ToolInvocationContext context = new ToolInvocationContext(
                UUID.fromString("70000000-0000-0000-0000-000000000001"),
                "ops",
                "user-a",
                unnormalized,
                capabilities,
                false);
        capabilities.clear();

        assertThat(context.workspaceRoot())
                .isEqualTo(unnormalized.toAbsolutePath().normalize());
        assertThat(context.grantedCapabilities())
                .containsExactly(RequiredCapability.TERMINAL);
        assertThatThrownBy(() -> new ToolCall(
                "call-1", "code.read", JsonNodeFactory.instance.arrayNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("arguments");
        assertThatThrownBy(() -> new ToolInvocationContext(
                context.runId(), " ", "user-a", temporaryDirectory, Set.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodeName");
        assertThatThrownBy(() -> new ToolResult(
                "call-1", "code.read", ToolResultStatus.SUCCEEDED,
                NullNode.getInstance(), "", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output");
        assertThatThrownBy(() -> new ToolResult(
                "call-1", "code.read", ToolResultStatus.FAILED,
                NullNode.getInstance(), " ", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("errorStack");
        assertThatThrownBy(() -> new ToolResult(
                "call-1", "code.read", ToolResultStatus.DENIED,
                JsonNodeFactory.instance.objectNode(), "stack", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output");

        ToolResult failure = new ToolResult(
                "call-1", "code.read", ToolResultStatus.FAILED,
                NullNode.getInstance(), "java.lang.IllegalStateException: failed", 3);
        assertThat(failure.output().isNull()).isTrue();
        assertThat(failure.errorStack()).contains("IllegalStateException");
    }

    private ToolDefinition definition(String name) {
        return new ToolDefinition(
                name,
                "读取文件",
                objectSchema(),
                Set.of(),
                ToolRiskLevel.LOW,
                Duration.ofSeconds(1),
                (call, context) -> JsonNodeFactory.instance.objectNode());
    }

    private ToolDefinition definitionWithTimeout(Duration timeout) {
        return new ToolDefinition(
                "code.read",
                "读取文件",
                objectSchema(),
                Set.of(),
                ToolRiskLevel.LOW,
                timeout,
                (call, context) -> JsonNodeFactory.instance.objectNode());
    }

    private ObjectNode objectSchema() {
        return JsonNodeFactory.instance.objectNode().put("type", "object");
    }
}
