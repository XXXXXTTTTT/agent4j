# Production Multi-Agent Orchestration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将既有 handoff 内核接入 Agent4J 生产图，并允许用户选择真实可执行的协作模式和角色模型组。

**Architecture:** 新增强类型编排模式与角色合同，将用户选择转换为受校验的状态变量。生产图使用已有 `AgentHandoffExecutor` 调度只读研究与 FRESH 审查子运行，只有现有 Coder 保留工作区写权限。会话 API、前端 Composer 和 Trace 均传递及呈现相同模式标识。

**Tech Stack:** Java 21、Spring Boot、既有 StateGraph/multiagent、PostgreSQL 会话服务、React、Vitest、JUnit 5、EDD。

---

### Task 1: 编排模式与角色模型组合同

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/orchestration/OrchestrationMode.java`
- Create: `agent-core/src/main/java/com/agent/core/orchestration/AgentRole.java`
- Create: `agent-core/src/main/java/com/agent/core/orchestration/OrchestrationRequest.java`
- Create: `agent-core/src/main/java/com/agent/core/orchestration/OrchestrationRequestValidator.java`
- Test: `agent-core/src/test/java/com/agent/core/orchestration/OrchestrationRequestValidatorTest.java`

- [ ] 写失败测试，覆盖三个精确模式、四个精确角色键、空组回退、未知模式、未知角色键和空模型组拒绝。
- [ ] 运行 `mvn -pl agent-core '-Dtest=OrchestrationRequestValidatorTest' test`，确认缺少合同而失败。
- [ ] 实现不可变合同和校验器；模型组回退只在主 `model.groupId` 为非空时发生。
- [ ] 运行同一测试，确认通过。

### Task 2: 会话 API 与状态注入

**Files:**
- Modify: `agent-web/src/main/java/com/agent/web/controller/SubmitConversationTurnRequest.java`
- Modify: `agent-web/src/main/java/com/agent/web/controller/ConversationController.java`
- Modify: `agent-web/src/main/java/com/agent/web/conversation/ConversationService.java`
- Test: `agent-web/src/test/java/com/agent/web/controller/ConversationControllerTest.java`

- [ ] 写失败测试，提交 `orchestrationMode` 和 `roleModelGroups` 后断言状态含精确模式与角色组变量；非法合同返回 400。
- [ ] 运行指定 Controller 测试，确认失败。
- [ ] 扩展请求和服务重载，调用核心校验器并仅写经验证的状态变量。
- [ ] 运行指定测试，确认通过。

### Task 3: 生产图模式编排

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/orchestration/ProductionMultiAgentOrchestrator.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/ProductionGraphConfiguration.java`
- Test: `agent-web/src/test/java/com/agent/web/config/ProductionMultiAgentGraphTest.java`

- [ ] 写失败测试，断言串行模式保留 Planner→Coder→Ops→Reviewer；并行侦察仅运行只读子图且汇总后单 Coder 写入；评审闭环的 Reviewer 使用 FRESH 上下文。
- [ ] 运行指定图测试，确认失败。
- [ ] 用现有 `AgentCatalog`、`AgentStateProjector` 和 `AgentHandoffExecutor` 注册协调、研究、实施和验证角色；对子图设置明确输出键、超时、次数和 Trace 发布。
- [ ] 运行指定图测试，确认通过。

### Task 4: 前端模式与角色模型组选择

**Files:**
- Modify: `agent-web/src/main/frontend/src/api/conversationApi.ts`
- Modify: `agent-web/src/main/frontend/src/hooks/useConversationWorkspace.ts`
- Modify: `agent-web/src/main/frontend/src/components/ConversationComposer.tsx`
- Create: `agent-web/src/main/frontend/src/components/OrchestrationModeSelector.tsx`
- Test: `agent-web/src/main/frontend/src/components/ConversationComposer.test.tsx`

- [ ] 写失败测试，选择模式与角色模型组后断言提交 JSON 的精确字段；串行模式不渲染无用角色选择。
- [ ] 运行指定 Vitest 测试，确认失败。
- [ ] 实现请求解码、类型和选择器；选项只来自当前用户已加载的模型组。
- [ ] 运行指定测试，确认通过。

### Task 5: Trace、EGG 与真实模型 EDD

**Files:**
- Modify: `agent-web/src/main/frontend/src/components/AgentConversation.tsx`
- Create: `agent-eval/src/test/java/com/agent/eval/ProductionMultiAgentEddTest.java`
- Test: `agent-core/src/test/java/com/agent/core/orchestration/OrchestrationRequestValidatorTest.java`

- [ ] 写 EGG 测试覆盖角色权限、并行只读、FRESH 审查、handoff 深度与子 Run Trace。
- [ ] 写 EDD，在 `AGENT_LLM_ENABLED=true` 时执行一个小型代码任务，并记录主/子 Run、模型、终端、Diff、审查结论和通过状态。
- [ ] 运行核心、Web、前端和 EDD 测试；构建 Docker 后以真实工作台选择三个模式进行验收。
- [ ] 提交仅包含多 Agent 功能、测试和文档的原子变更。
