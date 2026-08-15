package com.agent.web.command;

import com.agent.core.command.CommandChannel;
import com.agent.core.command.CommandDefinition;
import com.agent.core.command.CommandParameter;
import com.agent.core.command.CommandPermission;
import com.agent.core.command.CommandSource;
import com.agent.core.command.CommandTemplateRenderer;
import com.agent.core.command.WorkflowCommandBridge;
import com.agent.core.command.WorkflowCommandHandler;

import java.util.List;
import java.util.Objects;

/** 提供独立、可组合的内置工作流命令目录。 */
public final class BuiltInWorkflowCommands {

    private static final CommandParameter REQUEST = new CommandParameter("request", "工作请求", true);

    private BuiltInWorkflowCommands() {
    }

    /** 创建会话工作台可用的内置工作流命令。 */
    public static List<CommandDefinition> definitions(WorkflowCommandBridge bridge) {
        Objects.requireNonNull(bridge, "bridge 不能为空");
        CommandTemplateRenderer renderer = new CommandTemplateRenderer();
        return List.of(
                workflow("plan", "计划", "为请求制定实施计划", List.of(),
                        "请制定可执行的实施计划：${request}", renderer, bridge),
                workflow("review", "审查", "审查当前工作区变更", List.of("code-review"),
                        "请审查当前工作区并处理请求：${request}", renderer, bridge),
                workflow("debug", "调试", "定位问题根因并验证修复", List.of("diagnose"),
                        "请调试并定位根因：${request}。收集证据后实施最小修复并运行验证。",
                        renderer, bridge),
                workflow("fix", "修复", "修复缺陷并运行聚焦验证", List.of(),
                        "请修复以下问题：${request}。先定位根因，再实施最小修复并运行聚焦验证。",
                        renderer, bridge),
                workflow("test", "测试", "编写或更新测试并运行验证", List.of("tests", "write-tests"),
                        "请为以下请求编写或更新测试，并运行相关验证：${request}", renderer, bridge),
                workflow("explain", "解释", "只读解释代码或设计", List.of(),
                        "请只读解释以下内容，不修改文件：${request}", renderer, bridge),
                workflow("refactor", "重构", "在保持行为的前提下重构代码", List.of(),
                        "请重构以下内容，保持现有行为并运行相关测试：${request}", renderer, bridge),
                workflow("security-review", "安全审查", "分析安全风险并提出修复", List.of("security"),
                        "请执行安全审查：${request}。按风险等级给出证据和修复建议。", renderer, bridge),
                workflow("research", "研究", "在工作区执行只读研究", List.of("investigate"),
                        "请在当前工作区执行只读研究：${request}。基于证据给出结论，不修改文件。",
                        renderer, bridge),
                workflow("document", "文档", "更新相关项目文档", List.of("docs"),
                        "请为以下请求更新相关项目文档，并说明验证结果：${request}", renderer, bridge),
                workflow("implement", "实施", "实施明确需求并运行验证", List.of("build"),
                        "请实施以下明确需求：${request}。完成后运行针对性验证并汇报证据。", renderer, bridge),
                workflow("verify", "验证", "运行针对性验证并解释结果", List.of("validate"),
                        "请验证以下目标：${request}。只运行与目标相关的检查，并报告实际输出。", renderer, bridge),
                workflow("inspect", "检查", "只读检查工作区结构与实现", List.of("analyze"),
                        "请只读检查当前工作区并分析：${request}。不要修改文件，结论必须引用实际证据。", renderer, bridge),
                workflow("architecture", "架构", "分析架构影响并提出实施方案", List.of("design"),
                        "请分析以下架构问题并提出可实施方案：${request}。说明影响范围、取舍和验证方式。", renderer, bridge),
                workflow("release", "发布", "检查发布前门禁与交付状态", List.of("preflight"),
                        "请执行发布前检查：${request}。检查构建、测试、配置和工作树状态，不跳过失败项。", renderer, bridge));
    }

    private static CommandDefinition workflow(
            String name,
            String displayName,
            String description,
            List<String> aliases,
            String template,
            CommandTemplateRenderer renderer,
            WorkflowCommandBridge bridge) {
        return new CommandDefinition(
                name,
                displayName,
                description,
                aliases,
                List.of(REQUEST),
                CommandChannel.WORKFLOW_SKILL,
                CommandSource.BUILT_IN,
                CommandPermission.OPERATOR,
                new WorkflowCommandHandler(template, List.of(REQUEST), renderer, bridge));
    }
}
