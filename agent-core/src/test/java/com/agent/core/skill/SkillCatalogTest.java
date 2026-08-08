package com.agent.core.skill;

import com.agent.core.tool.DefaultToolAuthorizer;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.ToolRiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillCatalogTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exposesOnlySummaryUntilTriggerActivation() throws Exception {
        try (ToolRegistry registry = registry("weather.lookup")) {
            SkillCatalog catalog = catalog(registry, skill("weather", List.of("天气"), List.of("weather.lookup")));

            SkillPromptContext discovery = catalog.resolve("今天吃什么", Set.of());
            assertThat(discovery.availableSkills()).extracting(SkillSummary::name).containsExactly("weather");
            assertThat(discovery.discoverySection()).doesNotContain("weather.lookup", "先查询天气");
            assertThat(discovery.activationSection()).isEmpty();
            assertThat(discovery.activatedSkills()).isEmpty();

            SkillPromptContext activated = catalog.resolve("明天天气怎么样", Set.of());
            assertThat(activated.activatedSkills()).extracting(ActivatedSkill::name).containsExactly("weather");
            assertThat(activated.activationSection()).contains("weather.lookup", "先查询天气", "inputSchema");
            assertThat(activated.fingerprint()).hasSize(64).matches("[0-9a-f]{64}");
        }
    }

    @Test
    void rejectsDuplicateTriggerAndUnknownToolWithoutPublishingDirectory() throws Exception {
        try (ToolRegistry registry = registry("weather.lookup")) {
            assertThatThrownBy(() -> catalog(registry,
                    skill("weather", List.of("天气"), List.of("weather.lookup")),
                    skill("travel", List.of("天气"), List.of("weather.lookup"))))
                    .isInstanceOf(SkillRegistrationException.class);
            assertThatThrownBy(() -> catalog(registry,
                    skill("travel", List.of(), List.of("missing.tool"))))
                    .isInstanceOf(SkillRegistrationException.class)
                    .hasMessageContaining("missing.tool");
        }
    }

    @Test
    void findsByExactNameAndActivatesExplicitlyNamedRareSkill() throws Exception {
        try (ToolRegistry registry = registry("weather.lookup")) {
            SkillDefinition rare = skill("weather", List.of(), List.of("weather.lookup"));
            SkillCatalog catalog = catalog(registry, rare);

            assertThat(catalog.find("weather")).contains(rare);
            assertThat(catalog.find("Weather")).isEmpty();
            assertThat(catalog.resolve("无关请求", Set.of("weather")).activatedSkills())
                    .extracting(ActivatedSkill::name).containsExactly("weather");
            assertThatThrownBy(() -> catalog.resolve("无关请求", Set.of("missing")))
                    .isInstanceOf(SkillNotFoundException.class);
        }
    }

    @Test
    void returnsDefensiveSchemaCopiesFromActivatedMetadata() throws Exception {
        try (ToolRegistry registry = registry("weather.lookup")) {
            SkillPromptContext context = catalog(registry,
                    skill("weather", List.of("天气"), List.of("weather.lookup")))
                    .resolve("天气", Set.of());
            var schema = context.activatedSkills().getFirst().tools().getFirst().inputSchema();
            ((com.fasterxml.jackson.databind.node.ObjectNode) schema).put("changed", true);
            assertThat(context.activatedSkills().getFirst().tools().getFirst().inputSchema().has("changed"))
                    .isFalse();
        }
    }

    private SkillCatalog catalog(ToolRegistry registry, SkillDefinition... definitions) {
        return new SkillCatalog(List.of(definitions), registry, mapper);
    }

    private SkillDefinition skill(String name, List<String> triggers, List<String> tools) {
        return new SkillDefinition(name, "1.0.0", "天气顾问", triggers, tools, "先查询天气");
    }

    private ToolRegistry registry(String... names) {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        for (String name : names) {
            registry.register(new ToolDefinition(name, "工具 " + name,
                    mapper.createObjectNode().put("type", "object"), Set.of(), ToolRiskLevel.LOW,
                    Duration.ofSeconds(1), (call, context) -> mapper.createObjectNode().put("ok", true)));
        }
        return registry;
    }
}
