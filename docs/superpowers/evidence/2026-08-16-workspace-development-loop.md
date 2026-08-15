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

## 尚未完成

- Docker Desktop 当前不可连接：`//./pipe/dockerDesktopLinuxEngine` 不存在，8080 和 5432 均未监听。
- 因此尚未执行真实 HTTP 空项目 EDD，也没有生成 Run、Trace SSE 和终端 SSE 的最终证据。
- 完整 `mvn -pl agent-web -am test` 被既有 `agent-sandbox` Windows PTY 临时目录文件锁测试阻断，错误为 `PtyCommandExecutorTest.releasesWorkingDirectoryBeforeReturningFromTimeout`。
