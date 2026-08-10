package com.agent.rag.knowledge;

import com.agent.core.context.TokenEstimator;
import com.agent.core.llm.ChatMessage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/** 在工作区边界内编译精确命名的项目知识文件。 */
public final class ProjectKnowledgeCompiler {

    private static final int MAX_FILE_BYTES = 25_000;
    private static final int MAX_FILE_LINES = 200;

    private final TokenEstimator tokenEstimator;
    private final ConcurrentMap<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 创建使用指定 token 估算器的编译器。 */
    public ProjectKnowledgeCompiler(TokenEstimator tokenEstimator) {
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator 不能为空");
    }

    /** 加载根目录至 activePath 的完整项目知识文件。 */
    public synchronized ProjectKnowledgeContext compile(
            Path workspaceRoot,
            Path activePath,
            int maxTokens) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot 不能为空");
        Objects.requireNonNull(activePath, "activePath 不能为空");
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens 必须大于 0");
        }
        try {
            Path realRoot = workspaceRoot.toRealPath();
            Path realActive = activePath.toRealPath();
            if (!Files.isDirectory(realRoot)) {
                throw new ProjectKnowledgeException("workspaceRoot 必须是目录: " + realRoot);
            }
            if (!realActive.startsWith(realRoot)) {
                throw new ProjectKnowledgeException("activePath 必须位于 workspaceRoot 内: " + realActive);
            }
            Path activeDirectory = Files.isDirectory(realActive) ? realActive : realActive.getParent();
            if (activeDirectory == null || !activeDirectory.startsWith(realRoot)) {
                throw new ProjectKnowledgeException("activePath 没有工作区内父目录: " + realActive);
            }
            List<Path> directories = hierarchy(realRoot, activeDirectory);
            List<LoadedSource> loaded = loadSources(realRoot, directories);
            List<KnowledgeSource> discoveredSources = loaded.stream()
                    .map(LoadedSource::metadata)
                    .toList();
            String discoveredFingerprint = fingerprint(discoveredSources);
            CacheKey key = new CacheKey(realRoot, realActive, maxTokens);
            CacheEntry cached = cache.get(key);
            if (cached != null && cached.discoveredFingerprint().equals(discoveredFingerprint)) {
                return cached.context();
            }
            ProjectKnowledgeContext context = selectWithinBudget(loaded, maxTokens);
            CacheEntry replacement = cache.compute(key, (ignored, current) -> {
                if (current != null && current.discoveredFingerprint().equals(discoveredFingerprint)) {
                    return current;
                }
                return new CacheEntry(discoveredFingerprint, context);
            });
            return replacement.context();
        } catch (ProjectKnowledgeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ProjectKnowledgeException("项目知识路径解析或读取失败", exception);
        }
    }

    private List<Path> hierarchy(Path root, Path activeDirectory) {
        List<Path> reversed = new ArrayList<>();
        Path current = activeDirectory;
        while (current != null && current.startsWith(root)) {
            reversed.add(current);
            if (current.equals(root)) {
                break;
            }
            current = current.getParent();
        }
        if (reversed.isEmpty() || !reversed.getLast().equals(root)) {
            throw new ProjectKnowledgeException("无法建立工作区内知识目录层级: " + activeDirectory);
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private List<LoadedSource> loadSources(Path root, List<Path> directories) throws IOException {
        List<LoadedSource> loaded = new ArrayList<>();
        findExact(root, KnowledgeFileType.SOUL)
                .ifPresent(path -> loaded.add(readSource(root, path, KnowledgeFileType.SOUL, 0)));
        loadByType(root, directories, KnowledgeFileType.AGENTS, loaded);
        loadByType(root, directories, KnowledgeFileType.CLAUDE, loaded);
        return List.copyOf(loaded);
    }

    private void loadByType(
            Path root,
            List<Path> directories,
            KnowledgeFileType type,
            List<LoadedSource> target) throws IOException {
        for (Path directory : directories) {
            Optional<Path> exact = findExact(directory, type);
            if (exact.isPresent()) {
                int depth = directory.equals(root) ? 0 : root.relativize(directory).getNameCount();
                target.add(readSource(root, exact.orElseThrow(), type, depth));
            }
        }
    }

    private Optional<Path> findExact(Path directory, KnowledgeFileType type) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .filter(path -> path.getFileName().toString().equals(type.fileName()))
                    .findFirst();
        }
    }

    private LoadedSource readSource(
            Path root,
            Path sourcePath,
            KnowledgeFileType type,
            int depth) {
        String relativePath = relativePath(root, sourcePath);
        try {
            Path realTarget = sourcePath.toRealPath();
            if (!realTarget.startsWith(root)) {
                throw new ProjectKnowledgeException(
                        "知识文件真实目标位于工作区外: " + relativePath + " -> " + realTarget);
            }
            if (!Files.isRegularFile(realTarget)) {
                throw new ProjectKnowledgeException("知识来源不是普通文件: " + relativePath);
            }
            byte[] bytes = Files.readAllBytes(realTarget);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new ProjectKnowledgeLimitException(
                        relativePath,
                        ProjectKnowledgeLimitKind.BYTES,
                        bytes.length,
                        MAX_FILE_BYTES);
            }
            String content = decodeUtf8(bytes, relativePath);
            int lines = content.split("\\R", -1).length;
            if (lines > MAX_FILE_LINES) {
                throw new ProjectKnowledgeLimitException(
                        relativePath,
                        ProjectKnowledgeLimitKind.LINES,
                        lines,
                        MAX_FILE_LINES);
            }
            KnowledgeSource metadata = new KnowledgeSource(
                    relativePath,
                    type,
                    depth,
                    bytes.length,
                    lines,
                    sha256(bytes));
            return new LoadedSource(metadata, content);
        } catch (ProjectKnowledgeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ProjectKnowledgeException("知识文件读取失败: " + relativePath, exception);
        }
    }

    private String decodeUtf8(byte[] bytes, String relativePath) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new ProjectKnowledgeException("知识文件不是合法 UTF-8: " + relativePath, exception);
        }
    }

    private String renderPrompt(List<LoadedSource> loaded) {
        return loaded.stream()
                .map(source -> "### [" + source.metadata().fileType().name() + "] "
                        + source.metadata().relativePath() + "\n" + source.content())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    private ProjectKnowledgeContext selectWithinBudget(
            List<LoadedSource> loaded,
            int maxTokens) {
        if (loaded.isEmpty()) {
            return ProjectKnowledgeContext.empty();
        }
        LoadedSource rootAgents = loaded.stream()
                .filter(source -> source.metadata().fileType() == KnowledgeFileType.AGENTS)
                .filter(source -> source.metadata().depth() == 0)
                .findFirst()
                .orElse(null);
        List<LoadedSource> selected = new ArrayList<>();
        if (rootAgents != null) {
            int rootTokens = estimatePrompt(List.of(rootAgents));
            if (rootTokens > maxTokens) {
                throw new ProjectKnowledgeLimitException(
                        rootAgents.metadata().relativePath(),
                        ProjectKnowledgeLimitKind.TOKENS,
                        rootTokens,
                        maxTokens);
            }
            selected.add(rootAgents);
        }
        for (LoadedSource source : loaded) {
            if (source == rootAgents) {
                continue;
            }
            List<LoadedSource> trial = new ArrayList<>(selected);
            trial.add(source);
            int trialTokens = estimatePromptInSourceOrder(loaded, trial);
            if (trialTokens <= maxTokens) {
                selected.add(source);
            }
        }
        if (selected.isEmpty()) {
            return ProjectKnowledgeContext.empty();
        }
        List<LoadedSource> orderedSelected = loaded.stream()
                .filter(selected::contains)
                .toList();
        String prompt = renderPrompt(orderedSelected);
        int estimatedTokens = tokenEstimator.estimate(ChatMessage.user(prompt));
        if (estimatedTokens > maxTokens) {
            throw new ProjectKnowledgeLimitException(
                    orderedSelected.getFirst().metadata().relativePath(),
                    ProjectKnowledgeLimitKind.TOKENS,
                    estimatedTokens,
                    maxTokens);
        }
        List<KnowledgeSource> sources = orderedSelected.stream()
                .map(LoadedSource::metadata)
                .toList();
        return new ProjectKnowledgeContext(
                prompt,
                sources,
                fingerprint(sources),
                estimatedTokens);
    }

    private int estimatePrompt(List<LoadedSource> selected) {
        return tokenEstimator.estimate(ChatMessage.user(renderPrompt(selected)));
    }

    private int estimatePromptInSourceOrder(
            List<LoadedSource> loaded,
            List<LoadedSource> selected) {
        List<LoadedSource> ordered = loaded.stream()
                .filter(selected::contains)
                .toList();
        return estimatePrompt(ordered);
    }

    private String fingerprint(List<KnowledgeSource> sources) {
        MessageDigest digest = sha256Digest();
        for (KnowledgeSource source : sources) {
            String entry = source.fileType().name() + "\n"
                    + source.relativePath() + "\n"
                    + source.sha256() + "\n";
            digest.update(entry.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private String relativePath(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private record LoadedSource(KnowledgeSource metadata, String content) {
    }

    private record CacheKey(Path workspaceRoot, Path activePath, int maxTokens) {
    }

    private record CacheEntry(String discoveredFingerprint, ProjectKnowledgeContext context) {
    }
}
