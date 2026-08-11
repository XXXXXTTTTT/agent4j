# 会话、工作区与模型池管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Agent4J Web 实现可解释的会话生命周期、工作区去重和用户级模型组/API 池配置。

**Architecture:** 继续复用现有 `ConversationService`、`WorkspaceAccessService` 和 JDBC 仓储。会话删除采用数据库事务级联；工作区唯一性在服务层兼容历史重复记录，并由迁移补充唯一索引。模型 Provider、Endpoint、Group 使用新的用户隔离 JDBC 表和服务，Router 通过请求中的组 ID解析端点并在组内执行优先级/权重降级。

**Tech Stack:** Java 21、Spring Boot 3.3、Spring JDBC、PostgreSQL、React 19、TypeScript、Vitest。

---

### Task 1: 会话删除和归档筛选

**Files:**
- Modify: `agent-web/src/main/java/com/agent/web/conversation/ConversationRepository.java`
- Modify: `agent-web/src/main/java/com/agent/web/conversation/ConversationService.java`
- Modify: `agent-web/src/main/java/com/agent/web/controller/ConversationController.java`
- Create: `agent-web/src/main/resources/db/migration/V4__conversation_delete_and_workspace_key.sql`
- Test: `agent-web/src/test/java/com/agent/web/conversation/ConversationServiceTest.java`
- Test: `agent-web/src/test/java/com/agent/web/controller/ConversationControllerTest.java`

- [ ] 写测试：归档后默认列表隐藏、归档筛选可见；删除调用仓储级联删除并保留工作区；删除后读取抛统一不可见异常。
- [ ] 运行 `mvn -pl agent-web -am -Dtest=ConversationServiceTest,ConversationControllerTest -Dfrontend.skip=true test`，确认测试因缺少删除接口失败。
- [ ] 增加 `deleteConversation` 仓储方法、服务方法和 `DELETE` Controller；迁移为轮次/Run 外键增加级联删除，并为会话状态查询增加 `includeArchived` 参数。
- [ ] 重新运行同一测试并确认通过。

### Task 2: 工作区规范化和历史去重

**Files:**
- Modify: `agent-web/src/main/java/com/agent/web/workspace/WorkspaceAccessService.java`
- Modify: `agent-web/src/main/java/com/agent/web/persistence/JdbcConversationRepository.java`
- Modify: `agent-web/src/main/java/com/agent/web/controller/WorkspaceController.java`
- Modify: `agent-web/src/main/resources/db/migration/V4__conversation_delete_and_workspace_key.sql`
- Test: `agent-web/src/test/java/com/agent/web/workspace/WorkspaceAccessServiceTest.java`
- Test: `agent-web/src/test/java/com/agent/web/persistence/JdbcConversationRepositoryIntegrationTest.java`

- [ ] 写测试：同一用户、规范化路径和仓库创建返回同一工作区；不同仓库或路径保持独立；列表只返回一条。
- [ ] 运行目标测试确认失败。
- [ ] 实现规范化键查询、创建幂等和列表去重；迁移清理历史重复记录后增加唯一索引。
- [ ] 运行目标测试确认通过。

### Task 3: 用户级 Model Provider/Endpoint/Group

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/model/ModelProviderRecord.java`
- Create: `agent-web/src/main/java/com/agent/web/model/ModelEndpointRecord.java`
- Create: `agent-web/src/main/java/com/agent/web/model/ModelGroupRecord.java`
- Create: `agent-web/src/main/java/com/agent/web/model/ModelConfigurationService.java`
- Create: `agent-web/src/main/java/com/agent/web/controller/ModelConfigurationController.java`
- Create: `agent-web/src/main/resources/db/migration/V5__create_user_model_configuration.sql`
- Test: `agent-web/src/test/java/com/agent/web/model/ModelConfigurationServiceTest.java`
- Test: `agent-web/src/test/java/com/agent/web/controller/ModelConfigurationControllerTest.java`

- [ ] 写测试：Provider/API Key 只返回掩码；不同用户隔离；组端点按优先级和权重排序；删除被组引用的 Provider 被拒绝。
- [ ] 运行目标测试确认失败。
- [ ] 实现不可变记录、JDBC 持久化、密钥掩码、权限校验和精确 JSON 合约。
- [ ] 运行目标测试确认通过。

### Task 4: Router 组选择与降级

**Files:**
- Modify: `agent-core/src/main/java/com/agent/core/llm/ModelRouter.java`
- Modify: `agent-core/src/main/java/com/agent/core/llm/ModelRequest.java`
- Create: `agent-core/src/main/java/com/agent/core/llm/ModelGroupSelection.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/ProductionGraphConfiguration.java`
- Test: `agent-core/src/test/java/com/agent/core/llm/ModelRouterTest.java`

- [ ] 写测试：指定组 ID 只选择组内端点；首端点失败后按优先级/权重降级；无组 ID 使用默认组；所有端点失败保留完整原因。
- [ ] 运行目标测试确认失败。
- [ ] 增加可选组选择字段并注入用户组解析器，保持未配置时现有 `.env` 路由兼容。
- [ ] 运行目标测试确认通过。

### Task 5: 前端工作区、会话和模型组界面

**Files:**
- Modify: `agent-web/src/main/frontend/src/api/contracts.ts`
- Modify: `agent-web/src/main/frontend/src/api/conversationApi.ts`
- Modify: `agent-web/src/main/frontend/src/hooks/useConversationWorkspace.ts`
- Modify: `agent-web/src/main/frontend/src/components/ConversationSidebar.tsx`
- Modify: `agent-web/src/main/frontend/src/components/ConversationComposer.tsx`
- Modify: `agent-web/src/main/frontend/src/components/Workbench.tsx`
- Create: `agent-web/src/main/frontend/src/components/ModelSettingsDialog.tsx`
- Modify: `agent-web/src/main/frontend/src/styles.css`
- Test: `agent-web/src/main/frontend/src/components/ConversationSidebar.test.tsx`
- Test: `agent-web/src/main/frontend/src/components/ModelSettingsDialog.test.tsx`

- [ ] 写测试：工作区去重、归档筛选、删除二次确认、模型组选择和掩码显示。
- [ ] 运行 Vitest 目标测试确认失败。
- [ ] 实现精确 API 解码、侧栏菜单、模型设置抽屉、提交时携带组 ID和工作区切换刷新。
- [ ] 运行目标测试确认通过，再运行完整 `npm run test:run -- --pool=threads` 和 `npm run build`。

### Task 6: EDD、Docker 和提交

**Files:**
- Create: `agent-eval/src/test/java/com/agent/eval/ConversationWorkspaceModelPoolEddTest.java`
- Modify: `README.md`

- [ ] 使用固定 `agent4j-test` 工作区执行三轮真实会话，验证归档、删除和模型组选择的状态证据。
- [ ] 运行 `mvn -pl agent-eval -am test`，确认 EDD 报告包含任务 ID、状态和组选择字段。
- [ ] 运行 `mvn clean package '-DskipTests'`。
- [ ] 运行 `docker compose -f docker-compose.local.yml --env-file .env up -d --build`，检查健康状态和 API。
- [ ] 运行 `git diff --check`、`git status --short`，按模块提交 Conventional Commits。
