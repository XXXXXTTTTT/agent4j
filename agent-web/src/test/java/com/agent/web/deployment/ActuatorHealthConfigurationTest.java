package com.agent.web.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ActuatorHealthConfigurationTest {

    private static final Path APPLICATION_PROPERTIES = Path.of(
            "src/main/resources/application.properties");

    @Test
    void exposesProbeGroupsAndGracefulShutdownContract() throws Exception {
        String properties = Files.readString(APPLICATION_PROPERTIES);

        assertThat(properties)
                .contains("management.endpoint.health.probes.enabled=true")
                .contains("management.endpoints.web.exposure.include=health,info")
                .contains("management.endpoint.health.group.readiness.include=readinessState,db")
                .contains("server.shutdown=graceful")
                .contains("spring.lifecycle.timeout-per-shutdown-phase=30s");
    }
}
