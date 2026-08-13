package com.agent.core.skill;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolRiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillCatalogSnapshotCodecTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void encodesCanonicalSnapshotAndDecodesBoundIdentity() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            registry.register(new ToolDefinition(
                    "code.patch", "修改代码", mapper.readTree("{\"type\":\"object\"}"),
                    Set.of(RequiredCapability.TOOL), ToolRiskLevel.LOW, Duration.ofSeconds(1),
                    (call, context) -> mapper.createObjectNode()));
            SkillCatalogSnapshot snapshot = new SkillCatalogSnapshot(
                    1, "user-1", WORKSPACE_ID, Instant.parse("2026-08-12T00:00:00Z"), 7,
                    List.of(new SkillDefinition("review-java", "1.2.0", "审查 Java 变更",
                            List.of("审查 Java"), List.of("code.patch"), "只审查当前工作区。")), "");
            SkillCatalogSnapshotCodec codec = new SkillCatalogSnapshotCodec(mapper);

            String encoded = codec.encode(snapshot);
            SkillCatalogSnapshot decoded = codec.decode(encoded, "user-1", WORKSPACE_ID, registry);

            assertThat(encoded)
                    .startsWith("{\"schemaVersion\":1,\"actorUserId\":\"user-1\",\"workspaceId\":\"00000000-0000-0000-0000-000000000001\",\"installationsUpdatedAt\":\"2026-08-12T00:00:00Z\",\"toolRegistryRevision\":7,\"definitions\":[{\"name\":\"review-java\",\"version\":\"1.2.0\",\"description\":\"审查 Java 变更\",\"triggers\":[\"审查 Java\"],\"toolNames\":[\"code.patch\"],\"promptFragment\":\"只审查当前工作区。\"}],\"snapshotSha256\":\"")
                    .endsWith("\"}");
            assertThat(decoded.snapshotSha256()).matches("[0-9a-f]{64}");
            assertThat(decoded.definitions()).extracting(SkillDefinition::name).containsExactly("review-java");
        }
    }

    @Test
    void rejectsUnknownFieldsDigestMismatchAndForeignIdentity() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            SkillCatalogSnapshotCodec codec = new SkillCatalogSnapshotCodec(mapper);
            String foreign = "{\"schemaVersion\":1,\"actorUserId\":\"other-user\",\"workspaceId\":\"00000000-0000-0000-0000-000000000001\",\"installationsUpdatedAt\":\"2026-08-12T00:00:00Z\",\"toolRegistryRevision\":0,\"definitions\":[],\"snapshotSha256\":\"0000000000000000000000000000000000000000000000000000000000000000\"}";

            assertThatThrownBy(() -> codec.decode(foreign, "user-1", WORKSPACE_ID, registry))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
