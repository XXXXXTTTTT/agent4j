package com.agent.eval;

import com.agent.core.skill.SkillCatalog;
import com.agent.core.skill.SkillDefinition;
import com.agent.core.skill.SkillRegistrationException;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolInvocationContext;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.ToolResult;
import com.agent.core.tool.ToolRiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 对 Skill 发现、激活和 Registry 治理执行确定性 EDD。 */
@Tag("edd")
class SkillCatalogEddTest {

    private static final UUID RUN_ID = UUID.fromString("75000000-0000-0000-0000-000000000001");
    private static final Set<String> REPORT_FIELDS = Set.of(
            "taskId", "status", "activatedSkills", "exposedTools", "fingerprint", "passed");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void evaluatesSevenSkillScenariosAndWritesStrictReport() throws Exception {
        List<EddResult> results = List.of(
                discovery(), trigger(), explicit(), unmatched(), collision(), unknownTool(), governance());
        Path report = Path.of("target", "edd", "skill-catalog-edd.json");
        Files.createDirectories(report.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of("scenarios", results));

        JsonNode json = mapper.readTree(report.toFile());
        assertThat(json.path("scenarios")).hasSize(7);
        for (JsonNode scenario : json.path("scenarios")) {
            List<String> fields = new ArrayList<>();
            scenario.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrderElementsOf(REPORT_FIELDS);
        }
        assertThat(results).allSatisfy(result -> assertThat(result.passed()).as(result.taskId()).isTrue());
        assertThat(report).isRegularFile();
    }

    private EddResult discovery() throws Exception {
        try (ToolRegistry registry = registry(ToolRiskLevel.LOW)) {
            var context = catalog(registry).resolve("没有技能触发", Set.of());
            return result("skill.discovery", "DISCOVERED", context, true,
                    context.activatedSkills().isEmpty() && context.discoverySection().contains("weather"));
        }
    }

    private EddResult trigger() throws Exception {
        try (ToolRegistry registry = registry(ToolRiskLevel.LOW)) {
            var context = catalog(registry).resolve("明天天气如何", Set.of());
            return result("skill.trigger", "ACTIVATED", context, true,
                    names(context).equals(List.of("weather")) && tools(context).equals(List.of("weather.lookup")));
        }
    }

    private EddResult explicit() throws Exception {
        try (ToolRegistry registry = registry(ToolRiskLevel.LOW)) {
            var context = catalog(registry).resolve("无关问题", Set.of("weather"));
            return result("skill.explicit", "ACTIVATED", context, true,
                    names(context).equals(List.of("weather")));
        }
    }

    private EddResult unmatched() throws Exception {
        try (ToolRegistry registry = registry(ToolRiskLevel.LOW)) {
            var context = catalog(registry).resolve("普通聊天", Set.of());
            return result("skill.unmatched", "UNMATCHED", context, true,
                    context.activatedSkills().isEmpty() && context.activationSection().isEmpty());
        }
    }

    private EddResult collision() throws Exception {
        try (ToolRegistry registry = registry(ToolRiskLevel.LOW)) {
            try {
                new SkillCatalog(List.of(
                        new SkillDefinition("weather", "1.0.0", "天气", List.of("天气"), List.of("weather.lookup"), "策略"),
                        new SkillDefinition("travel", "1.0.0", "出行", List.of("天气"), List.of("weather.lookup"), "策略")),
                        registry, mapper);
                return new EddResult("skill.collision", "ACCEPTED", List.of(), List.of(), "", false);
            } catch (SkillRegistrationException expected) {
                return new EddResult("skill.collision", "REJECTED", List.of(), List.of(), "", true);
            }
        }
    }

    private EddResult unknownTool() throws Exception {
        try (ToolRegistry registry = registry(ToolRiskLevel.LOW)) {
            try {
                new SkillCatalog(List.of(new SkillDefinition(
                        "weather", "1.0.0", "天气", List.of("天气"), List.of("missing.tool"), "策略")),
                        registry, mapper);
                return new EddResult("skill.unknown-tool", "ACCEPTED", List.of(), List.of(), "", false);
            } catch (SkillRegistrationException expected) {
                return new EddResult("skill.unknown-tool", "REJECTED", List.of(), List.of(), "", true);
            }
        }
    }

    private EddResult governance() throws Exception {
        try (ToolRegistry registry = registry(ToolRiskLevel.HIGH)) {
            var context = catalog(registry).resolve("天气", Set.of());
            ToolResult result = registry.execute(
                    new ToolCall("skill-governance", "weather.lookup", mapper.createObjectNode()),
                    new ToolInvocationContext(RUN_ID, "skill-edd", "user-edd", Path.of("."), Set.of(), false));
            return result("skill.registry-governance", result.status().name(), context, true,
                    result.status().name().equals("APPROVAL_REQUIRED")
                            && context.activatedSkills().size() == 1);
        }
    }

    private EddResult result(String taskId, String status, com.agent.core.skill.SkillPromptContext context,
                             boolean expectedFingerprint, boolean passed) {
        return new EddResult(taskId, status, names(context), tools(context),
                expectedFingerprint ? context.fingerprint() : "", passed);
    }

    private SkillCatalog catalog(ToolRegistry registry) {
        return new SkillCatalog(List.of(new SkillDefinition(
                "weather", "1.0.0", "天气顾问", List.of("天气", "下雨"),
                List.of("weather.lookup"), "先查询天气，再给出出行建议")), registry, mapper);
    }

    private ToolRegistry registry(ToolRiskLevel riskLevel) {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new ToolDefinition("weather.lookup", "查询天气",
                mapper.createObjectNode().put("type", "object"), Set.of(), riskLevel,
                Duration.ofSeconds(1), (call, context) -> mapper.createObjectNode().put("ok", true)));
        return registry;
    }

    private List<String> names(com.agent.core.skill.SkillPromptContext context) {
        return context.activatedSkills().stream().map(skill -> skill.name()).toList();
    }

    private List<String> tools(com.agent.core.skill.SkillPromptContext context) {
        return context.activatedSkills().stream().flatMap(skill -> skill.tools().stream())
                .map(tool -> tool.name()).toList();
    }

    private record EddResult(
            String taskId,
            String status,
            List<String> activatedSkills,
            List<String> exposedTools,
            String fingerprint,
            boolean passed) {
    }
}
