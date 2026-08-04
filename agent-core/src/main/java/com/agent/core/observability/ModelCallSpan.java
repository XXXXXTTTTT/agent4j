package com.agent.core.observability;

/** 单次模型端点调用尝试的观测生命周期。 */
public interface ModelCallSpan extends AutoCloseable {

    /** 记录成功结果。 */
    void succeed(ModelCallSuccess success);

    /** 记录失败异常。 */
    void fail(Throwable failure);

    /** 关闭观测生命周期。 */
    @Override
    void close();
}
