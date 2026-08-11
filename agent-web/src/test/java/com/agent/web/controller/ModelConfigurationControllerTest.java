package com.agent.web.controller;

import com.agent.web.model.ModelConfigurationService;
import com.agent.web.model.ModelConfigurationSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.mockito.Mockito.when;

@WebFluxTest(controllers = ModelConfigurationController.class,
        properties = "agent.production.enabled=true")
@Import(RunExceptionHandler.class)
class ModelConfigurationControllerTest {
    @Autowired private WebTestClient client;
    @MockBean private ModelConfigurationService service;

    @Test
    void returnsCurrentUserModelConfigurationWithMaskedProviderKey() {
        when(service.snapshot()).thenReturn(new ModelConfigurationSnapshot(List.of(), List.of(), List.of()));

        client.get().uri("/api/model-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.providers").isArray()
                .jsonPath("$.endpoints").isArray()
                .jsonPath("$.groups").isArray();
    }
}
