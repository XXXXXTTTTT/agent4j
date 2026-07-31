# Phase 1 图引擎内核与 LLM 客户端设计

## 目标

在 Java 21 与 Spring Boot 3.3.13 基础上初始化 Maven 多模块工程，并在
`agent-core` 中实现不依赖第三方 Agent 框架的图状态机内核与 OpenAI 兼容
LLM 客户端。Phase 1 必须通过自动化测试证明节点跳转、虚拟线程调度、循环
熔断、同步补全、SSE 流式响应和 Function Calling 数据映射均可工作。

## 工程结构

根目录是 Maven 聚合工程，包含以下模块：

- `agent-core`：Phase 1 的图引擎、状态模型、LLM 客户端及测试。
- `agent-rag`：RAG 能力边界，本阶段仅建立可编译模块。
- `agent-sandbox`：沙箱能力边界，本阶段仅建立可编译模块。
- `agent-web`：Web 服务入口，本阶段提供 Spring Boot 启动类。

根 `pom.xml` 继承 `spring-boot-starter-parent` 3.3.13，统一使用 Java 21。
`agent-core` 仅引入 `spring-web`、Jackson 与测试依赖，不引入
LangChain4j、LangGraph4j 或其他 Agent 编排库。

## 不可变状态

`AgentState` 是以下精确结构的 Java record：

```java
public record AgentState(
        List<ChatMessage> messages,
        Map<String, String> variables,
        List<String> trace) {
}
```

紧凑构造器使用 `List.copyOf` 和 `Map.copyOf` 执行防御性复制，并拒绝空集合
引用。`empty()` 创建空状态；`withMessage`、`withVariable` 和 `withTraceEntry`
创建新集合并返回新 record，永不修改原实例。

## 图执行模型

`Node` 的单一抽象方法为：

```java
AgentState execute(AgentState state) throws Exception;
```

`Condition` 的单一抽象方法为：

```java
String route(AgentState state);
```

`StateGraph` 使用字符串节点标识，并提供以下构图操作：

- `addNode(String, Node)` 注册节点。
- `setEntryPoint(String)` 设置唯一入口。
- `addEdge(String, String)` 添加普通边。
- `addConditionalEdges(String, Condition, Map<String, String>)` 添加条件边。
- `execute(AgentState)` 从入口运行并返回最终不可变状态。

`StateGraph.END` 是唯一终点标识。每一步都将节点任务提交给
`Executors.newVirtualThreadPerTaskExecutor()`；调用线程只等待该任务完成。节点
完成后，执行器先计算条件路由，否则读取普通边。运行步数达到构造参数
`maxSteps` 后仍未到达 `END` 时抛出 `MaxStepsExceededException`。

构图错误抛出 `IllegalArgumentException` 或 `IllegalStateException`；节点执行失败
统一封装为 `GraphExecutionException`，保留原始 cause。`StateGraph` 实现
`AutoCloseable`，关闭时释放虚拟线程执行器。

## OpenAI 消息与 DTO

`ChatMessage` 是不可变 record，字段为 `role`、`content`、`name`、
`toolCallId` 和 `toolCalls`。角色枚举精确包含 `SYSTEM`、`USER`、
`ASSISTANT`、`TOOL`，序列化值分别为 OpenAI 协议要求的小写字符串。

Function Calling 所需的 `ToolCall` 与 `FunctionCall` 作为 `ChatMessage` 的嵌套
record。`toolCalls` 在构造时执行防御性复制。常用的 system、user、assistant
和 tool 消息通过静态工厂方法创建。

`LlmClient` 内定义公开的请求与响应 record DTO，覆盖以下协议字段：

- 请求：`model`、`messages`、`tools`、`tool_choice`、`temperature`、`stream`。
- 完整响应：`id`、`object`、`created`、`model`、`choices`、`usage`。
- SSE 增量：`id`、`object`、`created`、`model`、`choices`。
- 工具定义：`type` 与包含 `name`、`description`、`parameters` 的函数定义。
- 工具调用：`id`、`type` 以及包含 `name`、`arguments` 的函数调用。

所有蛇形 JSON 键通过 Jackson `@JsonProperty` 精确映射，不执行大小写或格式
推断。未知响应字段由 Jackson 显式配置为忽略，以兼容 OpenAI 格式网关增加
的扩展字段。

## LLM 请求与流式处理

`LlmClient` 接收 `RestClient` 和 `ObjectMapper`，默认 API 路径由构造参数完整
传入。它提供：

```java
ChatCompletionResponse complete(ChatCompletionRequest request);
void stream(ChatCompletionRequest request, Consumer<ChatCompletionChunk> consumer);
```

两类网络操作都提交到客户端私有的虚拟线程执行器。`complete` 强制发送
`stream: false`，`stream` 强制发送 `stream: true`。SSE 读取按规范处理
`data:` 行、空行事件边界和 `[DONE]` 终止标记；事件中的 JSON 仅交由 Jackson
解析。HTTP、I/O、序列化和回调异常统一封装为 `LlmClientException` 并保留
cause，供 Phase 3 的 `ModelRouter` 实现降级策略。

## 测试策略

实现严格遵循红、绿、重构循环：

- `AgentStateTest` 验证防御性复制和所有更新操作不修改旧状态。
- `StateGraphTest` 运行 Planner -> Tool -> End 条件循环，节点记录
  `Thread.currentThread().isVirtual()`；同时覆盖未知路由和最大步数熔断。
- `LlmClientTest` 使用 Spring `MockRestServiceServer` 验证请求 JSON、普通响应、
  SSE 多事件、`[DONE]`、Function Calling DTO 和 HTTP 错误封装。
- 根目录执行 `mvn test` 和 `mvn verify`，并通过依赖树检查确认不存在被禁止的
  Agent 框架。

## Phase 1 边界

本阶段不实现 Phase 2 的 AST、Docker、PTY，不实现 Phase 3 的 Playwright、
`ModelRouter`、Resilience4j，也不实现 Phase 4 的 Checkpointer、HITL 与状态
数据库。`Checkpointer` 和 `ModelRouter` 保留在后续阶段，避免产生无实现语义
的空接口。
