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
        BuiltInWorkflowTemplates templates = new BuiltInWorkflowTemplates();
        return List.of(
                workflow("plan", "计划", "为请求制定实施计划", List.of(),
                        templates.template("plan"), renderer, bridge),
                workflow("review", "审查", "审查当前工作区变更", List.of("code-review"),
                        templates.template("review"), renderer, bridge),
                workflow("debug", "调试", "定位问题根因并验证修复", List.of("diagnose"),
                        templates.template("debug"),
                        renderer, bridge),
                workflow("fix", "修复", "修复缺陷并运行聚焦验证", List.of(),
                        templates.template("fix"),
                        renderer, bridge),
                workflow("test", "测试", "编写或更新测试并运行验证", List.of("tests", "write-tests"),
                        templates.template("test"), renderer, bridge),
                workflow("explain", "解释", "只读解释代码或设计", List.of(),
                        templates.template("explain"), renderer, bridge),
                workflow("refactor", "重构", "在保持行为的前提下重构代码", List.of(),
                        templates.template("refactor"), renderer, bridge),
                workflow("security-review", "安全审查", "分析安全风险并提出修复", List.of("security"),
                        templates.template("security-review"), renderer, bridge),
                workflow("research", "研究", "在工作区执行只读研究", List.of("investigate"),
                        templates.template("research"),
                        renderer, bridge),
                workflow("document", "文档", "更新相关项目文档", List.of("docs"),
                        templates.template("document"), renderer, bridge),
                workflow("implement", "实施", "实施明确需求并运行验证", List.of("build"),
                        templates.template("implement"), renderer, bridge),
                workflow("verify", "验证", "运行针对性验证并解释结果", List.of("validate"),
                        templates.template("verify"), renderer, bridge),
                workflow("inspect", "检查", "只读检查工作区结构与实现", List.of("analyze"),
                        templates.template("inspect"), renderer, bridge),
                workflow("architecture", "架构", "分析架构影响并提出实施方案", List.of("design"),
                        templates.template("architecture"), renderer, bridge),
                workflow("release", "发布", "检查发布前门禁与交付状态", List.of("preflight"),
                        templates.template("release"), renderer, bridge),
                workflow("tasks", "任务", "拆解请求并维护可执行任务清单", List.of("todo"),
                        templates.template("tasks"),
                        renderer, bridge));
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
