# 第四篇 4B：MCP 工具适配层设计

## 目标

在 4A `ToolRegistry` 之上接入 MCP（Model Context Protocol）远程工具。MCP 只负责
协议握手、工具发现和远程调用；所有远程工具必须先转换为本地不可变 `ToolDefinition`
和 `ToolHandler`，再由 Registry 执行 JSON Schema、能力、审批、超时、Hook 与审计。
任何 MCP 失败都必须保留完整 cause，不得把远程调用直接暴露给 Planner 或绕过治理边界。

## 边界

本里程碑只实现 JSON-RPC 2.0 的 MCP client port、严格响应协议、HTTP JSON transport
和 Registry adapter。传输端只接受单个 `application/json` 响应；SSE/Streamable HTTP
会被明确报告为不支持的传输错误，不伪造成功。不会引入 MCP SDK、LangChain4j、
LangGraph4j、stdio 子进程或前端配置页面；stdio transport 留到后续 CLI 能力篇章。

## 公开协议

### JSON-RPC

`McpJsonRpcRequest` 是不可变 record，字段精确为 `jsonrpc="2.0"`、`id`、`method`、
`params`。请求 ID 由客户端单调递增并以字符串发送；通知没有 `id` 且不等待响应。
`McpJsonRpcResponse` 严格要求 `jsonrpc`、`id` 与 `result/error` 二选一。错误对象必须
包含数值 `code` 和字符串 `message`，可选 `data` 原样保留；重复字段、尾随 JSON、
错误响应与 ID 不匹配都抛 `McpProtocolException`。

### MCP client

`McpClient.initialize()` 发送 `initialize`，校验结果包含字符串 `protocolVersion`、
对象 `capabilities` 和对象 `serverInfo`，随后发送 `notifications/initialized`。
初始化只允许成功一次；未初始化时 `listTools`/`callTool` 立即失败。

`listTools()` 调用 `tools/list`，只接受结果中的 `tools` 数组和可选字符串 `nextCursor`。
每个工具精确要求字符串 `name`、非空 `description` 和 object `inputSchema`，映射为
不可变 `McpRemoteTool`。未知字段、重复名称、非法名称和非法 Schema 立即失败。

`callTool(name, arguments)` 调用 `tools/call`，参数只允许 object；返回结果保存完整
`content` 数组和 `isError` 布尔值。`isError=true` 转换为 Registry handler 异常并保留
远程错误 JSON；不把远程错误当作成功输出。

### Registry adapter

`McpToolRegistryAdapter.registerDiscoveredTools(namespace, riskLevel, capabilities, timeout)`
先初始化并发现工具，再按 `namespace + "." + remoteName` 生成本地名。namespace 与
remoteName 必须组合成 4A 名称正则允许的精确字符串，不做大小写或字符替换；冲突由
Registry 原子拒绝。handler 只闭包远程原名，收到本地 `ToolCall` 后调用 `McpClient`。
返回的 content 数组作为 JSON array 输出，远程 `isError` 抛出 `McpRemoteToolException`。

## HTTP transport

`McpHttpTransport` 构造器注入 `RestClient`、`ObjectMapper`、endpoint URI 和请求超时。
请求使用 `POST`、`Content-Type: application/json`、`Accept: application/json`；非 2xx、
空响应、非 JSON content type、解析失败、请求超时均抛 `McpTransportException`。日志只记录
endpoint、method、requestId、HTTP 状态和耗时，不记录 params、API Key 或源码。

## 测试门禁

- `McpJsonRpcProtocolTest` 覆盖严格字段、重复字段、尾随 token、error/result 互斥和 ID。
- `McpClientTest` 使用确定性 fake transport 覆盖握手顺序、工具发现、未初始化和远程错误。
- `McpToolRegistryAdapterTest` 断言发现工具经过 Registry、Schema/能力/审批仍生效，handler
  只调用一次远程工具并保留原始 content。
- `McpHttpTransportTest` 使用本地 `HttpServer` 覆盖请求头、JSON body、非 2xx、SSE 拒绝、
  超时和日志脱敏。
- EDD 写入 `agent-eval/target/edd/mcp-tool-adapter-edd.json`，覆盖 initialize、发现、
  成功调用、Schema 拒绝、权限拒绝、审批拒绝、远程失败七条路线。

## 非目标与后续

4B 不实现 MCP server、stdio 进程管理、动态用户配置或 Skills。4C Skills 只能消费本里程
碑的只读发现结果和 Prompt 元数据；第七篇再提供 AST、PTY、Docker、Playwright 的真实
工具适配器。
