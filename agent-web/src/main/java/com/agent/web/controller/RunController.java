package com.agent.web.controller;

import com.agent.core.engine.AgentRunService;
import jakarta.validation.Valid;
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

    /** 创建 Run Controller。 */
    public RunController(AgentRunService runService) {
        this.runService = Objects.requireNonNull(runService, "runService 不能为空");
    }

    /** 创建并异步启动 Run。 */
    @PostMapping
    public ResponseEntity<RunView> start(@Valid @RequestBody StartRunRequest request) {
        RunView view = RunView.from(runService.start(request.graphId(), request.initialState()));
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
}
