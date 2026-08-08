package com.agent.rag.knowledge;

import com.agent.core.context.TokenEstimator;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.knowledge.KnowledgeContext;
import com.agent.core.knowledge.KnowledgeContextProvider;
import com.agent.core.knowledge.KnowledgeContextRequest;
import com.agent.core.knowledge.KnowledgeEvidence;
import com.agent.core.knowledge.KnowledgeEvidenceKind;
import com.agent.core.knowledge.KnowledgeEvidenceStatus;

import java.util.List;
import java.util.Objects;

/** 仅加载项目规则文件的知识 Provider，不创建代码 RAG 阶段证据。 */
public final class ProjectFileKnowledgeContextProvider implements KnowledgeContextProvider {

    private final ProjectKnowledgeCompiler compiler;
    private final TokenEstimator tokenEstimator;

    /** 创建文件知识 Provider。 */
    public ProjectFileKnowledgeContextProvider(
            ProjectKnowledgeCompiler compiler,
            TokenEstimator tokenEstimator) {
        this.compiler = Objects.requireNonNull(compiler, "compiler 不能为空");
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator 不能为空");
    }

    /** 编译当前工作区规则并转换为核心知识上下文。 */
    @Override
    public KnowledgeContext load(KnowledgeContextRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        NodeExecutionContext.progress("开始加载项目文件知识");
        ProjectKnowledgeContext project = compiler.compile(
                request.workspaceRoot(), request.activePath(), request.maxTokens());
        List<KnowledgeEvidence> evidence = project.sources().stream()
                .map(source -> new KnowledgeEvidence(
                        KnowledgeEvidenceKind.PROJECT_FILE,
                        source.relativePath(),
                        KnowledgeEvidenceStatus.APPLIED,
                        "已加载项目规则文件",
                        null))
                .toList();
        int estimatedTokens = tokenEstimator.estimate(
                com.agent.core.llm.ChatMessage.user(project.prompt()));
        NodeExecutionContext.progress(
                "项目文件知识加载完成: " + project.sources().size() + " 个文件");
        return new KnowledgeContext(
                project.prompt(),
                project.sources().size(),
                project.fingerprint(),
                estimatedTokens,
                false,
                evidence);
    }
}
