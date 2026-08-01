package com.agent.web.controller;

import com.agent.core.engine.AgentState;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建 Run 的请求。
 *
 * @param graphId 精确图标识
 * @param initialState 初始不可变状态
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record StartRunRequest(
        @NotBlank String graphId,
        @NotNull @Valid AgentState initialState) {
}
