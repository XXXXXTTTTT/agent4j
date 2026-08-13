package com.agent.core.mcp;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.ToolRiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 负责 MCP 目录快照的规范 JSON 编码、摘要和严格解码。 */
public final class McpCatalogSnapshotCodec {
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion", "actorUserId", "workspaceId", "installationsUpdatedAt",
            "bindings", "snapshotSha256");
    private static final Set<String> V1_BINDING_FIELDS = Set.of(
            "installationId", "snapshotId", "installationVersion", "localToolName",
            "remoteToolName", "riskLevel", "requiredCapabilities", "bindingCreatedAt");
    private static final Set<String> V2_BINDING_FIELDS = Set.of(
            "installationId", "snapshotId", "installationVersion", "runtimeBindingInstanceId", "runtimeBindingRevision",
            "localToolName", "remoteToolName", "riskLevel", "requiredCapabilities", "bindingCreatedAt");
    private static final UUID LEGACY_RUNTIME_BINDING_INSTANCE_ID = new UUID(0, 0);

    private final ObjectMapper objectMapper;

    public McpCatalogSnapshotCodec(ObjectMapper objectMapper) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /** 生成字段顺序固定且包含摘要的 UTF-8 JSON。 */
    public String encode(McpCatalogSnapshot snapshot) {
        ObjectNode unsigned = unsigned(snapshot);
        ObjectNode signed = unsigned.deepCopy();
        signed.put("snapshotSha256", sha256(unsigned));
        try {
            return objectMapper.writeValueAsString(signed);
        } catch (Exception exception) {
            throw new IllegalArgumentException("MCP 目录快照编码失败", exception);
        }
    }

    /** 严格校验快照摘要和身份绑定。 */
    public McpCatalogSnapshot decode(String json, String actorUserId, UUID workspaceId) {
        try {
            JsonNode parsed = objectMapper.readTree(json);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException("MCP 目录快照必须是 JSON object");
            }
            rejectUnknown(parsed, TOP_LEVEL_FIELDS, "MCP 目录快照");
            String exactActor = exactText(parsed, "actorUserId");
            UUID exactWorkspace = UUID.fromString(exactText(parsed, "workspaceId"));
            int schemaVersion = exactInt(parsed, "schemaVersion");
            if (schemaVersion != 1 && schemaVersion != 2) {
                throw new IllegalArgumentException("schemaVersion 必须为 1 或 2");
            }
            List<McpToolBindingSnapshot> bindings = new ArrayList<>();
            JsonNode bindingsNode = parsed.get("bindings");
            if (bindingsNode == null || !bindingsNode.isArray()) {
                throw new IllegalArgumentException("bindings 必须是数组");
            }
            Set<String> localNames = new HashSet<>();
            for (JsonNode binding : bindingsNode) {
                rejectUnknown(binding, schemaVersion == 1 ? V1_BINDING_FIELDS : V2_BINDING_FIELDS, "MCP 工具绑定");
                McpToolBindingSnapshot decoded = new McpToolBindingSnapshot(
                        UUID.fromString(exactText(binding, "installationId")),
                        UUID.fromString(exactText(binding, "snapshotId")),
                        exactLong(binding, "installationVersion"),
                        schemaVersion == 1 ? LEGACY_RUNTIME_BINDING_INSTANCE_ID
                                : UUID.fromString(exactText(binding, "runtimeBindingInstanceId")),
                        schemaVersion == 1 ? 0 : exactLong(binding, "runtimeBindingRevision"),
                        exactText(binding, "localToolName"), exactText(binding, "remoteToolName"),
                        ToolRiskLevel.valueOf(exactText(binding, "riskLevel")),
                        capabilities(binding, "requiredCapabilities"),
                        Instant.parse(exactText(binding, "bindingCreatedAt")));
                if (!localNames.add(decoded.localToolName())) {
                    throw new IllegalArgumentException("bindings 包含重复 localToolName");
                }
                bindings.add(decoded);
            }
            McpCatalogSnapshot snapshot = new McpCatalogSnapshot(
                    schemaVersion, exactActor, exactWorkspace,
                    Instant.parse(exactText(parsed, "installationsUpdatedAt")), bindings,
                    exactText(parsed, "snapshotSha256"));
            if (!actorUserId.equals(exactActor) || !workspaceId.equals(exactWorkspace)) {
                throw new IllegalArgumentException("MCP 目录快照身份不匹配");
            }
            if (!snapshot.snapshotSha256().equals(sha256(unsigned(snapshot)))) {
                throw new IllegalArgumentException("MCP 目录快照摘要不匹配");
            }
            return snapshot;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("MCP 目录快照解码失败", exception);
        }
    }

    private ObjectNode unsigned(McpCatalogSnapshot snapshot) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", snapshot.schemaVersion());
        root.put("actorUserId", snapshot.actorUserId());
        root.put("workspaceId", snapshot.workspaceId().toString());
        root.put("installationsUpdatedAt", snapshot.installationsUpdatedAt().toString());
        ArrayNode bindings = root.putArray("bindings");
        snapshot.bindings().stream()
                .sorted(Comparator.comparing(McpToolBindingSnapshot::localToolName))
                .forEach(binding -> {
                    ObjectNode node = bindings.addObject();
                    node.put("installationId", binding.installationId().toString());
                    node.put("snapshotId", binding.snapshotId().toString());
                    node.put("installationVersion", binding.installationVersion());
                    if (snapshot.schemaVersion() == 2) {
                        node.put("runtimeBindingInstanceId", binding.runtimeBindingInstanceId().toString());
                        node.put("runtimeBindingRevision", binding.runtimeBindingRevision());
                    }
                    node.put("localToolName", binding.localToolName());
                    node.put("remoteToolName", binding.remoteToolName());
                    node.put("riskLevel", binding.riskLevel().name());
                    ArrayNode capabilities = node.putArray("requiredCapabilities");
                    binding.requiredCapabilities().stream().map(Enum::name).sorted().forEach(capabilities::add);
                    node.put("bindingCreatedAt", binding.bindingCreatedAt().toString());
                });
        return root;
    }

    private String sha256(JsonNode node) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(node)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static Set<RequiredCapability> capabilities(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(field + " 必须是数组");
        }
        Set<RequiredCapability> result = new HashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException(field + " 包含非法值");
            }
            if (!result.add(RequiredCapability.valueOf(item.textValue()))) {
                throw new IllegalArgumentException(field + " 包含重复值");
            }
        }
        return Set.copyOf(result);
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String label) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(label + " 必须是 JSON object");
        }
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(label + " 包含未知字段: " + field);
            }
        }
    }

    private static String exactText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " 必须是非空字符串");
        }
        return value.textValue();
    }

    private static int exactInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isInt()) throw new IllegalArgumentException(field + " 必须是整数");
        return value.intValue();
    }

    private static long exactLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) throw new IllegalArgumentException(field + " 必须是整数");
        return value.longValue();
    }
}
