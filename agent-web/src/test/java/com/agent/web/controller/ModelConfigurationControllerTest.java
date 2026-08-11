package com.agent.web.controller;

import com.agent.web.model.ModelConfigurationService;
import com.agent.web.model.ModelConfigurationSnapshot;
import com.agent.web.model.ModelEndpointRecord;
import com.agent.web.model.ModelGroupRecord;
import com.agent.web.model.ModelProviderRecord;
import com.agent.core.llm.InferenceCapability;
import com.agent.core.llm.TaskType;
import com.agent.web.persistence.JdbcModelConfigurationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void updatesProviderOnExactPathAndOmitsApiKeyWhenNotProvided() {
        UUID id = UUID.randomUUID();
        ModelProviderRecord updated = new ModelProviderRecord(id, "u", "网关", "https://example.com", "/chat", "sk-a****7890", Instant.now(), Instant.now());
        when(service.updateProvider(any(), anyString(), anyString(), anyString(), any())).thenReturn(updated);

        client.put().uri("/api/model-config/providers/{id}", id)
                .header("Content-Type", "application/json")
                .bodyValue("""
                        {"displayName":"网关","baseUrl":"https://example.com","chatCompletionsPath":"/chat"}
                        """)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.apiKeyMasked").isEqualTo("sk-a****7890")
                .jsonPath("$.apiKey").doesNotExist();

        var captor = forClass(String.class);
        verify(service).updateProvider(eq(id), captor.capture(), captor.capture(), captor.capture(), isNull());
        assertThat(captor.getAllValues()).containsExactly("网关", "https://example.com", "/chat");
    }

    @Test
    void updatesEndpointOnExactPath() {
        UUID id = UUID.randomUUID();
        ModelEndpointRecord updated = new ModelEndpointRecord(id, UUID.randomUUID(), "端点", "model", Set.of(InferenceCapability.CHAT_COMPLETIONS), 2, 3, true, Instant.now(), Instant.now());
        when(service.updateEndpoint(any(), anyString(), anyString(), anySet(), anyInt(), anyInt(), anyBoolean())).thenReturn(updated);

        client.put().uri("/api/model-config/endpoints/{id}", id).header("Content-Type", "application/json").bodyValue("""
                {"displayName":"端点","modelId":"model","capabilities":["CHAT_COMPLETIONS"],"priority":2,"weight":3,"enabled":true}
                """).exchange().expectStatus().isOk().expectBody().jsonPath("$.modelId").isEqualTo("model");
        var endpointText = forClass(String.class);
        var modelText = forClass(String.class);
        @SuppressWarnings("rawtypes") var capabilitySet = forClass(Set.class);
        var priority = forClass(Integer.class);
        var weight = forClass(Integer.class);
        var enabled = forClass(Boolean.class);
        verify(service).updateEndpoint(eq(id), endpointText.capture(), modelText.capture(), capabilitySet.capture(),
                priority.capture(), weight.capture(), enabled.capture());
        assertThat(endpointText.getValue()).isEqualTo("端点");
        assertThat(modelText.getValue()).isEqualTo("model");
        assertThat(capabilitySet.getValue()).isEqualTo(Set.of(InferenceCapability.CHAT_COMPLETIONS));
        assertThat(priority.getValue()).isEqualTo(2);
        assertThat(weight.getValue()).isEqualTo(3);
        assertThat(enabled.getValue()).isTrue();
    }

    @Test
    void updatesGroupOnExactPath() {
        UUID id = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        ModelGroupRecord updated = new ModelGroupRecord(id, "u", "组", TaskType.CODE, List.of(endpointId), Instant.now(), Instant.now());
        when(service.updateGroup(any(), anyString(), any(), any())).thenReturn(updated);

        client.put().uri("/api/model-config/groups/{id}", id).header("Content-Type", "application/json").bodyValue("""
                {"displayName":"组","taskType":"CODE","endpointIds":["%s"]}
                """.formatted(endpointId)).exchange().expectStatus().isOk().expectBody().jsonPath("$.displayName").isEqualTo("组");
        var groupText = forClass(String.class);
        var taskType = forClass(TaskType.class);
        @SuppressWarnings("rawtypes") var endpointList = forClass(List.class);
        verify(service).updateGroup(eq(id), groupText.capture(), taskType.capture(), endpointList.capture());
        assertThat(groupText.getValue()).isEqualTo("组");
        assertThat(taskType.getValue()).isEqualTo(TaskType.CODE);
        assertThat(endpointList.getValue()).isEqualTo(List.of(endpointId));
    }

    @Test
    void deletesEndpointAndGroupWithNoContent() {
        UUID endpointId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        client.delete().uri("/api/model-config/endpoints/{id}", endpointId).exchange().expectStatus().isNoContent();
        client.delete().uri("/api/model-config/groups/{id}", groupId).exchange().expectStatus().isNoContent();
        verify(service).deleteEndpoint(endpointId);
        verify(service).deleteGroup(groupId);
    }

    @Test
    void mapsEndpointReferenceConflictTo409WithExactDetail() {
        UUID id = UUID.randomUUID();
        String detail = "Endpoint 仍被模型组引用，请先从 Group 移除: " + id;
        doThrow(new JdbcModelConfigurationRepository.ModelConfigurationConflictException(detail)).when(service).deleteEndpoint(id);
        client.delete().uri("/api/model-config/endpoints/{id}", id).exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail").isEqualTo(detail);
    }

    @Test
    void rejectsInvalidUpdateRequestWith400() {
        client.put().uri("/api/model-config/endpoints/{id}", UUID.randomUUID()).header("Content-Type", "application/json").bodyValue("""
                {"displayName":"","modelId":"","capabilities":[],"priority":-1,"weight":0,"enabled":true}
                """).exchange().expectStatus().isBadRequest();
        verify(service, never()).updateEndpoint(any(), anyString(), anyString(), anySet(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void rejectsNullCapabilityForCreateAndUpdateWith400() {
        String createBody = """
                {"providerId":"7910a57a-a6c8-47be-a577-d2cf9336daec","displayName":"端点","modelId":"model","capabilities":[null],"priority":0,"weight":1,"enabled":true}
                """;
        client.post().uri("/api/model-config/endpoints").header("Content-Type", "application/json")
                .bodyValue(createBody).exchange().expectStatus().isBadRequest();
        client.put().uri("/api/model-config/endpoints/{id}", UUID.randomUUID())
                .header("Content-Type", "application/json").bodyValue("""
                        {"displayName":"端点","modelId":"model","capabilities":[null],"priority":0,"weight":1,"enabled":true}
                        """).exchange().expectStatus().isBadRequest();
        verify(service, never()).createEndpoint(any(), anyString(), anyString(), anySet(), anyInt(), anyInt(), anyBoolean());
        verify(service, never()).updateEndpoint(any(), anyString(), anyString(), anySet(), anyInt(), anyInt(), anyBoolean());
    }
}
