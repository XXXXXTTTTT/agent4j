package com.agent.rag.knowledge;

import com.agent.core.context.TokenEstimator;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.knowledge.KnowledgeContext;
import com.agent.core.knowledge.KnowledgeContextProvider;
import com.agent.core.knowledge.KnowledgeContextRequest;
import com.agent.core.knowledge.KnowledgeEvidence;
import com.agent.core.knowledge.KnowledgeEvidenceKind;
import com.agent.core.knowledge.KnowledgeEvidenceStatus;
import com.agent.rag.pipeline.RagContextDocument;
import com.agent.rag.pipeline.RagPipelineException;
import com.agent.rag.pipeline.RagRetrievalPipeline;
import com.agent.rag.pipeline.RagRetrievalPolicy;
import com.agent.rag.pipeline.RagRetrievalRequest;
import com.agent.rag.pipeline.RagRetrievalResult;
import com.agent.rag.pipeline.RagStageEvidence;
import com.agent.rag.pipeline.RagStageStatus;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 组合项目规则与按需代码证据的知识上下文 Provider。 */
public final class RagKnowledgeContextProvider implements KnowledgeContextProvider {

    private static final String RULES_TITLE = "项目规则（受当前指令和安全策略约束）";
    private static final String CODE_TITLE = "按需检索的代码证据";

    private final ProjectKnowledgeCompiler compiler;
    private final RagRetrievalPipeline pipeline;
    private final RagRetrievalPolicy basePolicy;
    private final TokenEstimator tokenEstimator;
    private final boolean strict;

    /** 注入项目规则编译器、RAG 流水线、基础策略和故障模式。 */
    public RagKnowledgeContextProvider(
            ProjectKnowledgeCompiler compiler,
            RagRetrievalPipeline pipeline,
            RagRetrievalPolicy basePolicy,
            TokenEstimator tokenEstimator,
            boolean strict) {
        this.compiler = Objects.requireNonNull(compiler, "compiler 不能为空");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline 不能为空");
        this.basePolicy = Objects.requireNonNull(basePolicy, "basePolicy 不能为空");
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator 不能为空");
        this.strict = strict;
    }

    /** 编译项目规则并在剩余预算内组合 RAG 代码证据。 */
    @Override
    public KnowledgeContext load(KnowledgeContextRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        NodeExecutionContext.progress("开始加载项目规则与代码知识");
        ProjectKnowledgeContext project = compiler.compile(
                request.workspaceRoot(), request.activePath(), request.maxTokens());
        NodeExecutionContext.progress("项目规则加载完成: " + project.sources().size() + " 个文件");

        List<KnowledgeEvidence> evidence = new ArrayList<>();
        project.sources().forEach(source -> evidence.add(new KnowledgeEvidence(
                KnowledgeEvidenceKind.PROJECT_FILE,
                source.relativePath(),
                KnowledgeEvidenceStatus.APPLIED,
                "已加载项目规则文件",
                null)));

        int projectOnlyTokens = estimatePrompt(project.prompt(), List.of());
        if (projectOnlyTokens > request.maxTokens()) {
            String source = project.sources().isEmpty()
                    ? "PROJECT_RULES"
                    : project.sources().getFirst().relativePath();
            throw new ProjectKnowledgeLimitException(
                    source,
                    ProjectKnowledgeLimitKind.TOKENS,
                    projectOnlyTokens,
                    request.maxTokens());
        }

        int remainingTokens = request.maxTokens() - project.estimatedTokens();
        if (remainingTokens <= 0) {
            evidence.add(new KnowledgeEvidence(
                    KnowledgeEvidenceKind.RAG_STAGE,
                    "RAG_PIPELINE",
                    KnowledgeEvidenceStatus.SKIPPED,
                    "项目规则已占用全部 token 预算",
                    null));
            return buildContext(project, List.of(), evidence, request.maxTokens());
        }

        RagRetrievalResult ragResult;
        try {
            RagRetrievalPolicy policy = new RagRetrievalPolicy(
                    basePolicy.rewriteLimit(),
                    basePolicy.hydeEnabled(),
                    basePolicy.retrievalLimit(),
                    basePolicy.rerankLimit(),
                    remainingTokens);
            ragResult = pipeline.retrieve(new RagRetrievalRequest(
                    request.repositoryId(),
                    request.query(),
                    request.complexity(),
                    policy));
            ragResult.evidence().forEach(item -> evidence.add(toEvidence(item)));
            NodeExecutionContext.progress(
                    "RAG 检索完成: " + ragResult.documents().size() + " 个代码证据");
        } catch (RuntimeException failure) {
            if (strict) {
                throw failure;
            }
            evidence.add(new KnowledgeEvidence(
                    KnowledgeEvidenceKind.RAG_STAGE,
                    "RAG_PIPELINE",
                    KnowledgeEvidenceStatus.DEGRADED,
                    "基础 RAG 失败，仅保留项目规则",
                    stackTrace(failure)));
            NodeExecutionContext.progress("RAG 检索失败，已回退到项目规则");
            return buildContext(project, List.of(), evidence, request.maxTokens());
        }

        List<RagContextDocument> documents = fitDocuments(
                project.prompt(), ragResult.documents(), request.maxTokens());
        return buildContext(project, documents, evidence, request.maxTokens());
    }

    private KnowledgeContext buildContext(
            ProjectKnowledgeContext project,
            List<RagContextDocument> documents,
            List<KnowledgeEvidence> evidence,
            int maxTokens) {
        String prompt = renderPrompt(project.prompt(), documents);
        int estimatedTokens = tokenEstimator.estimate(
                com.agent.core.llm.ChatMessage.user(prompt));
        if (estimatedTokens > maxTokens) {
            String source = project.sources().isEmpty()
                    ? "PROJECT_RULES"
                    : project.sources().getFirst().relativePath();
            throw new ProjectKnowledgeLimitException(
                    source,
                    ProjectKnowledgeLimitKind.TOKENS,
                    estimatedTokens,
                    maxTokens);
        }
        boolean degraded = evidence.stream()
                .anyMatch(item -> item.status() == KnowledgeEvidenceStatus.DEGRADED);
        List<KnowledgeEvidence> frozenEvidence = List.copyOf(evidence);
        return new KnowledgeContext(
                prompt,
                project.sources().size() + documents.size(),
                fingerprint(project.fingerprint(), documents),
                estimatedTokens,
                degraded,
                frozenEvidence);
    }

    private List<RagContextDocument> fitDocuments(
            String projectPrompt,
            List<RagContextDocument> documents,
            int maxTokens) {
        List<RagContextDocument> selected = new ArrayList<>(documents);
        while (!selected.isEmpty()
                && estimatePrompt(projectPrompt, selected) > maxTokens) {
            selected.removeLast();
        }
        return List.copyOf(selected);
    }

    private int estimatePrompt(String projectPrompt, List<RagContextDocument> documents) {
        return tokenEstimator.estimate(
                com.agent.core.llm.ChatMessage.user(renderPrompt(projectPrompt, documents)));
    }

    private String renderPrompt(
            String projectPrompt,
            List<RagContextDocument> documents) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(RULES_TITLE).append('\n');
        if (!projectPrompt.isEmpty()) {
            prompt.append(projectPrompt);
        }
        prompt.append("\n\n").append(CODE_TITLE).append('\n');
        for (int index = 0; index < documents.size(); index++) {
            RagContextDocument document = documents.get(index);
            prompt.append('[').append(index + 1).append("] ")
                    .append(document.path()).append(':')
                    .append(document.startLine()).append('-')
                    .append(document.endLine());
            if (document.symbol() != null) {
                prompt.append(' ').append(document.symbol());
            }
            prompt.append('\n').append(document.content());
            if (index + 1 < documents.size()) {
                prompt.append("\n\n");
            }
        }
        return prompt.toString();
    }

    private KnowledgeEvidence toEvidence(RagStageEvidence item) {
        KnowledgeEvidenceStatus status = switch (item.status()) {
            case APPLIED -> KnowledgeEvidenceStatus.APPLIED;
            case SKIPPED -> KnowledgeEvidenceStatus.SKIPPED;
            case DEGRADED -> KnowledgeEvidenceStatus.DEGRADED;
        };
        return new KnowledgeEvidence(
                KnowledgeEvidenceKind.RAG_STAGE,
                item.stage().name(),
                status,
                item.detail(),
                status == KnowledgeEvidenceStatus.DEGRADED ? item.errorStack() : null);
    }

    private String fingerprint(String projectFingerprint, List<RagContextDocument> documents) {
        MessageDigest digest = sha256Digest();
        digest.update(projectFingerprint.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
        for (RagContextDocument document : documents) {
            digest.update(document.childId().toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(document.contentSource().name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(sha256(document.content()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String sha256(String value) {
        return HexFormat.of().formatHex(
                sha256Digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
