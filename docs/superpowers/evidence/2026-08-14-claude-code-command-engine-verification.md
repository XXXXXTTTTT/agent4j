# Claude Code 风格指令引擎验证证据

验证日期：2026-08-14（北京时间）

## EGG：本地双通道拦截

命令：

```text
mvn -pl agent-core,agent-web -Dfrontend.skip=true -Dtest=CommandEngineEggTest,CommandEngineEddTest,AgentRunCommandCheckpointServiceTest test
```

结果：

- `CommandEngineEggTest`：2/2 通过。
- 系统控制指令在 Dispatcher 本地完成，工作流桥接计数为 0。
- 明确选择工作流指令时才进入工作流桥接端口，结果为 `FORWARDED`。
- `AgentRunCommandCheckpointServiceTest`：2/2 通过。

系统命令路径不创建 `LlmClient` 请求；它只读取 Registry、会话上下文、权限和 Checkpoint 端口。

## EDD：真实模型网关

测试开关：`AGENT_E2E_REAL_LLM=true`。

测试从本机 `.env` 读取真实的 `AGENT_LLM_BASE_URL`、`AGENT_LLM_CHAT_COMPLETIONS_PATH`、`AGENT_LLM_API_KEY` 和 `AGENT_LLM_CODE_MODEL`，没有替换为伪造成功响应，也没有把密钥写入仓库。

命令：

```text
mvn -pl agent-web -Dfrontend.skip=true -Dtest=CommandEngineEddTest test
```

结果：

- 测试：1/1 通过。
- HTTP 状态：200。
- 实际模型：`gpt-5.4-mini`。
- LLM 客户端记录：输入 12 tokens、输出 6 tokens、耗时约 2034 ms。

## 前端

- Vitest：25 个测试文件、135 个测试全部通过。
- Vite 生产构建成功。

## 既有测试残余风险

全量 `mvn -Dfrontend.skip=true test` 在执行窗口内未完成。Surefire 报告显示一个既有的 `DockerMcpMaterialPreparationRunnerTest.rejectsSymbolicLinkCreatedInsideMaterialTree` 因材料准备等待约 72 秒后失败；该失败不涉及 Slash Command 引擎。命令引擎定向 Core/Web 测试均通过。
