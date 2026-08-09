package com.agent.eval;

/** 评测失败的精确分类。 */
public enum FailureCategory {
    NONE,
    ROUTING,
    MODEL_TRANSPORT,
    TOOL_PROTOCOL,
    AUTHORIZATION,
    TIMEOUT,
    BUDGET,
    PERSISTENCE,
    ASSERTION,
    UNKNOWN
}
