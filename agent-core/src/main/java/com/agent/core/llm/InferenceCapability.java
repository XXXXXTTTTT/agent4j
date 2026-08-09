package com.agent.core.llm;

/** 模型推理端点可声明的能力。 */
public enum InferenceCapability {
    /** 支持非流式 Chat Completions。 */
    CHAT_COMPLETIONS,
    /** 支持 SSE 增量输出。 */
    STREAMING,
    /** 支持工具调用请求。 */
    TOOL_CALLING,
    /** 支持视觉输入。 */
    VISION_INPUT
}
