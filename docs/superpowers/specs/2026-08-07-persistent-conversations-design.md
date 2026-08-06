# Agent4J 持久化会话、工作区与权限边界设计

## 目标与根因

当前 Web 工作台每次提交任务都会调用 `POST /api/runs/code-agent` 创建新的 `runId`。
数据库只保存 `agent_runs` 和 `agent_checkpoints`，没有会话、轮次、工作区成员关系；
`CodeAgentStartRequest` 只传递本轮 `task`，`PlannerNode` 的快速问答请求也只包含本轮文本。
因此页面刷新、服务重启或再次提交后均无法恢复上一轮消息，模型也收不到历史上下文。

本设计新增以 PostgreSQL 为唯一权威源的用户、工作区、会话和轮次模型。一个会话绑定一个
工作区和创建用户；一个会话包含多个轮次；每个轮次仍创建一个独立 Run。这样既保留现有
Run Checkpoint、HITL、Trace 和终态语义，又提供与 Codex/Claude Code 相同类别的持久化对话入口。

教程 `fuzhengwei/ai-agent-guide` 的记忆章节将短期记忆定义为当前对话上下文，将长期记忆定义为
跨会话的偏好和经验，并明确反对把每轮完整聊天日志全部写入向量记忆。本设计遵循该边界：
Conversation 保存完整事实历史，Context Assembler 组装有界的当前上下文，现有
`rag_memories` 继续只承载 `USER_PREFERENCE`、`ARCHITECTURE_RULE` 和 `BAD_CASE`。

## 方案选择

### 方案 A：在同一 Run 上追加用户消息

该方案复用 `runId`，但现有 Run 到达 `COMPLETED`、`FAILED` 或 `REJECTED` 后就是不可变终态。
重新打开终态会破坏 Checkpoint 的版本语义、Trace 审计和 HITL 恢复规则，因此不采用。

### 方案 B：浏览器本地保存消息

该方案改动小，但 `localStorage` 不是权威数据源，无法支持服务重启、多浏览器、权限隔离或后台
上下文组装，也无法让模型可靠获得历史，因此不采用。

### 方案 C：Conversation 聚合，每轮独立 Run

Conversation 管理对话身份，Turn 管理用户输入、Run 归属和助手终态输出。每轮使用新 Run，
并在启动前从 Conversation 读取历史消息。该方案与现有架构兼容，且能独立扩展身份认证、
工作区授权、上下文压缩和长期记忆，因此确定采用。

## 数据模型

Flyway 新增 `V2__create_conversation_tables.sql`，创建以下精确表。

### `agent_users`

- `user_id varchar(255)`：主键，大小写敏感，不做归一化。
- `display_name varchar(255)`：显示名称。
- `enabled boolean`：禁用用户不能访问工作区或会话。
- `created_at timestamptz`、`updated_at timestamptz`。

### `agent_workspaces`

- `workspace_id uuid`：主键。
- `owner_user_id varchar(255)`：引用 `agent_users.user_id`。
- `display_name varchar(255)`。
- `workspace_path text`：服务端解析后的真实目录绝对路径。
- `repository_id varchar(255)`：长期记忆与 RAG 的精确仓库范围。
- `created_at timestamptz`、`updated_at timestamptz`。
- `(owner_user_id, workspace_path)` 唯一。

### `agent_workspace_members`

- `workspace_id uuid`、`user_id varchar(255)`：联合主键并分别引用工作区和用户。
- `permission varchar(16)`：精确枚举 `VIEWER`、`OPERATOR`、`OWNER`。
- `created_at timestamptz`、`updated_at timestamptz`。

`VIEWER` 可读取工作区和会话；`OPERATOR` 可创建会话和提交轮次；`OWNER` 额外拥有后续成员管理
扩展点。工作区创建者必须同时拥有一条 `OWNER` 成员记录。

### `agent_conversations`

- `conversation_id uuid`：主键。
- `workspace_id uuid`：引用 `agent_workspaces.workspace_id`。
- `created_by varchar(255)`：引用 `agent_users.user_id`。
- `title varchar(255)`：首轮用户文本的前 80 个 Unicode code point；空白折叠为单个空格。
- `status varchar(16)`：精确枚举 `ACTIVE`、`ARCHIVED`。
- `created_at timestamptz`、`updated_at timestamptz`。

### `agent_conversation_turns`

- `turn_id uuid`：主键。
- `conversation_id uuid`：引用 `agent_conversations.conversation_id`。
- `turn_index bigint`：从 1 开始，和 `conversation_id` 组成唯一键。
- `user_content text`：本轮用户原文。
- `assistant_content text`：终态回答，执行中允许为空。
- `run_id uuid`：引用 `agent_runs.run_id`，准备阶段允许为空，非空时全表唯一。
- `status varchar(16)`：精确枚举 `PENDING`、`RUNNING`、`COMPLETED`、`FAILED`。
- `error text`：完整失败堆栈，非失败状态为空。
- `created_at timestamptz`、`completed_at timestamptz`。

提交轮次时锁定 Conversation 行，若已有 `PENDING` 或 `RUNNING` 轮次则返回冲突，禁止同一会话
并行修改同一工作区。不同会话之间可以并发执行。

## 身份与权限边界

`com.agent.web.identity.Actor` 是不可变 record，字段为 `userId` 和 `displayName`。
`ActorResolver` 只负责解析当前调用者。当前单机部署使用 `ConfiguredActorResolver`，从现有精确配置
`agent.production.user-id` 获取用户标识；该解析器是以后接入 Spring Security、OIDC 或网关 JWT 的
替换边界，业务服务不得直接读取请求中的 `userId`。

`WorkspaceAccessService` 对每次工作区、会话和轮次操作执行精确成员查询。客户端不能再通过
`CodeAgentStartRequest.userId` 冒充其他用户。底层兼容接口 `POST /api/runs/code-agent` 保留，但其
用户标识由 `ActorResolver` 决定。

应用启动时 `WorkspaceBootstrap` 幂等创建配置用户，以及绑定
`agent.production.workspace`/`agent.production.repository-id` 的默认工作区和 `OWNER` 成员关系。
工作区路径仍必须经过现有 `toRealPath()`、目录存在性和配置根目录边界校验。

## 核心领域与持久化端口

`agent-core` 新增框架无关会话上下文端口：

```java
public interface ConversationContextProvider {
    ConversationContext load(UUID conversationId, String userId, int maxTurns, int maxCharacters);
}
```

`ConversationContext` 是不可变 record，字段为 `List<ChatMessage> messages`、`int turnCount` 和
`boolean truncated`。Provider 必须按 `turn_index` 升序返回完整的用户/助手消息对，不返回
`PENDING`、`RUNNING` 或 `FAILED` 的不完整助手消息。

`agent-web` 的 `JdbcConversationRepository` 负责事务、行锁、精确权限范围查询和数据映射；
`JdbcConversationContextProvider` 实现 core 端口。所有 SQL 使用绑定参数。数据库异常保留 cause，
不会退化为空历史。

首期上下文预算固定为最近 20 个已完成轮次和最多 32,000 个 Java 字符。超限时从最旧完整轮次
开始移除，用户/助手消息始终成对保留。完整历史仍保存在 PostgreSQL，不因 Prompt 截断而删除。
该确定性策略不增加额外模型调用延迟；后续摘要压缩可以在 `ConversationContextProvider` 后面扩展，
不改变会话 API。

## 多轮运行数据流

前端提交 `POST /api/conversations/{conversationId}/turns` 后执行以下步骤：

1. `ActorResolver` 解析当前用户，`WorkspaceAccessService` 校验至少 `OPERATOR`。
2. 事务中锁定 Conversation，分配下一个 `turn_index` 并写入 `PENDING` Turn。
3. `ConversationContextProvider` 读取此前已完成轮次，不包含当前 Turn。
4. 构造 `AgentState`：历史写入 `messages`；当前输入写入精确键 `planner.task`；工作区写入
   `coder.workspacePath`；仓库和用户写入既有 `planner.repositoryId`、`planner.userId`；新增精确键
   `conversation.id` 和 `conversation.turnId`。
5. `AgentRunService.start("code-agent", state)` 创建独立 Run。Turn 随后更新为 `RUNNING` 并关联
   `run_id`；启动失败则更新为 `FAILED` 并保存完整堆栈。
6. `PlannerNode` 使用历史 `state.messages` 加本轮用户消息构造模型请求。快速问答响应和代码任务
   规划均把本轮用户消息及助手消息追加回不可变 `AgentState.messages`。
7. `ConversationRunProjector` 监听现有 Run 终态 Trace。`COMPLETED` 时按顺序解析
   `final_response`、`reviewer.feedback`、`reviewer.summary`、`planner.response`，写入
   `assistant_content`；`FAILED`/`REJECTED` 写入 Turn 状态和完整错误。
8. 前端通过现有 Run Trace 获得实时过程，通过 Conversation API 重新加载权威轮次。

如果终态事件投影失败，异常必须进入应用日志；读取 Conversation 时执行幂等对账：对仍为
`RUNNING` 且已有终态 Run 的 Turn 重新投影，避免进程在事件发布窗口崩溃造成永久缺口。

## REST 协议

新增以下精确端点，所有返回均按当前 Actor 过滤：

- `GET /api/me`：返回 `userId`、`displayName`。
- `GET /api/workspaces`：返回当前用户有成员关系的工作区和精确权限。
- `POST /api/workspaces`：创建工作区；请求字段为 `displayName`、`workspacePath`、`repositoryId`。
- `GET /api/workspaces/{workspaceId}/conversations?query=`：按 `updated_at` 降序列出会话；`query`
  只对 title 做大小写敏感包含查询，不做格式猜测。
- `POST /api/workspaces/{workspaceId}/conversations`：创建空会话；请求字段为空对象。
- `GET /api/conversations/{conversationId}`：返回会话元数据和有权访问的工作区摘要。
- `GET /api/conversations/{conversationId}/turns`：按 `turn_index` 升序返回全部轮次。
- `POST /api/conversations/{conversationId}/turns`：请求字段为 `content`、可选 `reviewerUrl`，
  返回已关联 Run 的 Turn。
- `POST /api/conversations/{conversationId}/archive`：将状态改为 `ARCHIVED`；归档会话禁止新轮次。

现有 Run 查询、审批、Trace 和终端端点保持不变。Conversation Turn 返回 `runId` 后，前端继续复用
这些协议展示执行详情。

## 前端工作台

桌面端增加固定宽度会话侧栏：顶部显示当前工作区选择器、新建会话按钮和会话搜索框；下方按
`updatedAt` 降序显示 title、最后更新时间和运行状态。移动端侧栏以抽屉展示，不遮挡输入框。

选择工作区后只显示该工作区的会话。选择会话时加载权威 Turn 列表，并通过 URL 查询参数
`conversationId` 保存选择；刷新页面从该参数恢复。不存在或无权访问的会话显示明确错误并清除参数。
不使用 `localStorage` 保存权威聊天内容。

输入框在没有会话时先创建会话，再提交首轮；已有会话直接追加轮次。提交成功后清空输入框、连接
返回的 `runId` Trace/终端，并在终态重新加载 Turn。Conversation 消息区以 Turn 为主数据，当前
Run 的 Planner/Coder/Ops/Reviewer 证据继续显示在执行检查器中，避免把内部证据重复伪装成聊天。

工作区选择器展示数据库登记的 `displayName` 和路径；创建工作区使用明确表单并由后端执行路径门禁。
客户端不自行遍历服务端文件系统。

## 错误与安全语义

- 禁用用户返回 403；无成员关系返回 404，避免泄露工作区或会话是否存在。
- `VIEWER` 提交轮次或创建会话返回 403。
- 归档会话提交返回 409；同一会话已有活动轮次时返回 409。
- 用户输入、标题、消息内容和路径均不写入日志正文；日志只记录精确 ID、状态与耗时。
- Conversation Context 中的历史用户/助手文本是不可信数据，只作为 ChatMessage 发送，不能拼入
  System Instruction。
- 工作区路径必须是配置根目录内的现有真实目录，禁止相对逃逸和符号链接逃逸。
- 所有异常保留 cause；Turn 与 Run 的失败状态保存完整堆栈供审计。

## 测试与验收门禁

- Repository 集成测试：真实 PostgreSQL/Flyway、用户 bootstrap、权限隔离、行锁并发、排序、搜索、
  归档、Turn 状态和终态幂等投影。
- Core 单元测试：上下文预算、完整消息对、`PlannerNode` 快速问答和代码规划均携带历史且不重复本轮。
- Controller 测试：Actor 不可由请求覆盖，404/403/409 语义和未知 JSON 字段门禁。
- 前端测试：工作区切换、会话搜索/创建/恢复、同会话连续提交、刷新加载、终态重新加载和移动端抽屉。
- 端到端黑盒：使用本地 Compose，第一轮告诉 Agent 一个临时事实，第二轮追问该事实；刷新浏览器和
  重启 `agent-web` 后仍能看到历史并得到基于同一会话上下文的回答。
- 完成前执行 JDK 21 全量 `mvn clean verify`、前端测试与构建、`git diff --check`，检查 Docker 残留，
  更新 `README.md` 和 `docs/ENGINEERING_PITFALLS.md`，并按 Conventional Commits 原子提交。
