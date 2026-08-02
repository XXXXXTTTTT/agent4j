package com.agent.web.controller;

import com.agent.core.engine.ApprovalCommand;
import com.agent.core.engine.ApprovalDecision;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.Map;

/**
 * Run 审批请求。
 *
 * @param decision 审批决定
 * @param expectedVersion 预期 Checkpoint 版本
 * @param reason 审批原因
 * @param variableUpdates 允许修改的精确状态变量
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ApprovalRequest(
        @NotNull ApprovalDecision decision,
        @PositiveOrZero long expectedVersion,
        @NotBlank String reason,
        Map<String, String> variableUpdates) {

    /** 冻结审批变量更新；省略字段精确等价于空 Map。 */
    public ApprovalRequest {
        variableUpdates = variableUpdates == null
                ? Map.of()
                : Map.copyOf(variableUpdates);
    }

    /** 将 HTTP 请求转换为核心审批命令。 */
    public ApprovalCommand toCommand() {
        return new ApprovalCommand(decision, expectedVersion, reason, variableUpdates);
    }
}
