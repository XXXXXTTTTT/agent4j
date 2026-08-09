package com.agent.web.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DockerComposeProductModeTest {

    @Test
    void composeEntrypointsForceProductWorkbenchMode() throws IOException {
        Path repositoryRoot = Path.of("..").toRealPath();

        for (String fileName : List.of("docker-compose.local.yml", "docker-compose.yml")) {
            String compose = Files.readString(repositoryRoot.resolve(fileName));
            assertThat(compose)
                    .as(fileName)
                    .contains("AGENT_PRODUCTION_ENABLED: \"true\"")
                    .doesNotContain("AGENT_PRODUCTION_ENABLED: ${AGENT_PRODUCTION_ENABLED:-true}");
        }
    }
}
