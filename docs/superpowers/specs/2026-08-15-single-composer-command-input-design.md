# 单输入框命令与对话体验设计

## 背景

当前 `ConversationComposer` 在选择 Slash Command 或受治理 CLI 后清空主消息框，再渲染第二个参数输入框。该流程把命令选择、参数填写和普通提示词拆成两个编辑上下文，与 Claude Code 的同框交互不一致，也会丢失用户已经输入的上下文。

## 目标

- Composer 始终只有一个可编辑文本框。
- 选择命令只把命令文本插入当前输入，不切换到第二个编辑器。
- 工作流命令和 Skill 的后续文本作为同一条消息的参数/提示词。
- 系统指令继续本地执行，不调用模型；工作流命令继续进入既有 Agent 链路；受治理 CLI 继续使用审批、Terminal 和 Trace。
- 保留中文输入法、键盘导航、粘贴和 Shift+Enter 换行行为。

## 官方语义依据

Claude Code 官方命令文档说明：命令只在消息开头识别，命令名后的文本是参数；Skills 可在消息开头连续串联，最多六个；部分状态命令在响应期间即时运行。Agent4J 采用这一语义，不把普通句子中间的 `/` 猜测为命令。

## 交互设计

### 输入状态

`composerText` 是唯一输入真源。删除 `selectedCommand`、`selectedSlashCommand`、`argumentsInput` 和 `slashArgumentsInput` 的双编辑状态。命令选择状态只保存补全高亮和服务端定义，不保存第二份可编辑文本。

### 补全与插入

- 输入框为空时输入 `/`，加载实时 Slash Command 与工作区 CLI 目录。
- 过滤结果同时展示名称、描述、执行通道和风险。
- Enter 或 Tab 选择后，将精确命令文本插入当前文本开头，并保留输入框焦点。
- 例如选择 `plan` 后文本变为 `/plan `；选择受治理 CLI 后文本变为 `/cli test.maven `。
- Escape 关闭补全菜单但保留文本；上下键移动高亮。

### 提交分流

前端提交原始 `composerText`：

1. 普通文本：调用现有会话提交接口。
2. `SYSTEM_DIRECTIVE`：调用 Slash Command dispatcher，本地返回结构化结果，不产生 LLM 请求。
3. `WORKFLOW_SKILL`：调用 dispatcher，保留命令后的全部文本作为模板参数/任务提示词。
4. `/cli <commandName> [arguments...]`：解析为受治理 CLI Run，沿用现有工作区权限、风险审批、Terminal 日志和 Trace。

命令解析失败时只显示稳定错误，不回退为普通 LLM 消息，避免把拼写错误的控制指令发送给模型。

### 辅助状态

风险、审批状态、超时和模型组放在同一 Composer 的底部状态栏或紧凑 Popover 中，不再创建新的 textarea。发送后命令上下文进入现有 Run 卡片和会话消息流。

## 组件边界

- `ConversationComposer`：输入、补全、键盘行为和提交编排。
- `composerCommandParser`：纯函数，解析开头命令、Skill 链和 `/cli` 参数。
- `commandApi`/`cliApi`：只负责 HTTP，不在组件中拼接协议细节。
- 后端 dispatcher：继续作为最终权限和通道路由真源。

## 错误处理

- 未知命令、缺少 `/cli` 子命令、非法参数和权限拒绝均在输入区显示稳定错误。
- 网络失败保留原始输入，用户修复或重试时不丢文本。
- 提交期间禁用发送，但允许查看完整输入；不清空文本直到服务确认接受。

## 验收标准

- 页面同时只能找到一个聊天编辑框。
- 选择命令后编辑框仍有焦点，文本包含命令前缀且可继续输入提示词。
- `/plan 修复登录` 以原始字符串进入工作流 dispatcher。
- `/write-tests /review 修复登录` 保留 Skill 链和尾部参数。
- `/status` 等系统指令不调用模型。
- `/cli test.maven` 创建真实 governed-cli Run，状态、审批、Terminal 和 Trace 与现有链路一致。
- 全量前端 Vitest、CLI 组件测试和后端构建通过。
