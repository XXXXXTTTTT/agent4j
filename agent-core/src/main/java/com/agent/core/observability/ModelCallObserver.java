package com.agent.core.observability;

import java.util.Objects;

/** 为每次模型端点调用创建观测生命周期。 */
@FunctionalInterface
public interface ModelCallObserver {

    /** 开始观测一次模型端点调用。 */
    ModelCallSpan start(ModelCallStart start);

    /** 返回无副作用的模型调用观测器。 */
    static ModelCallObserver noop() {
        return start -> {
            Objects.requireNonNull(start, "start 不能为空");
            return new ModelCallSpan() {
                @Override
                public void succeed(ModelCallSuccess success) {
                }

                @Override
                public void fail(Throwable failure) {
                }

                @Override
                public void close() {
                }
            };
        };
    }
}
