package com.agent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 用不访问模型的确定性断言验证第八篇第 25 章部署契约。 */
class DeploymentEddTest {

    private final List<AssertionResult> results = new ArrayList<>();

    @Test
    void verifiesDeploymentContractAndWritesReport() throws Exception {
        check("compose.production.readiness", "docker-compose.yml",
                "curl -fsS http://localhost:8080/actuator/health/readiness");
        check("compose.local.readiness", "docker-compose.local.yml",
                "curl -fsS http://localhost:8080/actuator/health/readiness");
        check("compose.production.resources", "docker-compose.yml",
                "mem_limit: 2g", "cpus: 2.0", "pids_limit: 512");
        check("compose.local.resources", "docker-compose.local.yml",
                "mem_limit: 2g", "cpus: 2.0", "pids_limit: 512");
        check("docker.production.java21", "Dockerfile", "eclipse-temurin:21-jre");
        check("docker.local.java21", "Dockerfile.local", "eclipse-temurin:21-jre");
        check("docker.production.exec", "Dockerfile", "exec java $JAVA_OPTS -jar app.jar");
        check("docker.local.exec", "Dockerfile.local", "exec java $JAVA_OPTS -jar app.jar");
        check("application.health", "agent-web/src/main/resources/application.properties",
                "management.endpoint.health.probes.enabled=true",
                "management.endpoint.health.group.readiness.include=readinessState,db",
                "server.shutdown=graceful");
        check("recovery.runbook", "docs/deployment/backup-recovery.md",
                "pg_dump", "pg_restore", "flyway_schema_history");

        assertThat(results).hasSize(10);
        assertThat(results).allSatisfy(result -> assertThat(result.passed())
                .as(result.id() + " 未通过").isTrue());
        Path report = Path.of("target", "edd", "deployment-chapter-25.json");
        Files.createDirectories(report.getParent());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("suiteId", "deployment-chapter-25");
        output.put("mode", "deterministic");
        output.put("modelCallAttempts", 0);
        output.put("taskCount", results.size());
        output.put("passedTaskCount", results.stream().filter(AssertionResult::passed).count());
        output.put("results", results);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(), output);
        assertThat(Files.readString(report)).doesNotContain("AGENT_LLM_API_KEY", "password");
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
