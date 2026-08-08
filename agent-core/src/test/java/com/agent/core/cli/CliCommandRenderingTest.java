package com.agent.core.cli;

import com.agent.sandbox.pty.DockerTarget;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliCommandRenderingTest {

    @TempDir
    Path root;

    @Test
    void quotesSpacesQuotesAndUnicodeAndProducesStableFingerprint() {
        CliCommandCatalog catalog = new CliCommandCatalog(List.of(
                new CliCommandDefinition("read", "printf", List.of("%s\\n"), CliRiskLevel.READ_ONLY, Set.of())));
        CliCommandIntent intent = new CliCommandIntent(
                "read",
                List.of("hello world", "O'Reilly", "江西新余"),
                root,
                new DockerTarget("image", root, "/workspace"),
                Duration.ofSeconds(10));

        CliAuthorization first = catalog.authorize(intent, new CliAuthorizationContext(Set.of(), false, false));
        CliAuthorization second = catalog.authorize(intent, new CliAuthorizationContext(Set.of(), true, true));

        assertThat(first.plan().request().bashCommand())
                .contains("'hello world'")
                .contains("'O'\\''Reilly'")
                .contains("'江西新余'");
        assertThat(first.plan().commandSha256()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(second.plan().commandSha256()).isEqualTo(first.plan().commandSha256());
    }

    @Test
    void rejectsShellOperatorsNewlinesNullAndCommandSubstitution() {
        CliCommandCatalog catalog = new CliCommandCatalog(List.of(
                new CliCommandDefinition("read", "printf", List.of(), CliRiskLevel.READ_ONLY, Set.of())));
        for (String argument : List.of("ok;rm", "ok|cat", "ok>file", "$(whoami)", "line\nfeed", "nul\u0000")) {
            CliCommandIntent intent = new CliCommandIntent(
                    "read",
                    List.of(argument),
                    root,
                    new DockerTarget("image", root, "/workspace"),
                    Duration.ofSeconds(10));
            assertThatThrownBy(() -> catalog.authorize(
                    intent, new CliAuthorizationContext(Set.of(), false, false)))
                    .isInstanceOf(CliArgumentException.class);
        }
    }
}
