package com.agent.rag.ingest;

import com.agent.rag.domain.ParentChunk;
import com.agent.sandbox.ast.AstService;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 将代码库转换为父子检索块。 */
public final class CodebaseChunker {

    private static final String JAVA_METADATA = "{\"kind\":\"JAVA_CLASS\"}";
    private static final String TEXT_METADATA = "{\"kind\":\"TEXT_FILE\"}";
    private static final int WINDOW_LINES = 120;
    private static final int OVERLAP_LINES = 20;

    private final RepositorySourceScanner sourceScanner;
    private final JavaParser parser;

    /** 使用指定 AST 服务创建切片器。 */
    public CodebaseChunker(AstService astService) {
        Objects.requireNonNull(astService, "astService 不能为空");
        this.sourceScanner = new RepositorySourceScanner();
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setCharacterEncoding(StandardCharsets.UTF_8);
        this.parser = new JavaParser(configuration);
    }

    /** 切分仓库根目录下的全部可读文本文件。 */
    public ChunkBatch chunk(Path repositoryRoot, String repositoryId) {
        return chunk(sourceScanner.capture(repositoryRoot), repositoryId);
    }

    /** 仅使用同一次仓库快照中的正文完成切片。 */
    public ChunkBatch chunk(RepositorySnapshot snapshot, String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId 不能为空");
        }
        Objects.requireNonNull(snapshot, "snapshot 不能为空");
        List<ParentChunk> parents = new ArrayList<>();
        List<ChildDraft> children = new ArrayList<>();
        for (RepositorySource source : snapshot.sources()) {
            readAndChunk(snapshot.root(), source, repositoryId, parents, children);
        }
        return new ChunkBatch(parents, children);
    }

    private void readAndChunk(
            Path root,
            RepositorySource repositorySource,
            String repositoryId,
            List<ParentChunk> parents,
            List<ChildDraft> children) {
        String source = repositorySource.content();
        String relativePath = repositorySource.relativePath();
        if (relativePath.endsWith(".java")) {
            chunkJava(root.resolve(relativePath), relativePath,
                    repositoryId, source, parents, children);
        } else {
            chunkText(relativePath, repositoryId, source, parents, children);
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
            Range classRange = requireRange(declaration, qualifiedName);
            String classSource = slice(source, classRange);
            UUID parentId = stableId("parent", repositoryId, relativePath, qualifiedName);
            ParentChunk parent = new ParentChunk(
                    parentId,
                    repositoryId,
                    relativePath,
                    qualifiedName,
                    classSource,
                    classRange.begin.line,
                    classRange.end.line,
                    JAVA_METADATA);
            parents.add(parent);
            List<MethodDeclaration> methods = declaration.getMembers().stream()
                    .filter(MethodDeclaration.class::isInstance)
                    .map(MethodDeclaration.class::cast)
                    .toList();
            if (methods.isEmpty()) {
                children.add(new ChildDraft(
                        stableId("child", repositoryId, relativePath, qualifiedName, "0"),
                        parentId,
                        repositoryId,
                        relativePath,
                        qualifiedName,
                        0,
                        classSource,
                        classRange.begin.line,
                        classRange.end.line));
            } else {
                for (int ordinal = 0; ordinal < methods.size(); ordinal++) {
                    MethodDeclaration method = methods.get(ordinal);
                    Range methodRange = requireRange(method, method.getNameAsString());
                    children.add(new ChildDraft(
                            stableId("child", repositoryId, relativePath,
                                    qualifiedName, Integer.toString(ordinal)),
                            parentId,
                            repositoryId,
                            relativePath,
                            qualifiedName + "#"
                                    + method.getDeclarationAsString(true, true, true),
                            ordinal,
                            slice(source, methodRange),
                            methodRange.begin.line,
                            methodRange.end.line));
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

    private Range rangeStart(ClassOrInterfaceDeclaration declaration) {
        return requireRange(declaration, "Java 类");
    }

    private Range requireRange(Node node, String description) {
        return node.getRange().orElseThrow(() -> new CodebaseIngestionException(
                "源码缺少范围: " + description));
    }

    private String slice(String source, Range range) {
        int beginOffset = offset(source, range.begin.line, range.begin.column);
        int endOffset = offset(source, range.end.line, range.end.column) + 1;
        if (beginOffset < 0 || endOffset > source.length() || beginOffset >= endOffset) {
            throw new CodebaseIngestionException(
                    "JavaParser 返回的源码范围无效: " + range);
        }
        return source.substring(beginOffset, endOffset);
    }

    private int offset(String source, int line, int column) {
        int currentLine = 1;
        int lineStart = 0;
        while (currentLine < line) {
            int lineFeed = source.indexOf('\n', lineStart);
            if (lineFeed < 0) {
                return source.length();
            }
            lineStart = lineFeed + 1;
            currentLine++;
        }
        return lineStart + column - 1;
    }

    private UUID stableId(String kind, String... parts) {
        String value = kind + "\u0000" + String.join("\u0000", parts);
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
