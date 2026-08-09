package com.agent.core.llm;

/** 推理端点拒绝准入的原因。 */
public enum InferenceRejectionReason {
    /** 并发许可未在排队时限内获得。 */
    CONCURRENCY_LIMIT,
    /** 最近一分钟请求数已达到上限。 */
    RATE_LIMIT
}
