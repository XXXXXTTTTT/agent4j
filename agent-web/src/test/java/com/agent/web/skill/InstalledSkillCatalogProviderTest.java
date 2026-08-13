package com.agent.web.skill;

import com.agent.core.skill.SkillCatalogSnapshot;
import com.agent.core.skill.SkillDefinition;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolRiskLevel;
import com.agent.web.capability.CapabilityManagementAuditEvent;
import com.agent.web.capability.CapabilityManagementAuditSink;
import com.agent.web.capability.InstallationScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InstalledSkillCatalogProviderTest {
    private static final UUID WORKSPACE_ID = UUID.fromString("31748a76-5a0b-4d5b-b984-d0b3adfc854e");
    private static final UUID OTHER_WORKSPACE_ID = UUID.fromString("c8ae7c2d-af15-4114-8667-a97b6d27c304");
    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fallsBackToBuiltInsAndAuditsWithoutSkillBodyWhenContentShaDoesNotMatch() {
        String bodyMarker = "PRIVATE_SKILL_BODY_MUST_NOT_APPEAR_IN_AUDIT";
        String content = skillMarkdown("code.patch", bodyMarker);
        FakeRepository repository = new FakeRepository(List.of(installedSkill(content, "0".repeat(64))), NOW);
        CapturingAuditSink auditSink = new CapturingAuditSink();

        try (DefaultToolRegistry tools = toolRegistry("code.patch")) {
            InstalledSkillCatalogProvider provider = provider(repository, tools, auditSink);

            SkillCatalogSnapshot resolved = provider.resolve("user-a", WORKSPACE_ID);

            assertThat(resolved.definitions()).containsExactly(builtInSkill());
            assertRejectedWithoutBody(auditSink, bodyMarker);
        }
    }

    @Test
    void fallsBackToBuiltInsWhenExternalSkillReferencesAnUnregisteredTool() {
        String content = skillMarkdown("missing.tool", "Use only the declared tool.");
        FakeRepository repository = new FakeRepository(List.of(installedSkill(content, sha256(content))), NOW);
        CapturingAuditSink auditSink = new CapturingAuditSink();

        try (DefaultToolRegistry tools = toolRegistry("code.patch")) {
            InstalledSkillCatalogProvider provider = provider(repository, tools, auditSink);

            SkillCatalogSnapshot resolved = provider.resolve("user-a", WORKSPACE_ID);

            assertThat(resolved.definitions()).containsExactly(builtInSkill());
            assertRejectedWithoutBody(auditSink, "Use only the declared tool.");
        }
    }

    @Test
    void rejectsInvalidApprovedSnapshotWithItsCurrentVersion() {
        String oldContent = "---\nname: external.skill\ndescription: External skill\n"
                + "tools:\n  - code.patch\n---\n"
                + "Legacy skill content without required fields.";
        FakeRepository repository = new FakeRepository(
                List.of(installedSkill(oldContent, sha256(oldContent))), NOW);
        CapturingAuditSink auditSink = new CapturingAuditSink();

        try (DefaultToolRegistry tools = toolRegistry("code.patch")) {
            SkillCatalogSnapshot resolved = provider(repository, tools, auditSink)
                    .resolve("user-a", WORKSPACE_ID);

            assertThat(resolved.definitions()).containsExactly(builtInSkill());
            assertThat(repository.rejectedInstallationIds)
                    .containsExactly(UUID.fromString("b09b8cc8-4a8a-47f8-a1b3-b4c2d44c6238"));
            assertThat(repository.rejectedExpectedVersions).containsExactly(0L);
            assertThat(repository.rejectionEvents).singleElement()
                    .extracting(CapabilityManagementAuditEvent::eventType)
                    .isEqualTo("SKILL_SNAPSHOT_REJECTED");
        }
    }

    @Test
    void separatesCachedCatalogsByActorWorkspaceInstallationUpdateAndToolRegistryRevision() {
        FakeRepository repository = new FakeRepository(List.of(), NOW);
        CapturingAuditSink auditSink = new CapturingAuditSink();

        try (DefaultToolRegistry tools = toolRegistry("code.patch")) {
            InstalledSkillCatalogProvider provider = provider(repository, tools, auditSink);

            SkillCatalogSnapshot first = provider.resolve("user-a", WORKSPACE_ID);
            SkillCatalogSnapshot sameKey = provider.resolve("user-a", WORKSPACE_ID);
            SkillCatalogSnapshot anotherActor = provider.resolve("user-b", WORKSPACE_ID);
            SkillCatalogSnapshot anotherWorkspace = provider.resolve("user-a", OTHER_WORKSPACE_ID);
            repository.updatedAt = NOW.plusSeconds(1);
            SkillCatalogSnapshot anotherInstallationUpdate = provider.resolve("user-a", WORKSPACE_ID);
            tools.register(toolDefinition("browser.open"));
            SkillCatalogSnapshot anotherToolRegistryRevision = provider.resolve("user-a", WORKSPACE_ID);

            assertThat(sameKey).isSameAs(first);
            assertThat(anotherActor).isNotSameAs(first);
            assertThat(anotherWorkspace).isNotSameAs(first);
            assertThat(anotherInstallationUpdate).isNotSameAs(first);
            assertThat(anotherToolRegistryRevision).isNotSameAs(anotherInstallationUpdate);
            assertThat(repository.findInstalledSkillsCalls).isEqualTo(5);
            assertThat(anotherActor.actorUserId()).isEqualTo("user-b");
            assertThat(anotherWorkspace.workspaceId()).isEqualTo(OTHER_WORKSPACE_ID);
            assertThat(anotherInstallationUpdate.installationsUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
            assertThat(anotherToolRegistryRevision.toolRegistryRevision())
                    .isGreaterThan(anotherInstallationUpdate.toolRegistryRevision());
            assertThat(auditSink.events).isEmpty();
        }
    }

    private InstalledSkillCatalogProvider provider(
            FakeRepository repository,
            DefaultToolRegistry tools,
            CapturingAuditSink auditSink) {
        return new InstalledSkillCatalogProvider(repository, tools, objectMapper, List.of(builtInSkill()), auditSink);
    }

    private static SkillDefinition builtInSkill() {
        return new SkillDefinition("builtin.summary", "1.0.0", "Built in summary skill",
                List.of("summarize"), List.of("code.patch"), "Summarize the supplied content faithfully.");
    }

    private DefaultToolRegistry toolRegistry(String toolName) {
        DefaultToolRegistry tools = new DefaultToolRegistry();
        tools.register(toolDefinition(toolName));
        return tools;
    }

    private ToolDefinition toolDefinition(String name) {
        return new ToolDefinition(name, "测试工具", objectMapper.createObjectNode().put("type", "object"),
                java.util.Set.of(), ToolRiskLevel.LOW, Duration.ofSeconds(1),
                (call, context) -> objectMapper.createObjectNode().put("ok", true));
    }

    private static InstalledSkillRecord installedSkill(String content, String contentSha256) {
        UUID snapshotId = UUID.fromString("0cbaf180-d1ca-4d50-a3c9-c0b9fa2c6d5b");
        SkillSnapshotRecord snapshot = new SkillSnapshotRecord(
                snapshotId, URI.create("https://github.com/example/skills"), "example/skills",
                "760b09d29b17724a5df7b319ab386d9221c83e1d", "f5f169e6f4a49654544d4a4579ce9df8442309e4",
                "SKILL.md", "MIT", contentSha256, "External skill", List.of(toolName(content)), content, NOW);
        SkillInstallationRecord installation = new SkillInstallationRecord(
                UUID.fromString("b09b8cc8-4a8a-47f8-a1b3-b4c2d44c6238"), snapshotId, InstallationScope.WORKSPACE,
                WORKSPACE_ID, "user-a", SkillInstallationStatus.APPROVED, "f".repeat(64), NOW, NOW, NOW);
        return new InstalledSkillRecord(installation, snapshot);
    }

    private static String toolName(String content) {
        return content.lines()
                .dropWhile(line -> !"tools:".equals(line))
                .skip(1)
                .findFirst()
                .orElseThrow()
                .substring(4);
    }

    private static String skillMarkdown(String toolName, String body) {
        return """
                ---
                name: external.skill
                version: 1.0.0
                description: External skill
                triggers:
                  - external request
                tools:
                  - %s
                ---
                %s
                """.formatted(toolName, body).strip();
    }

    private static void assertRejectedWithoutBody(CapturingAuditSink auditSink, String bodyMarker) {
        assertThat(auditSink.events).hasSize(1);
        CapabilityManagementAuditEvent event = auditSink.events.getFirst();
        assertThat(event.eventType()).isEqualTo("SKILL_CATALOG_REJECTED");
        assertThat(event.result()).isEqualTo("REJECTED");
        assertThat(event.detailSha256()).matches("[0-9a-f]{64}").doesNotContain(bodyMarker);
        assertThat(event.sourceCommitSha()).doesNotContain(bodyMarker);
        assertThat(event.fromStatus()).doesNotContain(bodyMarker);
        assertThat(event.toStatus()).doesNotContain(bodyMarker);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class FakeRepository implements SkillInstallationRepository {
        private final List<InstalledSkillRecord> records;
        private Instant updatedAt;
        private int findInstalledSkillsCalls;
        private final List<UUID> rejectedInstallationIds = new ArrayList<>();
        private final List<Long> rejectedExpectedVersions = new ArrayList<>();
        private final List<CapabilityManagementAuditEvent> rejectionEvents = new ArrayList<>();

        private FakeRepository(List<InstalledSkillRecord> records, Instant updatedAt) {
            this.records = List.copyOf(records);
            this.updatedAt = updatedAt;
        }

        @Override
        public List<InstalledSkillRecord> findInstalledSkills(String actorUserId, UUID workspaceId) {
            findInstalledSkillsCalls++;
            return records;
        }

        @Override
        public Instant installationsUpdatedAt(String actorUserId, UUID workspaceId) {
            return updatedAt;
        }

        @Override
        public SkillInstallationRecord rejectInvalidSnapshot(
                UUID skillInstallationId,
                String actorUserId,
                UUID workspaceId,
                long expectedVersion,
                CapabilityManagementAuditEvent auditEvent) {
            rejectedInstallationIds.add(skillInstallationId);
            rejectedExpectedVersions.add(expectedVersion);
            rejectionEvents.add(auditEvent);
            return records.stream()
                    .map(InstalledSkillRecord::installation)
                    .filter(value -> value.skillInstallationId().equals(skillInstallationId))
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public SkillInstallationRecord confirmSkill(
                SkillSnapshotRecord snapshot,
                SkillInstallationRecord installation,
                CapabilityManagementAuditEvent auditEvent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SkillInstallationRecord> findInstallations(String actorUserId, UUID workspaceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SkillInstallationRecord removeInstallation(
                UUID skillInstallationId,
                String actorUserId,
                UUID workspaceId,
                long expectedVersion,
                CapabilityManagementAuditEvent auditEvent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SkillInstallationRecord transition(
                UUID skillInstallationId,
                long expectedVersion,
                SkillInstallationStatus from,
                SkillInstallationStatus to) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class CapturingAuditSink implements CapabilityManagementAuditSink {
        private final List<CapabilityManagementAuditEvent> events = new ArrayList<>();

        @Override
        public void record(CapabilityManagementAuditEvent event) {
            events.add(event);
        }
    }
}
