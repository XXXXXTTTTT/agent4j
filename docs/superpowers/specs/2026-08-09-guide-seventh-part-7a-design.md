# 第七篇 7A：CLI Agent 生产链治理与自愈设计

## 背景

第 21 章要求把 CLI Agent 的代码修改、知识检索、命令审批和失败自愈串成可审计的
`Coder -> Ops -> Reviewer -> Coder` 闭环。项目已经有 `DefaultToolRegistry`、`CliCommandCatalog`、
`ProjectKnowledgeCompiler`、RAG token 预算和 Coder/Ops 节点，但生产图仍让 Coder 直接调用
`AstService`，并让模型返回裸 Bash 字符串，命令目录与审批端口没有进入这条链。

## 目标

1. Coder 只能通过受治理的 `code.apply-diff` 工具应用 Unified Diff，工具结果保留完整错误栈。
2. Coder 模型输出精确的 `commandName` 与 `commandArguments`，不再接受裸 `command` 字符串作为
   生产协议；状态继续保留 `ops.command` 作为授权后实际渲染命令，兼容 Web 终端展示。
3. Ops 使用 `CliCommandCatalog` 的精确命令定义、参数校验、workspace 边界和风险决策；需要审批的
   命令在节点执行前由 `CliApprovalInterruptPolicy` 产生 HITL 中断，批准后才创建终端 Future。
4. Coder Prompt 注入 Planner 已加载的项目知识上下文、指纹、来源数和降级证据；不重复读取工作区，
   继续使用 Planner 已确定的知识/RAG token 预算。
5. 增加真实临时 Git 工作区 EDD：真实 AST/JGit Diff、真实 Git Bash/PTY 命令、命令失败后的修复循环
   和完整 Trace/状态断言。

## 非目标

- 不允许模型提供 executable、完整 shell 语句、管道、重定向或任意工具名称。
- 不在本里程碑新增 MCP 工具、Playwright 动作协议或修改 `StateGraph.execute()`。
- 不改变已有公开状态键的大小写和结构；新增键采用明确的 `coder.*`、`ops.*` 前缀。
- 不把审批绕过作为 Ops 自己的权限来源；只有生产图中的精确中断策略允许恢复执行。

## 精确状态协议

新增键：

- `coder.commandName`：`CliCommandCatalog` 中已注册的精确命令名。
- `coder.commandArguments`：JSON 数组字符串，元素按原样作为命令参数。
- `coder.knowledgeFingerprint`：本轮 Planner 知识指纹。
- `coder.knowledgeSources`：本轮 Planner 知识来源数。
- `ops.commandName`：传给 CLI 意图的精确命令名。
- `ops.commandArguments`：传给 CLI 意图的 JSON 数组字符串。
- `ops.commandSha256`：授权后渲染命令的 SHA-256。
- `ops.authorizationDecision`：`ALLOWED`、`APPROVAL_REQUIRED` 或 `DENIED`。
- `ops.authorizationReason`：目录授权的原样原因。

现有 `ops.command` 仍表示实际渲染的 Bash 命令，只有授权决策产生后才写入；原始模型输出不写入
该键。`ops.exitCode`、`ops.stdout`、`ops.stderr`、`ops.timedOut`、`ops.error` 保持不变。

## 核心组件

### `CodePatchTool`

包：`com.agent.core.tool.builtin`

以 `ToolDefinition` 声明精确名称 `code.apply-diff`、`CODE_WRITE` 能力、低风险和有界超时。
输入 Schema 只允许 `unifiedDiff` 字符串；Handler 使用 `ToolInvocationContext.workspaceRoot()`
调用 `AstService.applyDiff`，返回 `updatedFiles` 数组和统一相对路径。Workspace 路径不进入模型参数，
由上下文绑定，防止参数越界。

### `CoderNode`

生产构造器注入 `ToolRegistry`。生成 JSON 时严格要求 `summary`、`unifiedDiff`、`commandName`、
`commandArguments` 四个字段，并拒绝未知字段。通过 `HarnessToolExecutor` 执行 `code.apply-diff`；
非成功 `ToolResult` 转为 `coder.error` 完整堆栈。请求 Prompt 同时包含 Planner 的知识上下文和
失败节点输出，修复循环保持现有 `coder.attempt` 与 Reviewer 路由。

### `CliApprovalInterruptPolicy` 与 `OpsNode`

策略在 `ops` 节点前从两个结构化状态键解析 `CliCommandIntent`，调用 `CliCommandCatalog.authorize`
但不执行终端。`APPROVAL_REQUIRED` 返回 `InterruptRequest`，details 只公开命令名、参数、渲染命令、
风险、指纹和原因；`DENIED` 直接返回异常，`ALLOWED` 不中断。恢复时 `StateGraph` 的既有一次性
`bypassInterruptAtStart` 作为已批准恢复信号，Ops 使用同一个授权计划创建终端 Future，并将计划和
结果写入状态；拒绝路线不会调用终端。

生产命令目录只声明项目需要的精确只读测试命令，定义由 Spring 构造器注入，模型不能扩展目录。

## 失败自愈 EDD

`CliAgentWorkflowEddTest` 使用 `@TempDir` 创建真实 Git 仓库和当前机器精确 Bash 路径，模型响应由
MockRestServiceServer 提供两次确定 JSON：第一次 Diff 成功但测试命令返回非零，第二次读取
`ops.stderr` 和 Reviewer 反馈后生成修复 Diff。断言：

- Diff 只更新目标文件，真实 Git 工作区内容与 `updatedFiles` 一致；
- 命令由目录渲染，PTY 日志保留，命令参数注入被拒绝；
- Trace 顺序包含 `planner/coder/ops/reviewer/coder/ops/reviewer`；
- `coder.attempt` 从 1 增至 2，最终 `reviewer.approved=true`、无 `.error` 键；
- EDD JSON 报告字段固定为 `taskId/status/attempts/updatedFiles/commandSha256/terminalCalls/passed`。

## 错误与安全边界

- Coder JSON 解析、工具 Schema、工具授权、Diff 冲突和知识加载异常均写入完整 `coder.error`。
- Ops 命令不存在、参数非法、workspace 越界、能力不足和审批拒绝均在终端创建前失败并写入
  `ops.error` 或中断状态。
- 任何工具、CLI 或知识 Provider 的审计/Trace 发布失败都不能伪造成功状态。

## 验证门禁

1. Core Coder/CLI 单元测试和生产图构造测试。
2. `CliAgentWorkflowEddTest` 真实临时 Git + Git Bash/PTY。
3. `mvn -pl agent-core,agent-web,agent-eval -am test`，Docker/外部模型按现有 assumption/显式开关。
4. `mvn clean package -DskipTests -Dfrontend.skip=true`。
