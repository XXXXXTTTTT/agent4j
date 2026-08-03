package com.agent.rag.ingest;

import com.agent.rag.domain.ParentChunk;
import com.agent.sandbox.ast.AstService;
import com.agent.sandbox.ast.ClassInfo;
import com.agent.sandbox.ast.MethodInfo;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/** 将代码库转换为父子检索块。 */
public final class CodebaseChunker {

    private static final String JAVA_METADATA = "{\"kind\":\"JAVA_CLASS\"}";
    private static final String TEXT_METADATA = "{\"kind\":\"TEXT_FILE\"}";
    private static final int WINDOW_LINES = 120;
    private static final int OVERLAP_LINES = 20;

    private final AstService astService;
    private final JavaParser parser;

    /** 使用指定 AST 服务创建切片器。 */
    public CodebaseChunker(AstService astService) {
        this.astService = Objects.requireNonNull(astService, "astService 不能为空");
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setCharacterEncoding(StandardCharsets.UTF_8);
        this.parser = new JavaParser(configuration);
    }

    /** 切分仓库根目录下的全部可读文本文件。 */
    public ChunkBatch chunk(Path repositoryRoot, String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId 不能为空");
        }
        Objects.requireNonNull(repositoryRoot, "repositoryRoot 不能为空");
        Path root = realRoot(repositoryRoot);
        List<ParentChunk> parents = new ArrayList<>();
        List<ChildDraft> children = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> Files.isRegularFile(path))
                    .filter(path -> !excluded(root, path))
                    .sorted(Comparator.comparing(path -> relativePath(root, path)))
                    .forEach(path -> readAndChunk(
                            root, path, repositoryId, parents, children));
        } catch (CodebaseIngestionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new CodebaseIngestionException("读取仓库文件失败: " + root, exception);
        }
        return new ChunkBatch(parents, children);
    }

    private Path realRoot(Path repositoryRoot) {
        try {
            Path root = repositoryRoot.toRealPath();
            if (!Files.isDirectory(root)) {
                throw new IOException("不是目录");
            }
            return root;
        } catch (IOException exception) {
            throw new CodebaseIngestionException("仓库根目录无效: " + repositoryRoot, exception);
        }
    }

    private void readAndChunk(
            Path root,
            Path path,
            String repositoryId,
            List<ParentChunk> parents,
            List<ChildDraft> children) {
        String source = readUtf8OrSkip(path);
        if (source == null) {
            return;
        }
        String relativePath = relativePath(root, path);
        if (relativePath.endsWith(".java")) {
            chunkJava(path, relativePath, repositoryId, source, parents, children);
        } else {
            chunkText(relativePath, repositoryId, source, parents, children);
        }
    }

    private String readUtf8OrSkip(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            for (byte value : bytes) {
                if (value == 0) {
                    return null;
                }
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException exception) {
                return null;
            }
        } catch (IOException exception) {
            throw new CodebaseIngestionException("读取文件失败: " + path, exception);
        }
    }

    private void chunkJava(
            Path sourceFile,
            String relativePath,
            String repositoryId,
            String source,
            List<ParentChunk> parents,
            List<ChildDraft> children) {
        ParseResult<CompilationUnit> parsed = parser.parse(source);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            throw new CodebaseIngestionException(
                    "解析 Java 文件失败: " + sourceFile,
                    new IllegalArgumentException(parsed.getProblems().toString()));
        }
        List<ClassOrInterfaceDeclaration> declarations = parsed.getResult().orElseThrow()
                .findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(type -> type.getFullyQualifiedName().isPresent())
                .sorted(Comparator.comparingInt(type -> rangeStart(type).begin.line))
                .toList();
        for (ClassOrInterfaceDeclaration declaration : declarations) {
            String qualifiedName = declaration.getFullyQualifiedName().orElseThrow();
            ClassInfo classInfo = astService.extractClass(sourceFile, qualifiedName);
            UUID parentId = stableId("parent", repositoryId, relativePath, qualifiedName);
            ParentChunk parent = new ParentChunk(
                    parentId,
                    repositoryId,
                    relativePath,
                    qualifiedName,
                    classInfo.source(),
                    classInfo.beginLine(),
                    classInfo.endLine(),
                    JAVA_METADATA);
            parents.add(parent);
            List<MethodInfo> methods = astService.extractMethods(sourceFile, qualifiedName);
            if (methods.isEmpty()) {
                children.add(new ChildDraft(
                        stableId("child", repositoryId, relativePath, qualifiedName, "0"),
                        parentId,
                        repositoryId,
                        relativePath,
                        qualifiedName,
                        0,
                        classInfo.source(),
                        classInfo.beginLine(),
                        classInfo.endLine()));
            } else {
                for (int ordinal = 0; ordinal < methods.size(); ordinal++) {
                    MethodInfo method = methods.get(ordinal);
                    children.add(new ChildDraft(
                            stableId("child", repositoryId, relativePath,
                                    qualifiedName, Integer.toString(ordinal)),
                            parentId,
                            repositoryId,
                            relativePath,
                            qualifiedName + "#" + method.declaration(),
                            ordinal,
                            method.source(),
                            method.beginLine(),
                            method.endLine()));
                }
            }
        }
    }

    private void chunkText(
            String relativePath,
            String repositoryId,
            String source,
            List<ParentChunk> parents,
            List<ChildDraft> children) {
        String[] lines = source.split("\\R", -1);
        UUID parentId = stableId("parent", repositoryId, relativePath, "");
        ParentChunk parent = new ParentChunk(
                parentId,
                repositoryId,
                relativePath,
                null,
                source,
                1,
                Math.max(1, lines.length),
                TEXT_METADATA);
        parents.add(parent);
        int ordinal = 0;
        for (int start = 0; start < lines.length; ) {
            int end = Math.min(lines.length, start + WINDOW_LINES);
            children.add(new ChildDraft(
                    stableId("child", repositoryId, relativePath, Integer.toString(ordinal)),
                    parentId,
                    repositoryId,
                    relativePath,
                    null,
                    ordinal++,
                    String.join("\n", List.of(lines).subList(start, end)),
                    start + 1,
                    end));
            if (end == lines.length) {
                break;
            }
            start = end - OVERLAP_LINES;
        }
    }

    private boolean excluded(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals(".git") || name.equals("target") || name.equals("node_modules")) {
                return true;
            }
        }
        return false;
    }

    private String relativePath(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private Range rangeStart(ClassOrInterfaceDeclaration declaration) {
        return declaration.getRange().orElseThrow(() ->
                new CodebaseIngestionException("Java 类缺少源码范围"));
    }

    private UUID stableId(String kind, String... parts) {
        String value = kind + "\u0000" + String.join("\u0000", parts);
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
