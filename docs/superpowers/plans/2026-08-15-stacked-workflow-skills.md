# 串联工作流 Skill 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将连续工作流 Skill 合并为一次受权限控制的 Agent 工作流提交。

**Architecture:** `WorkflowCommandHandler` 暴露通用的提示词渲染端口，`CommandDispatcher` 识别最多六个连续工作流定义，预校验全部定义后拼接模板并调用同一 bridge 一次。

**Tech Stack:** Java 21、JUnit 5、AssertJ、现有 Command Registry 与 Conversation Workflow Bridge。

---

### Task 1: 工作流提示词渲染端口与链式分发

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/command/WorkflowPromptCommandHandler.java`
- Modify: `agent-core/src/main/java/com/agent/core/command/WorkflowCommandHandler.java`
- Modify: `agent-core/src/main/java/com/agent/core/command/CommandDispatcher.java`
- Test: `agent-core/src/test/java/com/agent/core/command/CommandDispatcherTest.java`

- [ ] 写失败测试：`/plan /review "修复登录"` 调用一次 bridge，两个模板按顺序拼接，两个调用都接收相同尾部参数。
- [ ] 运行 `mvn -pl agent-core -Dtest=CommandDispatcherTest test`，确认当前 Dispatcher 因参数数量非法而失败。
- [ ] 新增渲染接口，保持普通单命令 Handler 契约不变；在 Dispatcher 中预校验链内每个定义的参数与权限，再合并模板并单次提交。
- [ ] 运行核心测试确认通过。

### Task 2: 回归与交付

- [ ] 运行 `mvn -pl agent-core -Dtest=SlashCommandParserTest,CommandDispatcherTest,WorkflowCommandHandlerTest test`。
- [ ] 运行 `mvn -pl agent-web -am -Dtest=ConversationWorkflowCommandBridgeTest,CommandControllerTest -Dsurefire.failIfNoSpecifiedTests=false -Dfrontend.skip=true test`。
- [ ] 运行 `git diff --check`，只提交本任务文件。
- [ ] 提交 `feat(command): compose stacked workflow skills`。
