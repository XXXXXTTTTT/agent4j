package com.agent.web.controller;

import com.agent.core.llm.InferenceCapability;
import com.agent.core.llm.TaskType;
import com.agent.web.model.ModelConfigurationService;
import com.agent.web.model.ModelConfigurationSnapshot;
import com.agent.web.model.ModelEndpointRecord;
import com.agent.web.model.ModelGroupRecord;
import com.agent.web.model.ModelProviderRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 用户模型池配置 REST API。 */
@RestController
@RequestMapping("/api/model-config")
@ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
public final class ModelConfigurationController {
    private final ObjectProvider<ModelConfigurationService> service;

    public ModelConfigurationController(ObjectProvider<ModelConfigurationService> service) {
        this.service = Objects.requireNonNull(service, "service 不能为空");
    }

    @GetMapping
    public ModelConfigurationSnapshot snapshot() {
        return service().snapshot();
    }

    @PostMapping("/providers")
    public ResponseEntity<ModelProviderRecord> createProvider(
            @Valid @RequestBody CreateProviderRequest request) {
        return ResponseEntity.status(201).body(service().createProvider(
                request.displayName(), request.baseUrl(), request.apiKey()));
    }

    @PostMapping("/endpoints")
    public ResponseEntity<ModelEndpointRecord> createEndpoint(
            @Valid @RequestBody CreateEndpointRequest request) {
        return ResponseEntity.status(201).body(service().createEndpoint(
                request.providerId(), request.displayName(), request.modelId(),
                request.capabilities(), request.priority(), request.weight(), request.enabled()));
    }

    @PostMapping("/groups")
    public ResponseEntity<ModelGroupRecord> createGroup(
            @Valid @RequestBody CreateGroupRequest request) {
        return ResponseEntity.status(201).body(service().createGroup(
                request.displayName(), request.taskType(), request.endpointIds()));
    }

    @DeleteMapping("/providers/{providerId}")
    public ResponseEntity<Void> deleteProvider(@PathVariable UUID providerId) {
        service().deleteProvider(providerId);
        return ResponseEntity.noContent().build();
    }

    private ModelConfigurationService service() {
        ModelConfigurationService resolved = service.getIfAvailable();
        if (resolved == null) throw new IllegalStateException("模型配置服务未启用");
        return resolved;
    }

    public record CreateProviderRequest(
            @NotBlank String displayName,
            @NotBlank String baseUrl,
            @NotBlank String apiKey) {
    }

    public record CreateEndpointRequest(
            @NotNull UUID providerId,
            @NotBlank String displayName,
            @NotBlank String modelId,
            @NotEmpty Set<InferenceCapability> capabilities,
            @PositiveOrZero int priority,
            @Positive int weight,
            boolean enabled) {
    }

    public record CreateGroupRequest(
            @NotBlank String displayName,
            @NotNull TaskType taskType,
            @NotEmpty List<UUID> endpointIds) {
    }
}
