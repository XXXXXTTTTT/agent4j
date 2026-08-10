package com.agent.cli;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class LoopbackServerEndpointsTest {

    @Test
    void expandsExactLocalhostToExplicitLoopbacks() {
        URI server = URI.create("http://localhost:8080/agent4j");

        assertThat(LoopbackServerEndpoints.forServer(server)).containsExactly(
                URI.create("http://[::1]:8080/agent4j"),
                URI.create("http://127.0.0.1:8080/agent4j"));
    }

    @Test
    void keepsNonLocalhostServerUnchanged() {
        URI server = URI.create("https://agent4j.example.com:9443/api");

        assertThat(LoopbackServerEndpoints.forServer(server)).containsExactly(server);
    }
}
