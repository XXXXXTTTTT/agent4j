package com.agent.web.config;

import com.agent.core.command.CommandAuthorizationDecision;
import com.agent.core.command.CommandAuthorizationPolicy;
import com.agent.core.command.CommandChannel;
import com.agent.core.command.CommandDefinition;
import com.agent.core.command.CommandDispatcher;
import com.agent.core.command.CommandPermission;
import com.agent.core.command.CommandRegistry;
import com.agent.core.command.CommandResult;
import com.agent.core.command.CommandSource;
import com.agent.core.command.CommandTemplateRenderer;
import com.agent.core.command.InMemoryCommandRegistry;
import com.agent.core.command.MarkdownCommandLoader;
import com.agent.core.command.SystemCommandHandlers;
import com.agent.core.command.WorkflowCommandHandler;
import com.agent.web.audit.ConversationAuditSink;
import com.agent.web.command.ConversationWorkflowCommandBridge;
import com.agent.web.command.LocalCommandContextService;
import com.agent.web.command.WorkspaceCommandRuntimeProvider;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 装配按工作区隔离的 Slash Command 运行时。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CommandProperties.class)
@ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
public class CommandRegistryConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandRegistryConfiguration.class);

    /** 创建工作流桥接器。 */
    @Bean
    ConversationWorkflowCommandBridge conversationWorkflowCommandBridge(
            com.agent.web.conversation.ConversationService conversationService,
            ConversationAuditSink auditSink,
            Clock harnessClock) {
        return new ConversationWorkflowCommandBridge(conversationService, auditSink, harnessClock);
    }

    /** 创建实时工作区命令运行时提供器。 */
    @Bean
    WorkspaceCommandRuntimeProvider workspaceCommandRuntimeProvider(
            CommandProperties properties,
            ConversationWorkflowCommandBridge workflowBridge,
            com.agent.web.conversation.ConversationService conversationService,
            WorkspaceAccessService workspaceAccessService,
            ActorResolver actorResolver,
            Clock harnessClock) {
        return workspace -> createRuntime(
                properties, workflowBridge, conversationService,
                workspaceAccessService, actorResolver, harnessClock, workspace);
    }

    private WorkspaceCommandRuntimeProvider.Runtime createRuntime(
            CommandProperties properties,
            ConversationWorkflowCommandBridge workflowBridge,
            com.agent.web.conversation.ConversationService conversationService,
            WorkspaceAccessService workspaceAccessService,
            ActorResolver actorResolver,
            Clock clock,
            WorkspaceRecord workspace) {
        InMemoryCommandRegistry registry = new InMemoryCommandRegistry();
        LocalCommandContextService contextService = new LocalCommandContextService(conversationService);
        List<CommandDefinition> definitions = new ArrayList<>(SystemCommandHandlers.definitions(
                registry,
                contextService,
                (context, checkpoint) -> CommandResult.failure(
                        CommandResult.Status.FAILED,
                        "Checkpoint 回滚需要运行时版本引用: " + checkpoint)));
        definitions.addAll(builtInWorkflows(workflowBridge));
        Path globalDirectory = properties.globalDirectory().isBlank()
                ? null : Path.of(properties.globalDirectory());
        Path workspaceDirectory = workspace.workspacePath().resolve(".agent").resolve("commands");
        definitions.addAll(new MarkdownCommandLoader(properties.maxFileBytes(), workflowBridge)
                .load(globalDirectory, workspaceDirectory));
        registry.replace(definitions);
        CommandAuthorizationPolicy policy = (definition, context) -> authorize(
                definition, context, workspaceAccessService, actorResolver);
        CommandDispatcher dispatcher = new CommandDispatcher(
                registry,
                policy,
                event -> LOGGER.info(
                        "Slash Command 审计 command={} status={} workspace={} conversation={}",
                        event.commandName(), event.status(), event.workspaceId(), event.conversationId()),
                clock);
        return new WorkspaceCommandRuntimeProvider.Runtime(registry, dispatcher);
    }

    private List<CommandDefinition> builtInWorkflows(ConversationWorkflowCommandBridge bridge) {
        CommandTemplateRenderer renderer = new CommandTemplateRenderer();
        var request = new com.agent.core.command.CommandParameter("request", "工作请求", true);
        return List.of(
                new CommandDefinition(
                        "plan", "计划", "为请求制定实施计划", List.of(), List.of(request),
                        CommandChannel.WORKFLOW_SKILL, CommandSource.BUILT_IN, CommandPermission.OPERATOR,
                        new WorkflowCommandHandler("请制定实施计划：${request}", List.of(request), renderer, bridge)),
                new CommandDefinition(
                        "review", "审查", "审查当前工作区变更", List.of("code-review"), List.of(request),
                        CommandChannel.WORKFLOW_SKILL, CommandSource.BUILT_IN, CommandPermission.OPERATOR,
                        new WorkflowCommandHandler("请审查当前工作区并处理请求：${request}", List.of(request), renderer, bridge)));
    }

    private CommandAuthorizationDecision authorize(
            CommandDefinition definition,
            com.agent.core.command.CommandContext context,
            WorkspaceAccessService workspaceAccessService,
            ActorResolver actorResolver) {
        WorkspacePermission required = switch (definition.permission()) {
            case VIEWER -> WorkspacePermission.VIEWER;
            case OPERATOR -> WorkspacePermission.OPERATOR;
            case ADMIN -> WorkspacePermission.OWNER;
        };
        try {
            Actor actor = actorResolver.current();
            workspaceAccessService.requireWorkspace(
                    UUID.fromString(context.workspaceId()), actor.userId(), required);
            return CommandAuthorizationDecision.allow();
        } catch (RuntimeException exception) {
            return CommandAuthorizationDecision.deny("命令权限不足");
        }
    }
}
