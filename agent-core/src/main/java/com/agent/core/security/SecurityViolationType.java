package com.agent.core.security;

/** 安全违规的固定分类。 */
public enum SecurityViolationType {
    PROMPT_INJECTION,
    TOOL_PARAMETER,
    AUTHORIZATION,
    OUTPUT_REDACTION
}
