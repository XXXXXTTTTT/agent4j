package com.agent.core.architecture;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Agent4J 核心边界没有悄然引入第三方 Agent 编排框架。 */
class ArchitectureConstraintTest {

    private static final Set<String> FORBIDDEN_DEPENDENCY_FRAGMENTS = Set.of(
            "langchain4j", "langgraph4j", "spring-ai", "autogen", "crewai", "llamaindex");
    private static final Set<String> FORBIDDEN_IMPORTS = Set.of(
            "dev.langchain4j", "org.bsc.langgraph4j", "org.springframework.ai",
            "io.agentscope", "com.alibaba.cloud.ai");
    private static final List<String> CORE_PORTS = List.of(
            "agent-core/src/main/java/com/agent/core/engine/AgentState.java",
            "agent-core/src/main/java/com/agent/core/engine/Node.java",
            "agent-core/src/main/java/com/agent/core/engine/Condition.java",
            "agent-core/src/main/java/com/agent/core/engine/StateGraph.java",
            "agent-core/src/main/java/com/agent/core/engine/Checkpointer.java",
            "agent-core/src/main/java/com/agent/core/tool/ToolRegistry.java",
            "agent-core/src/main/java/com/agent/core/llm/ModelRouter.java",
            "agent-core/src/main/java/com/agent/core/engine/AgentRunService.java");

    @Test
    void rejectsForbiddenAgentFrameworkDependenciesInBuildDescriptors() throws Exception {
        List<String> coordinates = Stream.of(
                        repositoryRoot().resolve("pom.xml"),
                        repositoryRoot().resolve("agent-core/pom.xml"))
                .flatMap(this::dependencyCoordinates)
                .toList();

        assertThat(coordinates)
                .noneMatch(coordinate -> FORBIDDEN_DEPENDENCY_FRAGMENTS.stream()
                        .anyMatch(coordinate::contains));
    }

    @Test
    void rejectsForbiddenAgentFrameworkImportsInCoreProductionSources() throws IOException {
        Path sourceRoot = repositoryRoot().resolve("agent-core/src/main/java");
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            List<String> forbiddenImports = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(this::importLines)
                    .filter(line -> FORBIDDEN_IMPORTS.stream()
                            .anyMatch(line::startsWith))
                    .toList();
            assertThat(forbiddenImports).isEmpty();
        }
    }

    @Test
    void keepsSelfOwnedCorePortsPresent() {
        assertThat(CORE_PORTS)
                .allSatisfy(relativePath -> assertThat(
                        Files.isRegularFile(repositoryRoot().resolve(relativePath)))
                        .as(relativePath)
                        .isTrue());
    }

    @Test
    void documentsFrameworkConceptMappingForSelfOwnedPorts() throws IOException {
        Path mapping = repositoryRoot().resolve("docs/ARCHITECTURE_MAPPING.md");
        assertThat(Files.isRegularFile(mapping)).isTrue();
        String content = Files.readString(mapping);
        assertThat(content)
                .contains("Agent4J 自研")
                .contains("AgentState")
                .contains("Node")
                .contains("Condition")
                .contains("StateGraph")
                .contains("Checkpointer")
                .contains("ToolRegistry")
                .contains("ModelRouter")
                .contains("AgentRunService")
                .contains("MemoryManager")
                .contains("RagRetrievalPipeline")
                .contains("HarnessHookChain");
    }

    private Stream<String> dependencyCoordinates(Path pom) {
        try {
            Document document = secureDocumentBuilderFactory()
                    .newDocumentBuilder()
                    .parse(pom.toFile());
            NodeList dependencies = document.getElementsByTagNameNS(
                    "http://maven.apache.org/POM/4.0.0", "dependency");
            return java.util.stream.IntStream.range(0, dependencies.getLength())
                    .mapToObj(index -> dependencies.item(index))
                    .map(dependency -> text(dependency, "groupId") + ":"
                            + text(dependency, "artifactId"))
                    .map(String::toLowerCase)
                    .toList()
                    .stream();
        } catch (Exception exception) {
            throw new AssertionError("无法解析 POM: " + pom, exception);
        }
    }

    private Stream<String> importLines(Path source) {
        try {
            return Files.readAllLines(source).stream()
                    .map(String::strip)
                    .filter(line -> line.startsWith("import "))
                    .map(line -> line.substring("import ".length(), line.length() - 1));
        } catch (IOException exception) {
            throw new AssertionError("无法读取核心源码: " + source, exception);
        }
    }

    private String text(org.w3c.dom.Node dependency, String name) {
        NodeList children = ((org.w3c.dom.Element) dependency)
                .getElementsByTagNameNS("http://maven.apache.org/POM/4.0.0", name);
        if (children.getLength() != 1) {
            throw new AssertionError("依赖缺少唯一 " + name + ": " + dependency.getTextContent());
        }
        return children.item(0).getTextContent().strip();
    }

    private DocumentBuilderFactory secureDocumentBuilderFactory() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory;
        } catch (Exception exception) {
            throw new AssertionError("无法配置安全 XML 解析器", exception);
        }
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve("AGENTS.md"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("无法定位仓库根目录");
    }
}
