package com.agent.web.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 创建空会话请求，当前没有可选字段。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateConversationRequest() {
}
