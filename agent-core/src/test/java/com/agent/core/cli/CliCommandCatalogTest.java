package com.agent.core.cli;

import com.agent.core.intent.RequiredCapability;
import com.agent.sandbox.pty.DockerTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CliCommandCatalogTest {

    @TempDir
    Path root;

    @Test
    void rejectsDuplicateDefinitionsAndUsesExactNameLookup() {
        CliCommandDefinition first = definition("read", "printf", CliRiskLevel.READ_ONLY, Set.of());
        CliCommandDefinition duplicate = definition("read", "echo", CliRiskLevel.READ_ONLY, Set.of());
        assertThatThrownBy(() -> new CliCommandCatalog(List.of(first, duplicate)))
                .isInstanceOf(CliCommandDefinitionException.class);

        CliCommandCatalog catalog = new CliCommandCatalog(List.of(first));
        assertThat(catalog.find("read")).contains(first);
        assertThat(catalog.find("READ")).isEmpty();
        assertThatThrownBy(() -> catalog.authorize(intent("unknown", root), context()))
                .isInstanceOf(CliCommandNotFoundException.class);
    }

    @Test
    void appliesRiskApprovalAndCapabilityDecisions() {
        CliCommandCatalog catalog = new CliCommandCatalog(List.of(
                definition("read", "printf", CliRiskLevel.READ_ONLY, Set.of()),
                definition("write", "printf", CliRiskLevel.MUTATING, Set.of(RequiredCapability.TERMINAL)),
                definition("destroy", "printf", CliRiskLevel.DESTRUCTIVE, Set.of(RequiredCapability.TERMINAL))));

        CliAuthorization read = catalog.authorize(intent("read", root), context());
        CliAuthorization writeWaiting = catalog.authorize(
                intent("write", root), context(RequiredCapability.TERMINAL));
        CliAuthorization destroyWaiting = catalog.authorize(
                intent("destroy", root), new CliAuthorizationContext(Set.of(RequiredCapability.TERMINAL), true, false));
        CliAuthorization denied = catalog.authorize(intent("write", root), context(RequiredCapability.CODE_READ));

        assertThat(read.decision()).isEqualTo(CliAuthorizationDecision.ALLOWED);
        assertThat(writeWaiting.decision()).isEqualTo(CliAuthorizationDecision.APPROVAL_REQUIRED);
        assertThat(destroyWaiting.decision()).isEqualTo(CliAuthorizationDecision.APPROVAL_REQUIRED);
        assertThat(denied.decision()).isEqualTo(CliAuthorizationDecision.DENIED);
        assertThat(denied.reason()).contains("能力");

        CliAuthorization destroyAllowed = catalog.authorize(
                intent("destroy", root), new CliAuthorizationContext(Set.of(RequiredCapability.TERMINAL), true, true));
        assertThat(destroyAllowed.decision()).isEqualTo(CliAuthorizationDecision.ALLOWED);
    }

    @Test
    void rejectsTargetOutsideWorkspaceAfterResolvingRealPaths() throws IOException {
        Path outside = Files.createTempDirectory("cli-outside-");
        CliCommandCatalog catalog = new CliCommandCatalog(List.of(
                definition("read", "printf", CliRiskLevel.READ_ONLY, Set.of())));
        CliCommandIntent intent = new CliCommandIntent(
                "read", List.of(), root, new DockerTarget("image", outside, "/workspace"), Duration.ofSeconds(10));

        assertThatThrownBy(() -> catalog.authorize(intent, context()))
                .isInstanceOf(CliWorkspaceViolationException.class)
                .satisfies(error -> {
                    CliWorkspaceViolationException violation = (CliWorkspaceViolationException) error;
                    assertThat(violation.workspaceRoot()).isEqualTo(root.toAbsolutePath().normalize());
                    assertThat(violation.targetPath()).isEqualTo(outside.toAbsolutePath().normalize());
                });
    }

    @Test
    void rejectsSymbolicLinkEscapeWhenPlatformAllowsSymbolicLinks() throws IOException {
        Path outside = Files.createTempDirectory("cli-link-outside-");
        Path link = root.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            assumeTrue(false, "当前平台不允许创建符号链接");
        }

        CliCommandCatalog catalog = new CliCommandCatalog(List.of(
                definition("read", "printf", CliRiskLevel.READ_ONLY, Set.of())));
        CliCommandIntent intent = new CliCommandIntent(
                "read", List.of(), root, new DockerTarget("image", link, "/workspace"), Duration.ofSeconds(10));

        assertThatThrownBy(() -> catalog.authorize(intent, context()))
                .isInstanceOf(CliWorkspaceViolationException.class);
    }

    private CliCommandIntent intent(String name, Path workspace) {
        return intent(name, workspace, List.of());
    }

    private CliCommandIntent intent(String name, Path workspace, List<String> arguments) {
        return new CliCommandIntent(
                name,
                arguments,
                workspace,
                new DockerTarget("image", workspace, "/workspace"),
                Duration.ofSeconds(10));
    }

    private static CliCommandDefinition definition(
            String name,
            String executable,
            CliRiskLevel riskLevel,
            Set<RequiredCapability> capabilities) {
        return new CliCommandDefinition(name, executable, List.of(), riskLevel, capabilities);
    }

    private static CliAuthorizationContext context(RequiredCapability... capabilities) {
        return new CliAuthorizationContext(Set.of(capabilities), false, false);
    }
}
