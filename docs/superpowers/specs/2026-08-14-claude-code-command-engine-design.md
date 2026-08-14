# Agent4J Claude Code 风格指令引擎设计

## 目标

为 Agent4J 建立一套可扩展的 Slash Command 分发引擎，参考 Anthropic 官方 Claude Code CLI 与命令文档，但保留 Agent4J 现有的会话、Graph、Tool、权限、Checkpoint 和审计边界。系统控制指令在本地完成，不产生模型请求；工作流指令作为可注册模板进入既有 Agent graph。

## 调研依据

调研日期：2026-08-14（北京时间）。

- 官方命令参考：[https://code.claude.com/docs/en/commands](https://code.claude.com/docs/en/commands)
- 官方 CLI 参考：[https://code.claude.com/docs/en/cli-reference](https://code.claude.com/docs/en/cli-reference)
- 官方 Skills 参考：[https://code.claude.com/docs/en/skills](https://code.claude.com/docs/en/skills)
- npm 元数据：`@anthropic-ai/claude-code@2.1.232`，包入口为原生 `bin/claude.exe`，不存在可直接复用的 JavaScript 命令注册表。

官方命令页当前列出的命令集合包含 `/add-dir`、`/agents`、`/background`、`/batch`、`/branch`、`/btw`、`/checkpoint`、`/clear`、`/code-review`、`/compact`、`/config`、`/context`、`/cost`、`/debug`、`/doctor`、`/effort`、`/export`、`/feedback`、`/help`、`/hooks`、`/init`、`/mcp`、`/memory`、`/model`、`/permissions`、`/plan`、`/plugin`、`/review`、`/rewind`、`/security-review`、`/skills`、`/status`、`/tasks`、`/usage`、`/verify`、`/voice` 等。命令页同时说明 `/review` 是 `/code-review` 的别名，`/cost` 是 `/usage` 的别名，且 `.claude/commands/*.md` 与 `.claude/skills/*/SKILL.md` 共用可调用命令入口。

CLI 参考页确认的启动参数按能力分组如下：

| 能力 | 官方参数 |
| --- | --- |
| 非交互与输出 | `-p`/`--print`、`--output-format`、`--input-format`、`--include-partial-messages`、`--verbose` |
| 会话 | `-c`/`--continue`、`--resume`、`--fork-session`、`--session-id`、`--no-session-persistence` |
| 模型与预算 | `--model`、`--fallback-model`、`--effort`、`--max-budget-usd`、`--max-turns` |
| 工具与权限 | `--tools`、`--allowed-tools`、`--disallowed-tools`、`--permission-mode`、`--permission-prompt-tool`、`--dangerously-skip-permissions` |
| MCP 与扩展 | `--mcp-config`、`--strict-mcp-config`、`--plugin-dir`、`--plugin-url`、`--disable-slash-commands` |
| 提示词与目录 | `--system-prompt`、`--system-prompt-file`、`--append-system-prompt`、`--append-system-prompt-file`、`--add-dir`、`--cwd` |
| 调试与维护 | `--debug`、`--debug-file`、`--version`、`--help`、`--settings`、`--setting-sources` |

## 设计决策

### 双通道路由

统一入口 `CommandDispatcher` 接收原始输入、会话标识、工作区标识和调用者身份，先由 `SlashCommandParser` 解析名称与参数，再从 `CommandRegistry` 取得精确命令定义。

命令定义具有稳定名称、显示名称、描述、参数 schema、来源、通道和权限等级。通道只有两个值：

1. `SYSTEM_DIRECTIVE`：调用本地 `CommandHandler`，返回结构化 `CommandResult`，不得构造 `ConversationService.submitTurn` 或调用 `LlmClient`。
2. `WORKFLOW_SKILL`：先执行权限检查，再渲染 Markdown 模板，创建带模板变量的 AgentState，调用既有 `ConversationService.submitTurn`，并保留原始命令和渲染摘要用于审计。

未知命令、语法错误、参数校验错误和权限拒绝都在 Dispatcher 层结束，不发送给 LLM。

### 核心接口

新增 `com.agent.core.command` 包，职责保持单一：

- `CommandDefinition`：不可变命令元数据与参数 schema。
- `CommandChannel`、`CommandSource`、`CommandPermission`：受限枚举/值对象。
- `CommandInvocation`：解析后的名称、参数、会话和工作区上下文。
- `CommandHandler`：本地控制或工作流执行端口。
- `CommandRegistry`：注册、批量注册、精确查找、前缀搜索和修订快照。
- `CommandDispatcher`：解析、查找、授权、执行、审计生命周期。
- `CommandResult`：本地输出、工作流转发信息、错误和审批状态的结构化结果。

核心不依赖 Spring。Spring 只负责把内置 Handler、Markdown Loader 和 Web Controller 装配到注册表。

### 内置系统指令

第一期实现下列系统指令，名称和别名均注册到同一 Registry，不在 Dispatcher 中写分支：

| 指令 | 行为 | 模型请求 |
| --- | --- | --- |
| `/help [query]` | 返回 Registry 当前快照中的命令及参数说明 | 否 |
| `/context` | 使用已有 TokenEstimator 统计当前会话、工作流和工具上下文 | 否 |
| `/compact [focus]` | 使用已有 ContextSummaryProvider 生成摘要并写入新 Checkpoint/会话上下文 | 否；摘要服务若需要模型则由显式工作流 Handler 承担，系统指令默认只执行本地压缩 |
| `/clear` | 创建同工作区的新会话，保留项目文件和记忆，旧会话保持可归档访问 | 否 |
| `/permissions` | 读取/更新当前工作区命令权限策略；变更需要管理员权限 | 否 |
| `/cost` | 展示本会话已记录的模型调用与 Token/费用统计；无记录时返回零值 | 否 |
| `/rewind <checkpoint>` | 在权限检查后将会话/运行恢复到精确 Checkpoint，拒绝不存在或跨工作区版本 | 否 |

`/review` 注册为 `WORKFLOW_SKILL`，并作为 `/code-review` 的别名；`/plan`、`/security-review`、`/tasks` 采用同一模板 Handler 扩展点。第一期至少提供 `/plan` 和 `/review` 两个内置模板，模板正文在资源文件中，不在 Dispatcher 中拼接。

### Markdown 自定义命令

`MarkdownCommandLoader` 读取两个显式配置来源：

- 工作区根目录下的 `.agent/commands/*.md`。
- 配置属性 `agent.commands.global-directory` 指定的全局目录下的 `*.md`。

加载顺序固定为：内置命令 → 全局命令 → 工作区命令。名称冲突时更具体的工作区命令覆盖全局命令，全局命令覆盖内置命令；同一来源内重复名称直接拒绝整批加载并记录错误。Loader 只允许解析普通文件、限制文件大小、拒绝符号链接逃逸和路径遍历。

Markdown 文件使用 YAML front matter，必填字段为 `name`、`description`、`channel`，可选字段为 `aliases`、`arguments`、`permission`。正文是模板；只支持明确的 `${argumentName}` 和 `${workspacePath}` 等白名单变量，未知变量在加载时拒绝。工作流正文只作为模板输入，不允许 Markdown 直接执行 Shell 或绕过 ToolRegistry。

### 权限、Checkpoint 与审计

Dispatcher 在 Handler 前调用 `CommandAuthorizationPolicy`。策略以工作区为边界，至少区分 `VIEWER`、`OPERATOR`、`ADMIN`，并把命令风险、来源和是否改变文件/会话作为决策输入。所有拒绝、执行、覆盖、回滚和工作流转发都写入现有会话审计 Sink，并带命令名、来源、参数脱敏摘要、工作区和会话标识。

`/rewind` 只接受 `RunCheckpoint` 的精确 `runId + version` 或同一会话暴露的 checkpoint 标识；恢复前验证调用者、工作区和版本链，恢复后产生新的审计事件，不删除历史证据。

### Web 与前端

新增工作区命令目录 API，返回 Registry 的实时快照和修订号；会话 Composer 输入 `/` 时查询该 API，按名称、描述和来源筛选，不能维护一份前端硬编码命令数组。选择工作流命令后显示参数表单和权限提示；选择系统指令后直接提交本地命令请求并展示结构化结果。现有受治理 CLI 菜单继续作为独立能力，不与 Slash Command 命名空间混淆。

## 错误处理

- 原始输入不是 `/` 开头：继续走普通会话提交路径。
- `/` 后为空：返回当前 Registry 快照，不发送 LLM 请求。
- 名称不匹配：返回可用命令的有限前缀建议，不猜测并执行其他命令。
- 参数不合法、front matter 不合法、名称冲突、权限不足、跨工作区 Checkpoint：返回稳定错误码和中文消息。
- Handler 异常：由 Dispatcher 记录脱敏堆栈，系统指令返回失败结果，工作流指令不创建半成品轮次。

## 验证标准

必须先写失败测试再实现。核心测试覆盖：

1. Registry 注册、别名、修订快照、重复名称和来源覆盖规则。
2. Parser 参数边界、空输入、未知命令、引号/空白和非法参数拒绝。
3. Dispatcher 对所有系统指令不调用 `ConversationService`/`LlmClient`，并产生正确 `CommandResult`。
4. Workflow Handler 能把渲染模板送入既有 Graph，保留命令审计元数据。
5. Markdown Loader 的工作区/全局隔离、front matter、变量白名单、大小限制、符号链接逃逸和冲突处理。
6. 权限拒绝、管理员策略变更、Checkpoint 精确回滚和跨工作区拒绝。
7. Web API 返回 Registry 实时内容；前端 `/` 菜单渲染服务端命令，未知命令不会发起普通 LLM 轮次。
8. EGG/EDD 运行中记录真实 HTTP 请求计数，确认系统指令计数为零、工作流命令计数大于零。

