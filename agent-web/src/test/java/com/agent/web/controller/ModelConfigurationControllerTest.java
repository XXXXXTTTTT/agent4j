package com.agent.web.controller;

import com.agent.web.model.ModelConfigurationService;
import com.agent.web.model.ModelConfigurationSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
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

    @Test
    void mapsDuplicateEndpointToConfigurationConflict() {
        when(service.createEndpoint(any(), anyString(), anyString(), anySet(),
                anyInt(), anyInt(), anyBoolean()))
                .thenThrow(new DuplicateKeyException("duplicate model endpoint"));

        client.post().uri("/api/model-config/endpoints")
                .header("Content-Type", "application/json")
                .bodyValue("""
                        {
                          "providerId": "7910a57a-a6c8-47be-a577-d2cf9336daec",
                          "displayName": "duplicate",
                          "modelId": "gpt-5.4-mini",
                          "capabilities": ["CHAT_COMPLETIONS"],
                          "priority": 0,
                          "weight": 1,
                          "enabled": true
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("模型配置与已有记录冲突");
    }
}
