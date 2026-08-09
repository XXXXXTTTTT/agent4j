package com.agent.core.security;

/** Prompt Injection 检测端口。 */
public interface PromptInjectionDetector {

    /** 检查文本并返回不包含原文的安全结果。 */
    PromptSecurityAssessment inspect(PromptSecurityContext context, String text);
}
