package com.agent.eval;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkTaskSetReaderTest {

    @Test
    void readsVersionedResourceWithAtLeastFiftyUniqueBusinessTasks() {
        InputStream resource = getClass().getResourceAsStream("/benchmark/tasks.jsonl");
        assertThat(resource).isNotNull();

        BenchmarkTaskSet set = new BenchmarkTaskSetReader().read(resource);

        assertThat(set.tasks()).hasSizeGreaterThanOrEqualTo(50);
        assertThat(set.tasks()).extracting(BenchmarkTask::category)
                .contains("CODE", "OPS", "RAG", "TRACE", "WEB");
        assertThat(set.tasks()).allSatisfy(task -> {
            assertThat(task.id()).isNotBlank();
            assertThat(task.prompt()).isNotBlank();
            assertThat(task.successCriteria()).isNotBlank();
        });
    }

    @Test
    void readsStrictMcpSkillRuntimeFixtureWithAuditableScenarioCoverage() {
        InputStream resource = getClass().getResourceAsStream("/benchmarks/mcp-skill-runtime.jsonl");
        assertThat(resource).isNotNull();

        BenchmarkTaskSet set = new BenchmarkTaskSetReader().read(resource);

        assertThat(set.tasks()).hasSize(50);
        assertThat(set.tasks()).extracting(BenchmarkTask::id).doesNotHaveDuplicates();
        assertThat(set.tasks()).extracting(task -> task.metadata().get("fixtureVersion"))
                .containsOnly("1");
        assertThat(set.tasks()).extracting(task -> task.metadata().get("scenario"))
                .contains("mcp-tool-call", "skill-active-fingerprint", "actor-workspace-isolation",
                        "approval-audit-trace", "binding-lifecycle-recovery");
        assertThat(set.tasks()).allSatisfy(task -> {
            assertThat(task.id()).isNotBlank();
            assertThat(task.category()).isNotBlank();
            assertThat(task.prompt()).isNotBlank();
            assertThat(task.successCriteria()).isNotBlank();
            assertThat(task.metadata()).allSatisfy((key, value) -> {
                assertThat(key).isIn(
                        "fixtureVersion", "scenario", "actorUserId", "workspaceId",
                        "peerActorUserId", "peerWorkspaceId", "runtime", "expectedTerminalStatus",
                        "expectedMcpRemoteTool", "expectedSkillActive", "expectedAuditToolName",
                        "expectedTraceMarker", "expectPeerVisibility");
                assertThat(value).isNotBlank();
            });
        });
    }

    @Test
    void rejectsUnknownFieldsDuplicateIdsInvalidJsonAndBlankLines() {
        BenchmarkTaskSetReader reader = new BenchmarkTaskSetReader();
        String valid = "{\"id\":\"one\",\"category\":\"CODE\","
                + "\"prompt\":\"p\",\"successCriteria\":\"s\",\"metadata\":{}}\n";
        assertThatThrownBy(() -> reader.read(stream(valid + "\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空行");
        assertThatThrownBy(() -> reader.read(stream(valid + valid)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("唯一");
        String unknownField = "{\"id\":\"one\",\"category\":\"CODE\","
                + "\"prompt\":\"p\",\"successCriteria\":\"s\","
                + "\"metadata\":{},\"extra\":1}\n";
        assertThatThrownBy(() -> reader.read(stream(unknownField)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知");
        assertThatThrownBy(() -> reader.read(stream("not-json\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void preservesStableMetadataAndRejectsNullResource() {
        BenchmarkTaskSetReader reader = new BenchmarkTaskSetReader();
        String line = "{\"id\":\"one\",\"category\":\"CODE\","
                + "\"prompt\":\"p\",\"successCriteria\":\"s\","
                + "\"metadata\":{\"phase\":\"1\"}}\n";
        assertThatThrownBy(() -> reader.read(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> reader.read(stream(line)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50");
    }

    @Test
    void rejectsDuplicateFieldsAndTrailingRootValues() {
        BenchmarkTaskSetReader reader = new BenchmarkTaskSetReader();
        String duplicateField = "{\"id\":\"one\",\"id\":\"two\","
                + "\"category\":\"CODE\",\"prompt\":\"p\","
                + "\"successCriteria\":\"s\",\"metadata\":{}}\n";
        String trailingRoot = "{\"id\":\"one\",\"category\":\"CODE\","
                + "\"prompt\":\"p\",\"successCriteria\":\"s\","
                + "\"metadata\":{}} {\"unexpected\":true}\n";

        assertThatThrownBy(() -> reader.read(stream(duplicateField)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
        assertThatThrownBy(() -> reader.read(stream(trailingRoot)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
    }

    private InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
