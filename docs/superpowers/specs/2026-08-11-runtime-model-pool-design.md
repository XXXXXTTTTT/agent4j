# 运行时模型池接入设计

## 目标

将用户在数据库中配置的 Provider、模型端点和模型组接入生产 `ModelRouter`，使对话轮次选择的 `model.groupId` 真正决定运行时请求，同时保持未选择模型组时的 `.env` 默认路由兼容。

## 设计

- `agent-core` 新增 `ModelGroupRouteResolver`，只负责按精确组 ID和任务类型返回冻结的 `ModelEndpoint` 列表。
- `ModelRouter` 在静态组路由没有命中时调用解析器；解析失败转为 `ModelRoutingException`，组内端点仍按现有熔断、准入和顺序降级执行。
- `agent-web` 的动态解析器从 `NodeExecutionContext.currentState()` 读取 `planner.userId`，按用户条件查询组、端点和 Provider，拒绝跨用户资源。API Key 只用于后端创建 `RestClient`，不进入展示对象或日志。
- 会话级显式组选择贯穿 Planner 分类、代码、视觉和工具阶段；数据库中的 `taskType` 作为配置展示和路由能力提示，不阻断同一会话对该组端点的其他阶段调用，实际能力由 `InferenceServiceContract` 校验。
- Provider 新增 `chat_completions_path`，V6 迁移默认 `/v1/chat/completions`。URL 统一复用 `OpenAiEndpoint.resolve`，不自行拼接路径。
- 动态端点按配置快照版本缓存；缓存键为用户和组 ID，配置更新时间变化时重建。解析器关闭时关闭动态 `LlmClient`。
- 共享 HTTP 客户端继续使用现有 5 秒连接超时、45 秒响应超时；动态客户端复用该 HTTP 客户端，不创建无限等待的请求。

## 验证

- Core 测试验证显式组调用解析器、解析器返回组端点、组内失败降级、解析器无组明确失败和无组 ID时使用默认 `.env` 路由。
- Web 测试验证用户隔离、Provider 精确路径、端点优先级/权重顺序和密钥不出现在展示对象。
- 最后运行目标测试、`git diff --check` 和 `mvn clean package -DskipTests`。
