package com.agent.core.cli;

import com.agent.core.intent.RequiredCapability;
import com.agent.sandbox.pty.CommandRequest;
import com.agent.sandbox.pty.CommandResult;
import com.agent.sandbox.pty.DockerTarget;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliCommandDefinitionTest {

    @TempDir
    Path workspace;

    @Test
    void rejectsShellControlTokensAndFreezesDefinitionInputs() {
        assertThatThrownBy(() -> new CliCommandDefinition(
                "maven", "mvn", List.of("test;rm"), CliRiskLevel.READ_ONLY, Set.of()))
                .isInstanceOf(CliCommandDefinitionException.class)
                .extracting("commandName")
                .isEqualTo("maven");

        List<String> fixedArguments = new ArrayList<>(List.of("test"));
        Set<RequiredCapability> capabilities = new HashSet<>(Set.of(RequiredCapability.TERMINAL));
        CliCommandDefinition definition = new CliCommandDefinition(
                "maven", "mvn", fixedArguments, CliRiskLevel.MUTATING, capabilities);

        fixedArguments.add("package");
        capabilities.add(RequiredCapability.CODE_WRITE);

        assertThat(definition.fixedArguments()).containsExactly("test");
        assertThat(definition.requiredCapabilities()).containsExactly(RequiredCapability.TERMINAL);
        assertThatThrownBy(() -> definition.fixedArguments().add("package"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> definition.requiredCapabilities().add(RequiredCapability.CODE_WRITE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validatesExactCommandNameAndExecutable() {
        assertThatThrownBy(() -> definition("Maven", "mvn"))
                .isInstanceOf(CliCommandDefinitionException.class);
        assertThatThrownBy(() -> definition("maven", "mvn test"))
                .isInstanceOf(CliCommandDefinitionException.class);
        assertThatThrownBy(() -> definition("maven", "mvn$HOME"))
                .isInstanceOf(CliCommandDefinitionException.class);

        assertThat(definition("maven.test-1", "mvn").name()).isEqualTo("maven.test-1");
    }

    @Test
    void freezesAuthorizationAndIntentInputs() {
        Set<RequiredCapability> capabilities = new HashSet<>(Set.of(RequiredCapability.TERMINAL));
        CliAuthorizationContext context = new CliAuthorizationContext(capabilities, true, false);
        capabilities.add(RequiredCapability.CODE_WRITE);

        List<String> arguments = new ArrayList<>(List.of("-q"));
        CliCommandIntent intent = new CliCommandIntent(
                "maven",
                arguments,
                workspace,
                new DockerTarget("eclipse-temurin:21", workspace, "/workspace"),
                Duration.ofSeconds(30));
        arguments.add("test");

        assertThat(context.grantedCapabilities()).containsExactly(RequiredCapability.TERMINAL);
        assertThat(intent.arguments()).containsExactly("-q");
        assertThat(intent.workspaceRoot()).isEqualTo(workspace.toAbsolutePath().normalize());
        assertThatThrownBy(() -> intent.arguments().add("test"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidIntentLimits() {
        DockerTarget target = new DockerTarget("eclipse-temurin:21", workspace, "/workspace");
        List<String> tooManyArguments = java.util.stream.IntStream.range(0, 65)
                .mapToObj(Integer::toString)
                .toList();

        assertThatThrownBy(() -> new CliCommandIntent(
                "maven", tooManyArguments, workspace, target, Duration.ofSeconds(30)))
                .isInstanceOf(CliArgumentException.class);
        assertThatThrownBy(() -> new CliCommandIntent(
                "maven", List.of(), workspace, target, Duration.ofMinutes(11)))
                .isInstanceOf(CliArgumentException.class);
    }

    @Test
    void validatesPlanAuthorizationAndExecutionResult() {
        CommandRequest request = new CommandRequest(
                new DockerTarget("eclipse-temurin:21", workspace, "/workspace"),
                "'printf' 'ok'",
                Duration.ofSeconds(30));
        CliCommandPlan plan = new CliCommandPlan(
                "read", request, CliRiskLevel.READ_ONLY, "a".repeat(64));
        CliAuthorization authorization = new CliAuthorization(
                CliAuthorizationDecision.ALLOWED, "read-only", plan);
        CommandResult commandResult = new CommandResult(0, "ok", "", false);
        CliExecutionResult result = new CliExecutionResult(authorization, Optional.of(commandResult));

        assertThat(result.result()).contains(commandResult);
        assertThatThrownBy(() -> new CliCommandPlan(
                "read", request, CliRiskLevel.READ_ONLY, "ABC"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CliExecutionResult(authorization, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CliCommandDefinition definition(String name, String executable) {
        return new CliCommandDefinition(
                name,
                executable,
                List.of(),
                CliRiskLevel.READ_ONLY,
                Set.of(RequiredCapability.TERMINAL));
    }
}
