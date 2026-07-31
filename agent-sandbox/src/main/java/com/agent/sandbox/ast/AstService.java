package com.agent.sandbox.ast;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.eclipse.jgit.api.ApplyResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.Patch;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 使用 JavaParser 提取 Java 21 源码结构。 */
public final class AstService {

    private final JavaParser parser;

    /** 创建 Java 21 AST 服务。 */
    public AstService() {
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setCharacterEncoding(StandardCharsets.UTF_8);
        this.parser = new JavaParser(configuration);
    }

    /**
     * 提取完整限定名精确匹配的类。
     *
     * @param sourceFile        Java 源文件
     * @param qualifiedClassName 完整限定类名
     * @return 类源码信息
     */
    public ClassInfo extractClass(Path sourceFile, String qualifiedClassName) {
        ParsedSource parsed = parse(sourceFile);
        ClassOrInterfaceDeclaration declaration = locateClass(
                parsed.compilationUnit(), qualifiedClassName);
        Range range = requireRange(declaration, qualifiedClassName);
        return new ClassInfo(
                qualifiedClassName,
                range.begin.line,
                range.end.line,
                slice(parsed.source(), range));
    }

    /**
     * 提取目标类直接声明的方法。
     *
     * @param sourceFile        Java 源文件
     * @param qualifiedClassName 完整限定类名
     * @return 按源码顺序排列的方法信息
     */
    public List<MethodInfo> extractMethods(Path sourceFile, String qualifiedClassName) {
        ParsedSource parsed = parse(sourceFile);
        ClassOrInterfaceDeclaration declaration = locateClass(
                parsed.compilationUnit(), qualifiedClassName);

        List<MethodInfo> methods = declaration.getMembers().stream()
                .filter(MethodDeclaration.class::isInstance)
                .map(MethodDeclaration.class::cast)
                .map(method -> toMethodInfo(method, parsed.source()))
                .toList();
        return List.copyOf(methods);
    }

    /**
     * 在 Git 工作树内应用 UTF-8 Unified Diff。
     *
     * @param repositoryRoot Git 工作树根目录
     * @param unifiedDiff    Unified Diff 文本
     * @return 实际更新文件的绝对规范路径
     */
    public List<Path> applyDiff(Path repositoryRoot, String unifiedDiff) {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot 不能为空");
        if (unifiedDiff == null || unifiedDiff.isBlank()) {
            throw new AstServiceException("补丁内容不能为空");
        }

        try {
            Path root = repositoryRoot.toRealPath();
            if (!Files.isDirectory(root)) {
                throw new AstServiceException("Git 工作树根目录不是目录: " + root);
            }

            try (Git git = Git.open(root.toFile())) {
                Path workTree = git.getRepository().getWorkTree().toPath().toRealPath();
                if (!workTree.equals(root)) {
                    throw new AstServiceException("路径不是 Git 工作树根目录: " + root);
                }

                byte[] patchBytes = unifiedDiff.getBytes(StandardCharsets.UTF_8);
                validatePatch(root, patchBytes);
                ApplyResult result = git.apply()
                        .setPatch(new ByteArrayInputStream(patchBytes))
                        .call();
                List<Path> updatedFiles = new ArrayList<>();
                for (java.io.File updatedFile : result.getUpdatedFiles()) {
                    Path updatedPath = updatedFile.getCanonicalFile().toPath();
                    ensureInsideWorkTree(root, updatedPath, updatedFile.getPath());
                    updatedFiles.add(updatedPath);
                }
                return List.copyOf(updatedFiles);
            }
        } catch (AstServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AstServiceException("应用 Unified Diff 失败: " + repositoryRoot, exception);
        }
    }

    private void validatePatch(Path root, byte[] patchBytes) throws IOException {
        Patch patch = new Patch();
        patch.parse(new ByteArrayInputStream(patchBytes));
        if (!patch.getErrors().isEmpty()) {
            throw new AstServiceException(
                    "Unified Diff 解析失败",
                    new IllegalArgumentException(patch.getErrors().toString()));
        }
        if (patch.getFiles().isEmpty()) {
            throw new AstServiceException("Unified Diff 没有文件变更");
        }

        for (FileHeader fileHeader : patch.getFiles()) {
            validatePatchPath(root, fileHeader.getOldPath());
            validatePatchPath(root, fileHeader.getNewPath());
        }
    }

    private void validatePatchPath(Path root, String rawPath) {
        if (DiffEntry.DEV_NULL.equals(rawPath)) {
            return;
        }
        try {
            Path patchPath = Path.of(rawPath);
            if (patchPath.isAbsolute()) {
                throw new AstServiceException("补丁路径越过 Git 工作树: " + rawPath);
            }
            Path resolved = root.resolve(patchPath).normalize();
            ensureInsideWorkTree(root, resolved, rawPath);
        } catch (InvalidPathException exception) {
            throw new AstServiceException("补丁路径无效: " + rawPath, exception);
        }
    }

    private void ensureInsideWorkTree(Path root, Path path, String rawPath) {
        if (!path.toAbsolutePath().normalize().startsWith(root)) {
            throw new AstServiceException("补丁路径越过 Git 工作树: " + rawPath);
        }
    }

    private ParsedSource parse(Path sourceFile) {
        Objects.requireNonNull(sourceFile, "sourceFile 不能为空");
        try {
            String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
            ParseResult<CompilationUnit> result = parser.parse(source);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                IllegalArgumentException cause = new IllegalArgumentException(
                        "JavaParser 解析问题: " + result.getProblems());
                throw new AstServiceException("解析 Java 源文件失败: " + sourceFile, cause);
            }
            return new ParsedSource(source, result.getResult().orElseThrow());
        } catch (AstServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AstServiceException("读取 Java 源文件失败: " + sourceFile, exception);
        }
    }

    private ClassOrInterfaceDeclaration locateClass(
            CompilationUnit compilationUnit,
            String qualifiedClassName) {
        if (qualifiedClassName == null || qualifiedClassName.isBlank()) {
            throw new AstServiceException("qualifiedClassName 不能为空");
        }

        List<ClassOrInterfaceDeclaration> matches = compilationUnit
                .findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(type -> type.getFullyQualifiedName()
                        .map(qualifiedClassName::equals)
                        .orElse(false))
                .toList();
        if (matches.size() != 1) {
            throw new AstServiceException(
                    "无法唯一定位完整限定类名: " + qualifiedClassName);
        }
        return matches.getFirst();
    }

    private MethodInfo toMethodInfo(MethodDeclaration method, String source) {
        Range range = requireRange(method, method.getNameAsString());
        return new MethodInfo(
                method.getNameAsString(),
                method.getDeclarationAsString(true, true, true),
                range.begin.line,
                range.end.line,
                slice(source, range));
    }

    private Range requireRange(
            com.github.javaparser.ast.Node node,
            String description) {
        return node.getRange().orElseThrow(() -> new AstServiceException(
                "源码缺少位置信息: " + description,
                new IllegalStateException("JavaParser 未提供 Range")));
    }

    private String slice(String source, Range range) {
        int beginOffset = offset(source, range.begin.line, range.begin.column);
        int endOffset = offset(source, range.end.line, range.end.column) + 1;
        if (beginOffset < 0 || endOffset > source.length() || beginOffset >= endOffset) {
            throw new AstServiceException(
                    "JavaParser 返回的源码范围无效",
                    new IndexOutOfBoundsException(range.toString()));
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

    private record ParsedSource(String source, CompilationUnit compilationUnit) {
    }
}
