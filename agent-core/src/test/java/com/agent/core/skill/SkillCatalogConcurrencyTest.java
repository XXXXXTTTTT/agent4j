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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SkillCatalogConcurrencyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void concurrentReadOperationsReturnEquivalentImmutableContexts() throws Exception {
        try (ToolRegistry registry = registry()) {
            SkillCatalog catalog = new SkillCatalog(List.of(new SkillDefinition(
                    "weather", "1.0.0", "天气顾问", List.of("天气"), List.of("weather.lookup"), "先查询天气")),
                    registry, mapper);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<SkillPromptContext>> futures = IntStream.range(0, 32)
                        .mapToObj(index -> executor.submit(() -> catalog.resolve("明天天气", Set.of())))
                        .toList();
                String fingerprint = futures.getFirst().get().fingerprint();
                for (Future<SkillPromptContext> future : futures) {
                    assertThat(future.get().fingerprint()).isEqualTo(fingerprint);
                }
            }
        }
    }

    @Test
    void schemaChangesThroughAccessorDoNotMutateConcurrentCatalogSnapshot() throws Exception {
        try (ToolRegistry registry = registry()) {
            SkillCatalog catalog = new SkillCatalog(List.of(new SkillDefinition(
                    "weather", "1.0.0", "天气顾问", List.of("天气"), List.of("weather.lookup"), "先查询天气")),
                    registry, mapper);
            var schema = catalog.resolve("天气", Set.of()).activatedSkills().getFirst().tools().getFirst().inputSchema();
            ((com.fasterxml.jackson.databind.node.ObjectNode) schema).put("changed", true);
            assertThat(catalog.resolve("天气", Set.of()).activationSection()).doesNotContain("changed");
        }
    }

    private ToolRegistry registry() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new ToolDefinition("weather.lookup", "查询天气",
                mapper.createObjectNode().put("type", "object"), Set.of(), ToolRiskLevel.LOW,
                Duration.ofSeconds(1), (call, context) -> mapper.createObjectNode().put("ok", true)));
        return registry;
    }
}
