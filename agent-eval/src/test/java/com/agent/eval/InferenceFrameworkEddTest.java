package com.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 用不访问模型的确定性断言验证第八篇第 26 章推理框架契约。 */
class InferenceFrameworkEddTest {

    private final List<AssertionResult> results = new ArrayList<>();

    @Test
    void verifiesInferenceFrameworkContractAndWritesReport() throws Exception {
        check("contract.protocol", "agent-core/src/main/java/com/agent/core/llm/InferenceProtocol.java",
                "OPENAI_CHAT_COMPLETIONS");
        check("contract.capabilities", "agent-core/src/main/java/com/agent/core/llm/InferenceCapability.java",
                "CHAT_COMPLETIONS", "STREAMING", "TOOL_CALLING", "VISION_INPUT");
        check("endpoint.contract", "agent-core/src/main/java/com/agent/core/llm/ModelEndpoint.java",
                "InferenceServiceContract", "InferenceAdmissionController");
        check("admission.budget", "agent-core/src/main/java/com/agent/core/llm/InferenceBudget.java",
                "maxConcurrentRequests", "maxRequestsPerMinute", "queueTimeout");
        check("admission.rate-window", "agent-core/src/main/java/com/agent/core/llm/InferenceAdmissionController.java",
                "RATE_WINDOW", "Semaphore", "RATE_LIMIT");
        check("router.preflight", "agent-core/src/main/java/com/agent/core/llm/ModelRouter.java",
                "requireCapabilities", "admissionController().acquire", "serviceContracts");
        check("stream.metrics", "agent-core/src/main/java/com/agent/core/llm/StreamingMetrics.java",
                "timeToFirstChunk", "chunkCount", "consumerBackpressureDuration");
        check("stream.client", "agent-core/src/main/java/com/agent/core/llm/LlmClient.java",
                "logStreamSuccess", "maxConsumerBackpressureDuration", "nanoTime");
        check("web.env", ".env.example",
                "AGENT_LLM_MAX_CONCURRENT_REQUESTS", "AGENT_LLM_CODE_CAPABILITIES",
                "AGENT_LLM_QUEUE_TIMEOUT");
        check("web.properties", "agent-web/src/main/resources/application.properties",
                "agent.llm.max-concurrent-requests", "agent.llm.fallback-capabilities");
        check("pitfalls.chapter26", "docs/ENGINEERING_PITFALLS.md",
                "第 26 章 Inference Framework", "无界排队", "TTFT");

        String corePom = Files.readString(root("agent-core/pom.xml"));
        assertThat(corePom)
                .doesNotContain("langchain4j")
                .doesNotContain("langgraph4j")
                .doesNotContain("vllm")
                .doesNotContain("ollama");

        assertThat(results).hasSize(11);
        assertThat(results).allSatisfy(result -> assertThat(result.passed())
                .as(result.id() + " 未通过").isTrue());
        Path report = Path.of("target", "edd", "inference-framework-chapter-26.json");
        Files.createDirectories(report.getParent());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("suiteId", "inference-framework-chapter-26");
        output.put("mode", "deterministic");
        output.put("modelCallAttempts", 0);
        output.put("taskCount", results.size());
        output.put("passedTaskCount", results.stream().filter(AssertionResult::passed).count());
        output.put("results", results);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(), output);
        assertThat(Files.readString(report)).doesNotContain("AGENT_LLM_API_KEY", "secret");
    }

    private void check(String id, String relativePath, String... fragments) throws Exception {
        Path file = root(relativePath);
        boolean passed = Files.isRegularFile(file);
        String text = passed ? Files.readString(file) : "";
        for (String fragment : fragments) {
            passed = passed && text.contains(fragment);
        }
        results.add(new AssertionResult(id, passed));
    }

    private Path root(String relativePath) {
        return Path.of("..", relativePath).normalize();
    }

    private record AssertionResult(String id, boolean passed) {
    }
}
