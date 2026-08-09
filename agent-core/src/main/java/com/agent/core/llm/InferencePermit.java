package com.agent.core.llm;

/** 一次推理请求占用的并发许可。 */
@FunctionalInterface
public interface InferencePermit extends AutoCloseable {

    /** 释放许可；重复调用不得重复释放。 */
    @Override
    void close();
}
