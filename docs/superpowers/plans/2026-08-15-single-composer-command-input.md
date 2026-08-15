# 单输入框命令与对话体验实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Agent4J 的命令、Skill 和普通提示词统一到一个 Composer 输入框，并保持既有治理执行链。

**Architecture:** 前端以原始文本作为唯一状态，纯函数解析消息开头的 Slash Command/Skill 链和 `/cli` 命令；后端 dispatcher 继续决定本地系统指令、工作流 Skill 或 governed CLI 的最终通道。命令选择只做文本插入，不再创建第二个参数编辑器。

**Tech Stack:** React 18、TypeScript、Vitest、Testing Library、现有 Spring WebFlux Command/CLI API。

---

### Task 1: 纯函数命令解析

**Files:**
- Create: `agent-web/src/main/frontend/src/components/composerCommandParser.ts`
- Test: `agent-web/src/main/frontend/src/components/composerCommandParser.test.ts`

- [x] 写失败测试：解析普通消息、单个 Slash Command、带引号参数、`/cli` 命令和非法 `/cli`。
- [x] 运行 `npm run test:run -- src/components/composerCommandParser.test.ts`，确认因解析器不存在而失败。
- [x] 实现不依赖 React 的精确解析函数；只识别消息开头，不猜测句中 `/`。
- [x] 重新运行该测试并确认通过。

### Task 2: Composer 单输入框状态迁移

**Files:**
- Modify: `agent-web/src/main/frontend/src/components/ConversationComposer.tsx`
- Modify: `agent-web/src/main/frontend/src/components/ConversationComposer.test.tsx`
- Modify: `agent-web/src/main/frontend/src/styles.css`

- [x] 先新增失败组件测试：选择 Slash Command 后只有一个 textbox、焦点仍在主输入框、命令文本被插入且可继续输入；选择 `/cli` 后同样成立。
- [x] 运行 CLI/Composer 定向 Vitest，确认旧双框行为无法满足新断言。
- [x] 删除双编辑状态，改为 `composerText` 和命令补全状态；选择命令只插入文本并恢复焦点。
- [x] 将 Enter/Tab/Escape/Arrow 行为绑定到同一输入框，保留 Shift+Enter 换行。
- [x] 将风险、超时和模型组移动到紧凑状态栏，不渲染第二个 textarea。
- [x] 更新普通提交、Slash dispatcher 和 governed CLI 提交分流，失败时保留原始文本。
- [x] 运行 `npm run test:run -- src/components/ConversationComposer.test.tsx src/components/WorkbenchGovernedCliFlow.test.tsx`。

### Task 3: 复用协议并回归验证

**Files:**
- Reuse: `agent-web/src/main/frontend/src/api/commandApi.ts`
- Reuse: `agent-web/src/main/frontend/src/api/cliApi.ts`
- Test: `agent-web/src/main/frontend/src/api/commandApi.test.ts`
- Test: `agent-web/src/main/frontend/src/api/cliApi.test.ts`

- [x] 保持既有 decoder 与 HTTP 契约；Slash 原始文本继续提交 dispatcher，未知命令不会走普通会话接口。
- [x] 确认 `/cli` 参数仍生成现有 `commandName/arguments/timeoutSeconds` 请求，不改变审批协议。
- [x] 运行 API/Composer 全量前端回归测试。

### Task 4: 验证与交付

- [x] 运行前端全量 `npm run test:run`。
- [x] 运行 `npm run build`。
- [x] 运行 `mvn -pl agent-web -am package -DskipTests` 并重建 Docker Compose。
- [x] 使用浏览器检查 Composer 只有一个编辑框，验证选择 `/plan`、`/cli test.cat` 后可继续输入。
- [x] 使用真实工作区执行 `/cli test.cat pom.xml`，确认 Run、Terminal、Trace 和退出码保持正确。
- [x] 运行 `git diff --check`，只提交本任务文件，不触碰用户已有改动。
- [x] 提交 `feat(frontend): unify command and chat composer`。
