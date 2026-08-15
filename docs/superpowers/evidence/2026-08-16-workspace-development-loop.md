# 工作区开发闭环验收记录

## 已验证

- `mvn -pl agent-core -Dtest=CommandEngineEggTest,GovernedMultiAgentEggTest test`：6/6 通过。
- 前端 `npm run test:run`：28 个测试文件、152 个测试通过。
- 前端 `npm run build`：Vite 构建退出码 0。
- 工作区后端聚焦测试：项目创建、文件读写、SHA 冲突和 REST 控制器共 7/7 通过。
- Maven `agent-web -am package -DskipTests`：Java 21 Toolchain 选择成功，Reactor 构建成功。
- PowerShell 空项目 EDD 脚本语法解析错误数为 0。

## 真实模型 EDD

报告文件：`agent-eval/target/edd/llm-edd-1786817326243.json`

- transport：`live-openai-compatible`
- modelCallAttempts：9
- 场景数：6
- 通过：3
- 失败：3

失败场景均为聊天/快速分类路由，主模型和回退模型都使用配置的 `gpt-5.6-luna`，网关返回 HTTP 500。代码路由的 3 个场景通过。该结果证明测试确实发起了真实模型请求，但不证明聊天路由已通过验收。

## 真实 HTTP 空项目 EDD

执行脚本：`.agent4j/acceptance/run-workspace-development-loop.ps1`

- workspaceId：`80e2f0f6-8946-4364-aa33-efaf65042bcd`
- conversationId：`70a2a5f5-3d1f-4c03-ba39-4a883bcf7884`
- turnId：`69c38d32-4786-471a-b32c-879a479d3b38`
- runId：`25561a97-fea2-40a6-a07a-46279a6267a4`
- 实际模型：Planner/Coder/Reviewer 均为 `gpt-5.4-mini`。
- 初始测试：失败，`sqrt=4.0` 与期望 `sqrt=2.00` 不一致。
- Agent 修改：`src/main/java/demo/NumberLabel.java`。
- Ops：`'mvn' 'test'`，退出码 `0`，`BUILD SUCCESS`，`Failures: 0`。
- Reviewer：`reviewer.approved=true`。
- 文件 API 复读：SHA-256 `7dc5e8864d6d45395ef698576db1c26a5ff2bf37ee7a7ffe53ab4c925da7be74`，内容包含 `Math.sqrt(value)` 和 `String.format("sqrt=%.2f", ...)`。
- Trace：包含 `planner`、`coder`、`ops`、`reviewer`。
- 终端 SSE：包含 `BUILD SUCCESS` 和 `Failures: 0`。

SSE 是持续连接，`curl.exe` 在读取到证据后以 HTTP 客户端超时退出，但已保存并校验完整节点和终端内容。

## 尚未完成

- 完整 `mvn -pl agent-web -am test` 被既有 `agent-sandbox` Windows PTY 临时目录文件锁测试阻断，错误为 `PtyCommandExecutorTest.releasesWorkingDirectoryBeforeReturningFromTimeout`。
