package com.agent.web.controller;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.AgentState;
import com.agent.core.nodes.ToolAgentNode;
import com.agent.core.skill.SkillCatalogProvider;
import com.agent.core.skill.SkillCatalogSnapshotCodec;
import com.agent.web.config.ProductionAgentProperties;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import com.agent.web.validation.ReviewerUrlValidator;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import com.agent.web.workspace.WorkspaceRecord;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 提供 Agent Run 生命周期 REST API。 */
@RestController
@RequestMapping("/api/runs")
public final class RunController {

    private final AgentRunService runService;
    private final ObjectProvider<ProductionAgentProperties> productionProperties;
    private final ActorResolver actorResolver;
    private final ObjectProvider<WorkspaceAccessService> workspaceAccess;
    private final ObjectProvider<SkillCatalogProvider> skillCatalogProvider;
    private final SkillCatalogSnapshotCodec skillCatalogSnapshotCodec;

    /** 创建 Run Controller。 */
    public RunController(
            AgentRunService runService,
            ObjectProvider<ProductionAgentProperties> productionProperties,
            ActorResolver actorResolver,
            ObjectProvider<WorkspaceAccessService> workspaceAccess,
            ObjectProvider<SkillCatalogProvider> skillCatalogProvider,
            ObjectMapper objectMapper) {
        this.runService = Objects.requireNonNull(runService, "runService 不能为空");
        this.productionProperties = Objects.requireNonNull(
                productionProperties, "productionProperties 不能为空");
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
        this.workspaceAccess = Objects.requireNonNull(workspaceAccess, "workspaceAccess 不能为空");
        this.skillCatalogProvider = Objects.requireNonNull(
                skillCatalogProvider, "skillCatalogProvider 不能为空");
        this.skillCatalogSnapshotCodec = new SkillCatalogSnapshotCodec(
                Objects.requireNonNull(objectMapper, "objectMapper 不能为空"));
    }

    /** 创建并异步启动 Run。 */
    @PostMapping
    public ResponseEntity<RunView> start(@Valid @RequestBody StartRunRequest request) {
        if ("code-agent".equals(request.graphId()) || "governed-cli".equals(request.graphId())) {
            throw new IllegalArgumentException("该图必须使用专用启动入口");
        }
        RunView view = RunView.from(runService.start(request.graphId(), request.initialState()));
        return ResponseEntity.accepted().body(view);
    }

    /** 创建使用生产 Graph 的任务优先 Run。 */
    @PostMapping("/code-agent")
    public ResponseEntity<RunView> startCodeAgent(
            @Valid @RequestBody CodeAgentStartRequest request) {
        ProductionAgentProperties properties = productionProperties.getIfAvailable();
        if (properties == null || !properties.enabled()) {
            throw new IllegalStateException("生产 Code Agent 未启用");
        }
        WorkspaceAccessService access = workspaceAccess.getIfAvailable();
        if (access == null) {
            throw new IllegalStateException("工作区访问服务未配置");
        }
        Actor actor = actorResolver.current();
        WorkspaceRecord workspace = access.requireWorkspace(
                request.workspaceId(), actor.userId(), WorkspacePermission.OPERATOR);
        AgentState state = AgentState.empty()
                .withVariable("planner.task", request.task().trim())
                .withVariable("planner.repositoryId", workspace.repositoryId())
                .withVariable("planner.userId", actor.userId())
                .withVariable("conversation.workspaceId", workspace.workspaceId().toString())
                .withVariable("coder.workspacePath", workspace.workspacePath().toString());
        SkillCatalogProvider provider = skillCatalogProvider.getIfAvailable();
        if (provider != null) {
            state = state.withVariable(ToolAgentNode.SKILL_CATALOG_SNAPSHOT_KEY,
                    skillCatalogSnapshotCodec.encode(provider.resolve(
                            actor.userId(), workspace.workspaceId())));
        }
        String reviewerUrl = choose(request.reviewerUrl(), properties.reviewerUrl());
        if (!reviewerUrl.isBlank()) {
            state = state.withVariable(
                    "reviewer.url", ReviewerUrlValidator.validateOptional(reviewerUrl));
        }
        RunView view = RunView.from(runService.start("code-agent", state));
        return ResponseEntity.accepted().body(view);
    }

    /** 查询 Run 最新权威快照。 */
    @GetMapping("/{runId}")
    public RunView get(@PathVariable UUID runId) {
        return RunView.from(runService.get(runId));
    }

    /** 按版本升序查询 Run 的全部权威快照。 */
    @GetMapping("/{runId}/history")
    public List<RunView> history(@PathVariable UUID runId) {
        return runService.history(runId).stream()
                .map(RunView::from)
                .toList();
    }

    /** 批准或拒绝等待中的 Run。 */
    @PostMapping("/{runId}/approval")
    public ResponseEntity<RunView> decide(
            @PathVariable UUID runId,
            @Valid @RequestBody ApprovalRequest request) {
        RunView view = RunView.from(runService.decide(runId, request.toCommand()));
        return ResponseEntity.accepted().body(view);
    }

    private String choose(String requested, String configured) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        return configured == null ? "" : configured.trim();
    }

}
