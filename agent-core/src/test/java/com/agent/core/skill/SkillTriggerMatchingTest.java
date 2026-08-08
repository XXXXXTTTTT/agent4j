package com.agent.core.skill;

import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.ToolRiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SkillTriggerMatchingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void matchesOriginalCaseSensitiveSubstringWithoutUnicodeNormalization() throws Exception {
        try (ToolRegistry registry = registry("first.tool", "second.tool")) {
            SkillCatalog catalog = new SkillCatalog(List.of(
                    skill("rain", List.of("下雨"), "first.tool"),
                    skill("accent", List.of("é"), "second.tool")), registry, mapper);

            assertThat(catalog.resolve("明天下雨", Set.of()).activatedSkills())
                    .extracting(ActivatedSkill::name).containsExactly("rain");
            assertThat(catalog.resolve("下雨", Set.of()).activatedSkills())
                    .extracting(ActivatedSkill::name).containsExactly("rain");
            assertThat(catalog.resolve("明天 É", Set.of()).activatedSkills()).isEmpty();
            assertThat(catalog.resolve("e\u0301", Set.of()).activatedSkills()).isEmpty();
        }
    }

    @Test
    void activatesMultipleMatchingSkillsInNaturalNameOrderAndDeduplicates() throws Exception {
        try (ToolRegistry registry = registry("a.tool", "b.tool")) {
            SkillCatalog catalog = new SkillCatalog(List.of(
                    skill("zeta", List.of("天气"), "a.tool"),
                    skill("alpha", List.of("天"), "b.tool")), registry, mapper);

            assertThat(catalog.resolve("天气", Set.of("zeta")).activatedSkills())
                    .extracting(ActivatedSkill::name).containsExactly("alpha", "zeta");
        }
    }

    @Test
    void skillWithoutTriggersRequiresExplicitName() throws Exception {
        try (ToolRegistry registry = registry("rare.tool")) {
            SkillCatalog catalog = new SkillCatalog(
                    List.of(skill("rare", List.of(), "rare.tool")), registry, mapper);
            assertThat(catalog.resolve("rare", Set.of()).activatedSkills()).isEmpty();
            assertThat(catalog.resolve("rare", Set.of("rare")).activatedSkills())
                    .extracting(ActivatedSkill::name).containsExactly("rare");
        }
    }

    private SkillDefinition skill(String name, List<String> triggers, String tool) {
        return new SkillDefinition(name, "1.0.0", name, triggers, List.of(tool), "策略 " + name);
    }

    private ToolRegistry registry(String... names) {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        for (String name : names) {
            registry.register(new ToolDefinition(name, name,
                    mapper.createObjectNode().put("type", "object"), Set.of(), ToolRiskLevel.LOW,
                    Duration.ofSeconds(1), (call, context) -> mapper.createObjectNode().put("ok", true)));
        }
        return registry;
    }
}
