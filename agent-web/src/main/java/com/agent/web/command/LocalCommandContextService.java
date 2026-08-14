package com.agent.web.command;

import com.agent.core.command.CommandContext;
import com.agent.core.command.CommandContextService;
import com.agent.core.command.CommandResult;
import com.agent.web.conversation.ConversationService;
import com.agent.web.conversation.ConversationTurnRecord;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 使用现有会话服务执行确定性的本地上下文控制。 */
public final class LocalCommandContextService implements CommandContextService {

    private final ConversationService conversationService;

    /** 创建本地上下文服务。 */
    public LocalCommandContextService(ConversationService conversationService) {
        this.conversationService = Objects.requireNonNull(
                conversationService, "conversationService 不能为空");
    }

    @Override
    public CommandResult context(CommandContext context) {
        List<ConversationTurnRecord> turns = turns(context);
        long estimatedTokens = turns.stream()
                .mapToLong(turn -> estimate(turn.userContent())
                        + estimate(turn.assistantContent()))
                .sum();
        return result("上下文统计", Map.of(
                "turns", turns.size(),
                "estimatedTokens", estimatedTokens));
    }

    @Override
    public CommandResult compact(CommandContext context, String focus) {
        List<ConversationTurnRecord> turns = turns(context);
        String summary = turns.stream()
                .skip(Math.max(0, turns.size() - 3L))
                .map(ConversationTurnRecord::userContent)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("暂无可压缩历史");
        return result("已生成本地上下文摘要", Map.of(
                "focus", focus == null ? "" : focus,
                "summary", summary,
                "turns", turns.size()));
    }

    @Override
    public CommandResult clear(CommandContext context) {
        var created = conversationService.createConversation(UUID.fromString(context.workspaceId()));
        return result("已创建新会话", Map.of("conversationId", created.conversationId().toString()));
    }

    @Override
    public CommandResult cost(CommandContext context) {
        return result("当前命令未产生新的模型调用", Map.of(
                "modelRequests", 0,
                "estimatedTokens", 0));
    }

    @Override
    public CommandResult permissions(CommandContext context, List<String> arguments) {
        return result("工作区命令权限由工作区成员权限治理", Map.of(
                "workspaceId", context.workspaceId(),
                "arguments", List.copyOf(arguments)));
    }

    private List<ConversationTurnRecord> turns(CommandContext context) {
        return conversationService.listTurns(UUID.fromString(context.conversationId()));
    }

    private long estimate(String value) {
        return value == null ? 0 : Math.max(1, value.getBytes(StandardCharsets.UTF_8).length / 4L);
    }

    private CommandResult result(String message, Map<String, Object> data) {
        return new CommandResult(CommandResult.Status.COMPLETED, null, message, data);
    }
}
