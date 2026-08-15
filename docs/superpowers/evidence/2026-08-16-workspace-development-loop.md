# 工作区开发闭环验收记录

## 已验证

- `mvn -pl agent-core -Dtest=CommandEngineEggTest,GovernedMultiAgentEggTest test`：6/6 通过。
- 前端工作区闭环聚焦测试：4 个测试文件、32 个测试通过（文件树懒加载、键盘导航、空项目创建、API 合约）。
- 前端 `npm run build`：Vite 构建退出码 0。
- 工作区后端聚焦测试：33 个测试通过（项目创建、导入、目录隔离、文件读写、SHA 冲突、REST 控制器和北京时间审计）。
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

- workspaceId：`665012ed-a094-42f5-b3ae-000b04c87ace`
- conversationId：`c881790e-7b77-4df3-b462-fd4711174502`
- turnId：`6169a028-6c47-448d-9401-8355cc6f173f`
- runId：`b32f0f72-b81a-4986-89f8-dd2a6c4f4cfb`
- 实际模型：Planner/Coder/Reviewer 均为 `gpt-5.4-mini`。
- 初始测试：失败，`sqrt=4.0` 与期望 `sqrt=2.00` 不一致。
- Agent 修改：`src/main/java/demo/NumberLabel.java`。
- Ops：`'mvn' 'test'`，退出码 `0`，`BUILD SUCCESS`，`Failures: 0`。
- Reviewer：`reviewer.approved=true`。
- 文件 API 复读：SHA-256 `7dc5e8864d6d45395ef698576db1c26a5ff2bf37ee7a7ffe53ab4c925da7be74`，内容包含 `Math.sqrt(value)` 和 `String.format("sqrt=%.2f", ...)`。
- 同一真实工作区的文件树：根目录列出 `src`，`src/main/java/demo` 列出 `NumberLabel.java`；读取接口返回非空 SHA-256；使用过期 SHA 写入返回 HTTP 409。
- Trace：包含 `planner`、`coder`、`ops`、`reviewer`。
- 终端 SSE：包含 `BUILD SUCCESS` 和 `Failures: 0`。

SSE 是持续连接，`curl.exe` 在读取到证据后以 HTTP 客户端超时退出，但已保存并校验完整节点和终端内容。

本次容器重建后 readiness：HTTP 200，`{"status":"UP"}`；`agent4j-web-local` 和 PostgreSQL 均为 healthy。

## 回归边界

- `agent-sandbox` 独立全量回归：57/57 通过。
- 完整 `mvn -pl agent-web -am test` 本次在 5 分钟内未结束，因工具超时终止；工作区聚焦测试和生产构建均已独立通过，不能将该全量命令标记为通过。
