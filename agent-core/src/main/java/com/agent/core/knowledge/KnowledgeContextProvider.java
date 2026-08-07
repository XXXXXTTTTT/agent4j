package com.agent.core.knowledge;

import java.util.Objects;

/** 提供项目规则与检索证据的知识上下文端口。 */
@FunctionalInterface
public interface KnowledgeContextProvider {

    /** 加载一次知识上下文。 */
    KnowledgeContext load(KnowledgeContextRequest request);

    /** 返回不访问外部资源的空 Provider。 */
    static KnowledgeContextProvider empty() {
        return EmptyProvider.INSTANCE;
    }

    /** 确定性空 Provider 实现。 */
    final class EmptyProvider implements KnowledgeContextProvider {
        private static final EmptyProvider INSTANCE = new EmptyProvider();

        private EmptyProvider() {
        }

        @Override
        public KnowledgeContext load(KnowledgeContextRequest request) {
            Objects.requireNonNull(request, "request 不能为空");
            return KnowledgeContext.empty();
        }
    }
}
