package com.agent.web.skill;

import com.agent.core.skill.SkillCatalogProvider;
import com.agent.core.skill.SkillCatalogSnapshot;
import com.agent.core.skill.SkillDefinition;
import com.agent.core.tool.ToolRegistry;
import com.agent.web.capability.CapabilityManagementAuditEvent;
import com.agent.web.capability.CapabilityManagementAuditSink;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 从已批准安装重建按主体和工作区冻结的 Skill 目录。 */
public final class InstalledSkillCatalogProvider implements SkillCatalogProvider {
    private final SkillInstallationRepository repository;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final List<SkillDefinition> builtIns;
    private final CapabilityManagementAuditSink auditSink;
    private final ConcurrentHashMap<CacheKey, SkillCatalogSnapshot> cache = new ConcurrentHashMap<>();

    public InstalledSkillCatalogProvider(
            SkillInstallationRepository repository,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            List<SkillDefinition> builtIns,
            CapabilityManagementAuditSink auditSink) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.builtIns = List.copyOf(Objects.requireNonNull(builtIns, "builtIns 不能为空"));
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink 不能为空");
    }

    @Override
    public SkillCatalogSnapshot resolve(String actorUserId, UUID workspaceId) {
        Objects.requireNonNull(actorUserId, "actorUserId 不能为空");
        Objects.requireNonNull(workspaceId, "workspaceId 不能为空");
        Instant updatedAt = repository.installationsUpdatedAt(actorUserId, workspaceId);
        CacheKey key = new CacheKey(actorUserId, workspaceId, updatedAt, toolRegistry.revision());
        return cache.computeIfAbsent(key, ignored -> load(actorUserId, workspaceId, updatedAt));
    }

    private SkillCatalogSnapshot load(String actorUserId, UUID workspaceId, Instant updatedAt) {
        List<SkillDefinition> definitions = new ArrayList<>(builtIns);
        try {
            for (InstalledSkillRecord record : repository.findInstalledSkills(actorUserId, workspaceId)) {
                SkillSnapshotRecord snapshot = record.snapshot();
                String digest = sha256(snapshot.content());
                if (!digest.equals(snapshot.contentSha256())) {
                    throw new IllegalArgumentException("Skill 快照内容摘要不匹配: " + record.installation().skillInstallationId());
                }
                GitHubSkillContent content = GitHubSkillContent.parse(
                        snapshot.content(), toolRegistry.list().stream().map(value -> value.name()).collect(java.util.stream.Collectors.toSet()));
                if (!snapshot.summary().equals(content.summary())
                        || !snapshot.requestedToolNames().equals(content.requestedToolNames())) {
                    throw new IllegalArgumentException("Skill 快照派生字段不匹配");
                }
                definitions.add(content.definition());
            }
            definitions.sort(Comparator.comparing(SkillDefinition::name));
            if (!definitions.isEmpty()) {
                new com.agent.core.skill.SkillCatalog(definitions, toolRegistry, objectMapper);
            }
            SkillCatalogSnapshot unsigned = new SkillCatalogSnapshot(
                    1, actorUserId, workspaceId, updatedAt, toolRegistry.revision(), definitions, "");
            return unsigned;
        } catch (RuntimeException exception) {
            String detail = sha256(exception.getClass().getSimpleName() + ":" + exception.getMessage());
            auditSink.record(new CapabilityManagementAuditEvent(
                    "SKILL_CATALOG_REJECTED", actorUserId, workspaceId, null, null, null, "", "REJECTED",
                    Instant.now(), null, "", "", detail));
            SkillCatalogSnapshot fallback = new SkillCatalogSnapshot(
                    1, actorUserId, workspaceId, updatedAt, toolRegistry.revision(), builtIns, "");
            return fallback;
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record CacheKey(String actorUserId, UUID workspaceId, Instant updatedAt, long registryRevision) { }
}
