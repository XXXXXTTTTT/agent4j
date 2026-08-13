package com.agent.core.skill;

import com.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 负责 Skill 目录快照的规范 JSON 编码、摘要和严格解码。 */
public final class SkillCatalogSnapshotCodec {
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion", "actorUserId", "workspaceId", "installationsUpdatedAt",
            "toolRegistryRevision", "definitions", "snapshotSha256");
    private static final Set<String> DEFINITION_FIELDS = Set.of(
            "name", "version", "description", "triggers", "toolNames", "promptFragment");

    private final ObjectMapper objectMapper;

    public SkillCatalogSnapshotCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 生成字段顺序固定且包含摘要的 UTF-8 JSON。 */
    public String encode(SkillCatalogSnapshot snapshot) {
        ObjectNode unsigned = unsigned(snapshot);
        String digest = sha256(unsigned);
        ObjectNode signed = unsigned.deepCopy();
        signed.put("snapshotSha256", digest);
        try {
            return objectMapper.writeValueAsString(signed);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Skill 目录快照编码失败", exception);
        }
    }

    /** 严格校验快照摘要、主体边界和当前工具注册。 */
    public SkillCatalogSnapshot decode(
            String json, String actorUserId, UUID workspaceId, ToolRegistry toolRegistry) {
        try {
            JsonNode parsed = objectMapper.readTree(json);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException("Skill 目录快照必须是 JSON object");
            }
            rejectUnknown(parsed, TOP_LEVEL_FIELDS, "Skill 目录快照");
            int schemaVersion = exactInt(parsed, "schemaVersion");
            String exactActor = exactText(parsed, "actorUserId");
            UUID exactWorkspace = UUID.fromString(exactText(parsed, "workspaceId"));
            Instant updatedAt = Instant.parse(exactText(parsed, "installationsUpdatedAt"));
            long revision = exactLong(parsed, "toolRegistryRevision");
            JsonNode definitionsNode = parsed.get("definitions");
            if (definitionsNode == null || !definitionsNode.isArray()) {
                throw new IllegalArgumentException("definitions 必须是数组");
            }
            List<SkillDefinition> definitions = new ArrayList<>();
            for (JsonNode node : definitionsNode) {
                rejectUnknown(node, DEFINITION_FIELDS, "Skill definition");
                definitions.add(new SkillDefinition(
                        exactText(node, "name"), exactText(node, "version"), exactText(node, "description"),
                        textList(node, "triggers"), textList(node, "toolNames"), exactText(node, "promptFragment")));
            }
            String digest = exactText(parsed, "snapshotSha256");
            SkillCatalogSnapshot snapshot = new SkillCatalogSnapshot(
                    schemaVersion, exactActor, exactWorkspace, updatedAt, revision, definitions, digest);
            if (!actorUserId.equals(exactActor) || !workspaceId.equals(exactWorkspace)) {
                throw new IllegalArgumentException("Skill 目录快照身份不匹配");
            }
            if (!digest.equals(sha256(unsigned(snapshot)))) {
                throw new IllegalArgumentException("Skill 目录快照摘要不匹配");
            }
            if (toolRegistry == null) {
                throw new NullPointerException("toolRegistry 不能为空");
            }
            if (!definitions.isEmpty()) {
                new SkillCatalog(definitions, toolRegistry, objectMapper);
            }
            return snapshot;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Skill 目录快照解码失败", exception);
        }
    }

    private ObjectNode unsigned(SkillCatalogSnapshot snapshot) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", snapshot.schemaVersion());
        root.put("actorUserId", snapshot.actorUserId());
        root.put("workspaceId", snapshot.workspaceId().toString());
        root.put("installationsUpdatedAt", snapshot.installationsUpdatedAt().toString());
        root.put("toolRegistryRevision", snapshot.toolRegistryRevision());
        ArrayNode definitions = root.putArray("definitions");
        snapshot.definitions().stream().sorted(Comparator.comparing(SkillDefinition::name)).forEach(definition -> {
            ObjectNode node = definitions.addObject();
            node.put("name", definition.name());
            node.put("version", definition.version());
            node.put("description", definition.description());
            array(node, "triggers", definition.triggers());
            array(node, "toolNames", definition.toolNames());
            node.put("promptFragment", definition.promptFragment());
        });
        return root;
    }

    private void array(ObjectNode object, String field, List<String> values) {
        ArrayNode array = object.putArray(field);
        values.forEach(array::add);
    }

    private String sha256(JsonNode node) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(node)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
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

    private static List<String> textList(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) throw new IllegalArgumentException(field + " 必须是数组");
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.textValue().isBlank() || !seen.add(item.textValue())) {
                throw new IllegalArgumentException(field + " 包含非法或重复值");
            }
            result.add(item.textValue());
        }
        return List.copyOf(result);
    }
}
