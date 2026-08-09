# Agent4J Architecture Mapping

Agent4J 自研核心以 Java 21 `StateGraph` 为运行时，不把下表中的概念映射理解为第三方框架运行时依赖。
表中的“框架概念”只用于说明设计语义，实际执行始终由 Agent4J 自有类型、构造器注入端口和
PostgreSQL 权威状态完成。

| 框架概念 | Agent4J 自研实现 | Agent4J 实现 | 边界与差异 |
| --- | --- | --- | --- |
| State / immutable state | `AgentState` | Java record，消息、变量和 trace 均防御性复制 | 节点只能返回新状态，不允许原地修改或隐式 reducer |
| Node | `Node` | 上下文感知的节点接口，运行于 Java 21 虚拟线程 | 节点不依赖框架注解，状态键由调用方显式约定 |
| Conditional edge | `Condition` | 精确字符串路由到 `StateGraph` 已声明目标 | 路由值不做大小写或别名猜测，未知路线立即失败 |
| Graph / compiled graph | `StateGraph` | 自研循环调度、预算、中断、恢复和拓扑校验 | 不引入外部图编排库；图执行使用 `GraphExecutionRequest` |
| Checkpoint | `Checkpointer`、`RunCheckpoint` | PostgreSQL 作为 Run 与状态的唯一权威源 | Redis/事件通道不替代状态持久化 |
| Human-in-the-loop | `InterruptRequest`、`InterruptPolicy` | 节点执行前挂起，审批决定后从 checkpoint 恢复 | 子图中断保持 `SubgraphInterruptedException`，不伪装完成 |
| Subgraph / handoff | `SubgraphNode`、`AgentHandoffExecutor` | 显式状态投影、合并和有界子运行 | 不复制全量状态，不允许隐式覆盖或无界接力 |
| Tool registry | `ToolRegistry`、`DefaultToolRegistry` | JSON Schema、能力、风险、审批、超时和审计统一入口 | MCP、CLI、AST 和浏览器能力不能绕过 Registry |
| MCP transport | `McpClient`、`McpHttpTransport` | 手写 JSON-RPC 握手、发现、调用和错误协议 | MCP 只负责协议，权限与副作用治理仍由 Registry 负责 |
| Skills | `SkillCatalog`、`SkillDefinition` | 版本化只读清单和渐进披露 Prompt 上下文 | Skill 只能编排已注册工具，不执行任意反射或脚本 |
| Model gateway | `ModelRouter`、`LlmClient` | 按 `TaskType` 路由、熔断、降级和 OpenAI 兼容 SSE | 具体模型端点由构造器注入，不锁定某个厂商 SDK |
| Runtime / run lifecycle | `AgentRunService`、`RunStatus` | 创建、执行、checkpoint、恢复、取消和终态查询 | Web 层只调用服务端口，不自行复制状态机逻辑 |
| RAG retrieval | `RagRetrievalPipeline` | 向量、BM25、AST 符号、改写、HyDE 和 rerank 端口组合 | 增强阶段失败时保留证据并降级到可证明的基线 |
| Long-term memory | `MemoryManager` | PostgreSQL 记忆、去重、scope、重要度和生命周期 | 动态偏好与 Bad Case 可衰减，静态架构规则不衰减 |
| Observability hooks | `HarnessHookChain`、`TraceEvent` | 节点、工具、预算、模型和失败事件的结构化留痕 | 观测失败隔离；权限、审批和预算失败必须阻止副作用 |

## 依赖边界

`agent-core` 允许使用 Java 标准库、Spring Web 的 HTTP 协议能力、Jackson、Resilience4j 和
Agent4J 自有模块；它禁止引入第三方 Agent 编排运行时。OpenAI 兼容协议、MCP JSON-RPC 和
PostgreSQL/pgvector 都通过明确的端口或适配器接入，不能反向改变核心图引擎的状态语义。

`agent-web` 负责 Spring WebFlux、持久化装配和前端网关；`agent-rag` 负责知识与记忆；
`agent-sandbox` 负责 AST、Docker/PTY 和 Playwright；`agent-eval` 负责 EDD 与 Benchmark。
这些模块通过构造器注入协作，不把第三方 Agent 框架的隐式全局上下文带入核心。
