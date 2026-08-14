package com.agent.core.command;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只替换显式白名单变量的命令模板渲染器。 */
public final class CommandTemplateRenderer {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_]*)}");

    /** 渲染参数和调用上下文变量，未知变量直接拒绝。 */
    public String render(
            String template,
            List<CommandParameter> parameters,
            CommandInvocation invocation,
            CommandContext context) {
        Objects.requireNonNull(template, "template 不能为空");
        Objects.requireNonNull(parameters, "parameters 不能为空");
        Objects.requireNonNull(invocation, "invocation 不能为空");
        Objects.requireNonNull(context, "context 不能为空");
        if (invocation.arguments().size() < parameters.stream()
                .filter(CommandParameter::required).count()
                || invocation.arguments().size() > parameters.size()) {
            throw new IllegalArgumentException("模板参数数量不合法");
        }
        Map<String, String> values = new HashMap<>();
        for (int index = 0; index < parameters.size() && index < invocation.arguments().size(); index++) {
            values.put(parameters.get(index).name(), invocation.arguments().get(index));
        }
        values.put("actorId", context.actorId());
        values.put("workspaceId", context.workspaceId());
        values.put("conversationId", context.conversationId());
        values.putAll(context.variables());
        Matcher matcher = VARIABLE.matcher(template);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) {
                throw new IllegalArgumentException("模板变量未获准: " + matcher.group(1));
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(output);
        return output.toString();
    }
}
