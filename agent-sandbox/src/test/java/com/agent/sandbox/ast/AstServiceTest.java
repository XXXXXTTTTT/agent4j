package com.agent.sandbox.ast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AstServiceTest {

    private static final String SOURCE = """
            package example;

            public class Sample {
                public int value() {
                    return 1;
                }

                public int value(int input) {
                    return input;
                }

                public boolean diagonal(Object point) {
                    return point instanceof Point(int x, int y) && x == y;
                }

                static class Nested {
                    void hidden() {
                    }
                }
            }

            record Point(int x, int y) {
            }
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsClassAndDirectMethodsByQualifiedName() throws IOException {
        Path sourceFile = writeSource("Sample.java", SOURCE);
        AstService service = new AstService();

        ClassInfo classInfo = service.extractClass(sourceFile, "example.Sample");
        List<MethodInfo> methods = service.extractMethods(sourceFile, "example.Sample");

        assertThat(classInfo.qualifiedName()).isEqualTo("example.Sample");
        assertThat(classInfo.beginLine()).isEqualTo(3);
        assertThat(classInfo.endLine()).isEqualTo(20);
        assertThat(classInfo.source())
                .startsWith("public class Sample {")
                .endsWith("    }\n}")
                .contains("point instanceof Point(int x, int y)");

        assertThat(methods).extracting(MethodInfo::name)
                .containsExactly("value", "value", "diagonal");
        assertThat(methods).extracting(MethodInfo::declaration)
                .containsExactly(
                        "public int value()",
                        "public int value(int input)",
                        "public boolean diagonal(Object point)");
        assertThat(methods).extracting(MethodInfo::beginLine)
                .containsExactly(4, 8, 12);
        assertThat(methods).extracting(MethodInfo::endLine)
                .containsExactly(6, 10, 14);
        assertThat(methods.getFirst().source()).isEqualTo("""
                public int value() {
                        return 1;
                    }""");
        assertThat(methods).noneMatch(method -> method.name().equals("hidden"));
        assertThatThrownBy(() -> methods.add(methods.getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiresExactQualifiedClassName() throws IOException {
        Path sourceFile = writeSource("Sample.java", SOURCE);
        AstService service = new AstService();

        assertThatThrownBy(() -> service.extractClass(sourceFile, "Sample"))
                .isInstanceOf(AstServiceException.class)
                .hasMessageContaining("Sample");
        assertThatThrownBy(() -> service.extractClass(sourceFile, "example.sample"))
                .isInstanceOf(AstServiceException.class)
                .hasMessageContaining("example.sample");
        assertThatThrownBy(() -> service.extractClass(sourceFile, "example.Missing"))
                .isInstanceOf(AstServiceException.class)
                .hasMessageContaining("example.Missing");
    }

    @Test
    void preservesParsingFailureCause() throws IOException {
        Path sourceFile = writeSource("Broken.java", "package example; public class Broken {");

        assertThatThrownBy(() -> new AstService().extractClass(sourceFile, "example.Broken"))
                .isInstanceOf(AstServiceException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Broken.java");
    }

    @Test
    void preservesMissingFileCause() {
        Path sourceFile = temporaryDirectory.resolve("Missing.java");

        assertThatThrownBy(() -> new AstService().extractMethods(sourceFile, "example.Missing"))
                .isInstanceOf(AstServiceException.class)
                .hasCauseInstanceOf(IOException.class)
                .hasMessageContaining("Missing.java");
    }

    @Test
    void validatesResultRecords() {
        assertThatThrownBy(() -> new ClassInfo(" ", 1, 1, "source"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassInfo("example.Sample", 0, 1, "source"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MethodInfo("value", "value()", 2, 1, "source"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Path writeSource(String fileName, String source) throws IOException {
        Path sourceFile = temporaryDirectory.resolve(fileName);
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        return sourceFile;
    }
}
