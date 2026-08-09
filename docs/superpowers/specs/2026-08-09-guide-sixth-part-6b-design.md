# 第六篇 6B：受控 Agent Profile 与拓扑查询设计

## 背景

第 20 章对比 Dify / Coze 后，项目需要一个可被 Web 工作台读取的 Agent 声明层。现有 `GraphRegistry` 已经通过 Spring `GraphFactory` 注入精确图标识，但缺少 profile 元数据和只读拓扑查询。此里程碑只增加声明与查询能力，不改变图执行语义。

## 目标

1. 提供不可变 `AgentProfile`，声明 profile 标识、关联图标识、模型任务类型、能力和执行预算。
2. 提供 `AgentProfileRegistry`，只接受构造器注入的 profile 映射和现有 `GraphRegistry`。
3. 提供 Web 只读 API，查询 profile 列表、单个 profile 以及其拓扑快照。
4. 对未注册 profile 或 graph 返回现有精确 404 ProblemDetail 语义。
5. 通过测试证明查询不会执行节点，不允许动态类名、表达式或图编辑。

## 非目标

- 不开放任意类名、脚本、表达式或 JSON 图定义。
- 不提供创建、修改、删除 profile 或图的 API。
- 不改变 `StateGraph.execute()`、`GraphRegistry.create()` 或权限边界。
- 不引入 LangChain4j、LangGraph4j 或其他 Agent 编排框架。

## 核心协议

### `AgentProfile`

包：`com.agent.core.profile`

不可变 record 字段：

- `String profileId`：对外精确 profile 标识。
- `String graphId`：必须对应 `GraphRegistry` 已注册图。
- `String displayName`：展示名称。
- `String description`：展示说明。
- `Set<TaskType> taskTypes`：声明支持的模型任务类型。
- `Set<String> capabilities`：声明能力标签，原样保留大小写和结构。
- `ExecutionBudget executionBudget`：声明资源预算快照。

构造器校验所有文本非空、集合非空引用且不可变，能力标签不做大小写归一化。

### `AgentProfileRegistry`

包：`com.agent.core.profile`

- 构造器接收 `Map<String, AgentProfile>` 和 `GraphRegistry`，按 `AgentProfile.profileId()` 建立不可变索引。
- `profileIds()` 返回不可变、稳定排序的 profile 标识集合。
- `get(String profileId)` 只按精确字符串查找，未知值抛出 `AgentProfileNotFoundException`。
- `inspect(String profileId)` 先精确读取 profile，再通过 `graphId` 创建一次图，读取 `inspectTopology()`，使用 try-with-resources 关闭图，返回 `AgentProfileSnapshot`。
- profile 指向未注册 graph 时透传 `GraphNotFoundException`，不执行节点。

### Web API

包：`com.agent.web.profile`

- `GET /api/agent-profiles`：返回 `AgentProfileView[]`，只包含 profile 声明元数据，不创建图。
- `GET /api/agent-profiles/{profileId}`：返回 `AgentProfileDetailView`，包含 profile 元数据与拓扑快照。
- `GET /api/agent-profiles/{profileId}/topology`：返回 `GraphTopology` 快照。

路径参数按原样传递，禁止模糊匹配。未知 profile 使用 `AgentProfileNotFoundException`，由 `RunExceptionHandler` 映射为 404；未知 graph 继续使用 `GraphNotFoundException` 的 404 语义。

Spring 装配仅允许 `AgentProfile` Bean 通过构造器注入。示例图和生产图分别声明自己的 profile Bean；用户输入不能影响 Bean、类名或图拓扑。

## 数据流与生命周期

```text
HTTP profileId
  -> AgentProfileController
  -> AgentProfileRegistry.get/inspect
  -> exact AgentProfile.graphId
  -> GraphRegistry.create (一次)
  -> StateGraph.inspectTopology (不执行节点)
  -> close StateGraph
  -> immutable view
```

## 错误处理

- 空白 profileId、非法 record 字段：`IllegalArgumentException`，映射 400。
- 未注册 profile：`AgentProfileNotFoundException`，映射 404。
- profile 关联的 graph 未注册：`GraphNotFoundException`，映射 404。
- 图拓扑本身非法：只读查询仍返回快照；严格校验仍由已有 `validateTopology()` 负责。
- Controller 不泄露底层异常堆栈，沿用既有 ProblemDetail 处理器。

## 测试门禁

1. Core 单元测试：record 不可变校验、精确 profile 查找、稳定 ID 列表、拓扑查询只创建一次图并关闭、节点不执行、未知 graph 透传。
2. Web `@WebFluxTest`：列表、详情、拓扑 JSON 字段、未知 profile/graph 的 404、非法路径输入的 400。
3. 回归：`mvn -pl agent-core,agent-web -am test -Dfrontend.skip=true`。
4. 构建：`mvn clean package -DskipTests -Dfrontend.skip=true`。

## 安全与架构约束

Profile 是受控的只读声明，不是低代码图编辑器。所有可执行对象仍由 Java 类型、Spring Bean 和现有 workspace 权限装配；该边界防止用户通过类名或表达式绕过类型安全、沙箱和权限门禁。
