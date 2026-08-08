package com.agent.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillDefinitionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validatesCoreSemVerAndExactNames() {
        assertThatThrownBy(() -> definition("Weather", "1.0.0", List.of("天气"), List.of("weather.lookup")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> definition("weather", "01.2.3", List.of("天气"), List.of("weather.lookup")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> definition("weather", "1.2.3-beta", List.of("天气"), List.of("weather.lookup")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> definition("weather", "1.2.3", List.of("天气"), List.of("Weather.Lookup")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolNames");
    }

    @Test
    void preservesExactTextAndFreezesOrderedLists() {
        List<String> triggers = new ArrayList<>(List.of(" 天气 "));
        List<String> tools = new ArrayList<>(List.of("weather.lookup", "weather.advice"));

        SkillDefinition definition = definition("weather", "1.2.3", triggers, tools);
        triggers.add("下雨");
        tools.clear();

        assertThat(definition.triggers()).containsExactly(" 天气 ");
        assertThat(definition.toolNames()).containsExactly("weather.lookup", "weather.advice");
        assertThatThrownBy(() -> definition.triggers().add("新增"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsBlankDuplicateAndMissingCollections() {
        assertThatThrownBy(() -> definition("weather", "1.0.0", List.of(" "), List.of("weather.lookup")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> definition("weather", "1.0.0", List.of("天气", "天气"), List.of("weather.lookup")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> definition("weather", "1.0.0", List.of("天气"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillDefinition(
                "weather", "1.0.0", "天气顾问", List.of(), null, "先查询天气"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void copiesToolSchemaOnConstructionAndAccess() {
        var schema = mapper.createObjectNode().put("type", "object");
        SkillToolMetadata metadata = new SkillToolMetadata("weather.lookup", "查询天气", schema);
        schema.put("changed", true);
        var returned = metadata.inputSchema();
        ((com.fasterxml.jackson.databind.node.ObjectNode) returned).put("changed", true);

        assertThat(metadata.inputSchema().has("changed")).isFalse();
    }

    @Test
    void freezesPromptContextCollectionsAndValidatesFingerprint() {
        SkillSummary summary = new SkillSummary("weather", "1.0.0", "天气顾问");
        ActivatedSkill activated = new ActivatedSkill(
                "weather", "1.0.0",
                List.of(new SkillToolMetadata(
                        "weather.lookup", "查询天气", mapper.createObjectNode().put("type", "object"))),
                "先查询天气");
        SkillPromptContext context = new SkillPromptContext(
                "- weather@1.0.0: 天气顾问", "激活 weather", List.of(summary), List.of(activated), "a".repeat(64));

        assertThatThrownBy(() -> context.availableSkills().add(summary))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new SkillPromptContext("", "", List.of(), List.of(), "ABC"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SkillDefinition definition(
            String name,
            String version,
            List<String> triggers,
            List<String> toolNames) {
        return new SkillDefinition(name, version, "天气顾问", triggers, toolNames, "先查询天气");
    }
}
