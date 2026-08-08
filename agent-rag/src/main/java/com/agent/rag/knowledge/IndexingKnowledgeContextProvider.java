package com.agent.rag.knowledge;

import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.knowledge.KnowledgeContext;
import com.agent.core.knowledge.KnowledgeContextProvider;
import com.agent.core.knowledge.KnowledgeContextRequest;
import com.agent.rag.index.CodebaseIndexCoordinator;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 在读取 RAG 知识前确保工作区索引已经与当前内容一致。 */
public final class IndexingKnowledgeContextProvider implements KnowledgeContextProvider {

    private final CodebaseIndexCoordinator coordinator;
    private final KnowledgeContextProvider delegate;
    private final Duration timeout;

    /** 创建带精确索引等待上限的知识 Provider。 */
    public IndexingKnowledgeContextProvider(
            CodebaseIndexCoordinator coordinator,
            KnowledgeContextProvider delegate,
            Duration timeout) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator 不能为空");
        this.delegate = Objects.requireNonNull(delegate, "delegate 不能为空");
        this.timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
    }

    /** 等待仓库索引完成后加载项目规则和代码证据。 */
    @Override
    public KnowledgeContext load(KnowledgeContextRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        NodeExecutionContext.progress("开始校验代码库索引");
        try {
            coordinator.ensureIndexed(request.workspaceRoot(), request.repositoryId())
                    .get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw new ProjectKnowledgeException("代码库索引等待超时: " + timeout, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProjectKnowledgeException("代码库索引等待被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new ProjectKnowledgeException("代码库索引失败", cause);
        }
        NodeExecutionContext.progress("代码库索引已就绪");
        return delegate.load(request);
    }
}
