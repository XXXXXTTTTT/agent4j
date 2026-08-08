package com.agent.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 项目知识总开关与 Planner token 预算。 */
@ConfigurationProperties(prefix = "agent.knowledge")
public record KnowledgeProperties(boolean enabled, int maxTokens) {

    /** 校验知识上下文 token 预算。 */
    public void validate() {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("agent.knowledge.max-tokens 必须大于 0");
        }
    }
}
