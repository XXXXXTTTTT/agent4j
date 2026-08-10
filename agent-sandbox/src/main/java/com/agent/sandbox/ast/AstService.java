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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 使用 JavaParser 提取 Java 21 源码结构。 */
public final class AstService {

    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$");

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
        return applyDiffWithEvidence(repositoryRoot, unifiedDiff).updatedFiles();
    }

    /**
     * 在应用补丁的同时返回实际执行的规范化 Unified Diff，供审计和前端渲染使用。
     *
     * @param repositoryRoot Git 工作树根目录
     * @param unifiedDiff    模型返回的 Unified Diff 或 Apply Patch 文本
     * @return 实际更新文件和规范化后的 Unified Diff
     */
    public AppliedDiff applyDiffWithEvidence(Path repositoryRoot, String unifiedDiff) {
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

                String normalizedDiff = normalizePatchFormat(unifiedDiff, root);
                byte[] patchBytes = normalizedDiff.getBytes(StandardCharsets.UTF_8);
                validatePatch(root, patchBytes);
                Path indexPath = git.getRepository().getIndexFile().toPath();
                byte[] originalIndex = Files.exists(indexPath)
                        ? Files.readAllBytes(indexPath)
                        : null;
                ApplyResult result;
                try {
                    result = git.apply()
                            .setPatch(new ByteArrayInputStream(patchBytes))
                            .call();
                } finally {
                    restoreIndex(indexPath, originalIndex);
                }
                List<Path> updatedFiles = new ArrayList<>();
                for (java.io.File updatedFile : result.getUpdatedFiles()) {
                    Path updatedPath = updatedFile.getCanonicalFile().toPath();
                    ensureInsideWorkTree(root, updatedPath, updatedFile.getPath());
                    updatedFiles.add(updatedPath);
                }
                return new AppliedDiff(updatedFiles, normalizedDiff);
            }
        } catch (AstServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AstServiceException("应用 Unified Diff 失败: " + repositoryRoot, exception);
        }
    }

    /** 应用成功后的不可变 Diff 证据。 */
    public record AppliedDiff(List<Path> updatedFiles, String unifiedDiff) {

        public AppliedDiff {
            updatedFiles = List.copyOf(Objects.requireNonNull(updatedFiles, "updatedFiles 不能为空"));
            if (unifiedDiff == null || unifiedDiff.isBlank()) {
                throw new IllegalArgumentException("unifiedDiff 不能为空");
            }
        }
    }

    private void restoreIndex(Path indexPath, byte[] originalIndex) {
        try {
            if (originalIndex == null) {
                Files.deleteIfExists(indexPath);
            } else {
                Files.write(indexPath, originalIndex);
            }
        } catch (IOException exception) {
            throw new AstServiceException("恢复 Git index 失败: " + indexPath, exception);
        }
    }

    private String normalizePatchFormat(String patch, Path root) {
        if (!patch.stripLeading().startsWith("*** Begin Patch")) {
            return normalizeUnifiedDiffHunkCounts(patch);
        }
        String[] lines = patch.replace("\r\n", "\n").split("\n", -1);
        StringBuilder result = new StringBuilder();
        int index = 0;
        while (index < lines.length) {
            String line = lines[index];
            validateApplyPatchDirective(line);
            if (!line.startsWith("*** Update File: ")) {
                index++;
                continue;
            }
            String rawPath = line.substring("*** Update File: ".length()).trim();
            Path file = resolvePatchFile(root, rawPath);
            try {
                String original = Files.readString(file, StandardCharsets.UTF_8);
                int contentStart = index + 1;
                int contentEnd = contentStart;
                while (contentEnd < lines.length
                        && !lines[contentEnd].startsWith("*** ")) {
                    contentEnd++;
                }
                String updated = applyPatchHunks(original,
                        java.util.Arrays.copyOfRange(lines, contentStart, contentEnd));
                appendFullFileDiff(result, rawPath, original, updated);
                index = contentEnd;
            } catch (IOException exception) {
                throw new AstServiceException("读取补丁目标文件失败: " + rawPath, exception);
            }
        }
        if (result.isEmpty()) {
            throw new AstServiceException("Apply Patch 未包含可应用的 Update File");
        }
        return result.toString();
    }

    private void validateApplyPatchDirective(String line) {
        if (!line.startsWith("*** ")) {
            return;
        }
        if (line.equals("*** Begin Patch")
                || line.equals("*** End Patch")
                || line.startsWith("*** Update File: ")) {
            return;
        }
        throw new AstServiceException("Apply Patch 指令不支持: " + line);
    }

    private String normalizeUnifiedDiffHunkCounts(String patch) {
        String[] lines = patch.replace("\r\n", "\n").split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            Matcher header = HUNK_HEADER.matcher(lines[index]);
            if (!header.matches()) {
                continue;
            }
            int oldLines = 0;
            int newLines = 0;
            boolean validBody = true;
            for (int bodyIndex = index + 1; bodyIndex < lines.length; bodyIndex++) {
                String bodyLine = lines[bodyIndex];
                if (HUNK_HEADER.matcher(bodyLine).matches()
                        || bodyLine.startsWith("diff --git ")) {
                    break;
                }
                if (bodyIndex == lines.length - 1 && bodyLine.isEmpty()) {
                    break;
                }
                if (bodyLine.equals("\\ No newline at end of file")) {
                    continue;
                }
                if (bodyLine.startsWith(" ")) {
                    oldLines++;
                    newLines++;
                } else if (bodyLine.startsWith("-")) {
                    oldLines++;
                } else if (bodyLine.startsWith("+")) {
                    newLines++;
                } else {
                    validBody = false;
                    break;
                }
            }
            if (!validBody) {
                continue;
            }
            int declaredOldLines = header.group(2) == null
                    ? 1 : Integer.parseInt(header.group(2));
            int declaredNewLines = header.group(4) == null
                    ? 1 : Integer.parseInt(header.group(4));
            if (declaredOldLines != oldLines || declaredNewLines != newLines) {
                lines[index] = "@@ -" + header.group(1) + "," + oldLines
                        + " +" + header.group(3) + "," + newLines
                        + " @@" + header.group(5);
            }
        }
        return String.join("\n", lines);
    }

    private Path resolvePatchFile(Path root, String rawPath) {
        try {
            Path relative = Path.of(rawPath);
            if (relative.isAbsolute()) {
                throw new AstServiceException("补丁路径越过 Git 工作树: " + rawPath);
            }
            Path resolved = root.resolve(relative).normalize();
            ensureInsideWorkTree(root, resolved, rawPath);
            if (!Files.isRegularFile(resolved)) {
                throw new AstServiceException("补丁目标文件不存在: " + rawPath);
            }
            return resolved;
        } catch (InvalidPathException exception) {
            throw new AstServiceException("补丁路径无效: " + rawPath, exception);
        }
    }

    private String applyPatchHunks(String original, String[] lines) {
        String current = original;
        List<String> oldLines = new ArrayList<>();
        List<String> newLines = new ArrayList<>();
        boolean inHunk = false;
        for (String line : lines) {
            if (line.startsWith("@@")) {
                if (inHunk) {
                    current = replaceHunk(current, oldLines, newLines);
                    oldLines.clear();
                    newLines.clear();
                }
                inHunk = true;
                continue;
            }
            if (!inHunk || line.equals("\\ No newline at end of file")) {
                continue;
            }
            if (line.startsWith(" ")) {
                String content = line.substring(1);
                oldLines.add(content);
                newLines.add(content);
            } else if (line.startsWith("-")) {
                oldLines.add(line.substring(1));
            } else if (line.startsWith("+")) {
                newLines.add(line.substring(1));
            } else {
                throw new AstServiceException("Apply Patch 行格式无效: " + line);
            }
        }
        if (inHunk) {
            current = replaceHunk(current, oldLines, newLines);
        }
        return current;
    }

    private String replaceHunk(
            String source,
            List<String> oldLines,
            List<String> newLines) {
        if (oldLines.isEmpty()) {
            throw new AstServiceException("Apply Patch 不支持无上下文的新文件补丁");
        }
        boolean crlf = source.contains("\r\n");
        String normalizedSource = source.replace("\r\n", "\n");
        String oldText = String.join("\n", oldLines);
        String newText = String.join("\n", newLines);
        int offset = normalizedSource.indexOf(oldText);
        if (offset < 0) {
            throw new AstServiceException("Apply Patch 上下文与工作区文件不匹配");
        }
        if (normalizedSource.indexOf(oldText, offset + oldText.length()) >= 0) {
            throw new AstServiceException("Apply Patch 上下文不唯一");
        }
        String replaced = normalizedSource.substring(0, offset)
                + newText
                + normalizedSource.substring(offset + oldText.length());
        return crlf ? replaced.replace("\n", "\r\n") : replaced;
    }

    private void appendFullFileDiff(
            StringBuilder result,
            String rawPath,
            String original,
            String updated) {
        if (original.equals(updated)) {
            throw new AstServiceException("Apply Patch 未产生文件变更: " + rawPath);
        }
        List<String> oldLines = diffLines(original);
        List<String> newLines = diffLines(updated);
        result.append("diff --git a/").append(rawPath).append(" b/").append(rawPath).append('\n')
                .append("--- a/").append(rawPath).append('\n')
                .append("+++ b/").append(rawPath).append('\n')
                .append("@@ -1,").append(oldLines.size())
                .append(" +1,").append(newLines.size()).append(" @@\n");
        oldLines.forEach(line -> result.append('-').append(line).append('\n'));
        newLines.forEach(line -> result.append('+').append(line).append('\n'));
    }

    private List<String> diffLines(String content) {
        if (content.isEmpty()) {
            return List.of();
        }
        String withoutTrailingLineFeed = content.endsWith("\n")
                ? content.substring(0, content.length() - 1)
                : content;
        return List.of(withoutTrailingLineFeed.split("\n", -1));
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
