# 第六篇 6A：框架对比与架构守卫设计

## 目标与边界

本里程碑对应教程第 19 章 Framework Comparison。目标是把 Agent4J 的“去框架化自研”从文档约定
变成可重复执行的架构门禁，并提供一份精确的概念映射，说明 Agent4J 类型如何覆盖常见 Agent 框架
中的 State、Node、Graph、Checkpoint、Tool、Model Gateway 和 Runtime 概念。

本里程碑不修改运行时逻辑，不新增第三方依赖，不实现用户可编辑的图，不扫描构建产物，不把 README
或 AGENTS.md 中用于说明的框架名称误判为生产依赖。

## 架构守卫协议

新增 `com.agent.core.architecture.ArchitectureConstraintTest`，只读取仓库根 `pom.xml`、
`agent-core/pom.xml`、`agent-core/src/main/java` 和 `docs/ARCHITECTURE_MAPPING.md`：

1. 使用 JAXP XML 解析器读取两个 POM 的 `<dependency>`，精确拒绝包含以下固定片段的
   `groupId` 或 `artifactId`：`langchain4j`、`langgraph4j`、`spring-ai`、`autogen`、`crewai`、
   `llamaindex`。片段比较只在已解析的坐标上进行 ASCII 小写化，禁止对业务标识符做模糊推断。
2. 递归读取 `agent-core/src/main/java` 的 `.java` 文件，拒绝精确导入
   `dev.langchain4j`、`org.bsc.langgraph4j`、`org.springframework.ai`、`io.agentscope`、
   `com.alibaba.cloud.ai`；测试源码和文档不在扫描范围。
3. 验证自研核心端口文件存在：`AgentState`、`Node`、`Condition`、`StateGraph`、
   `Checkpointer`、`ToolRegistry`、`ModelRouter`、`AgentRunService`。缺少任一文件时门禁失败。
4. 解析映射文档，要求每个固定端口名称和“自研”声明均出现；映射文档缺失或字段不足时门禁失败。

测试失败时返回精确路径、坐标或导入文本，不能自动删除或替换依赖。

## 映射文档协议

新增 `docs/ARCHITECTURE_MAPPING.md`，以表格记录：

- `AgentState`、`Node`、`Condition`、`StateGraph` 对应状态、节点、条件边和图执行概念；
- `Checkpointer`、`InterruptRequest`、`AgentRunService` 对应 checkpoint、HITL 和 runtime；
- `ToolRegistry`、`McpClient`、`SkillCatalog` 对应工具治理、MCP 传输和能力编排；
- `ModelRouter`、`LlmClient` 对应模型网关和协议客户端；
- `RagRetrievalPipeline`、`MemoryManager`、`HarnessHookChain` 对应知识、记忆和可观测性。

每行都明确“Agent4J 实现”和“边界/差异”，声明映射是概念对照，不是第三方框架运行时依赖。

## 测试与报告

`ArchitectureConstraintTest` 覆盖禁止依赖、禁止导入、核心端口存在、映射文档完整四个行为。
测试只使用本地源码和 XML，不调用网络或真实模型。测试命令固定为：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -pl agent-core '-Dtest=ArchitectureConstraintTest' test
```

完成后更新 `docs/ENGINEERING_PITFALLS.md`，记录“文档宣称去框架化但 POM 可悄悄引入依赖”的现象、
根因、守卫实现和测试证据；不写未经命令证明的结论。

## 非目标

- 不修改 `agent-core/pom.xml` 现有合法依赖。
- 不把 OpenAI 协议客户端或 Spring Web 误判为 Agent 编排框架。
- 不对用户源码、`agent-rag`、`agent-web` 的业务行为添加静态限制。
- 不实现第 20 章 Agent Profile、拓扑查询 REST 或低代码编辑。
