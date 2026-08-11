# 模型池完整维护设计

## 目标与现状

当前 `GET /api/model-config` 已按当前 `Actor.userId` 返回 Provider、Endpoint 和 Group 快照，
运行时也能根据对话轮次中的 `modelGroupId` 使用数据库配置调用真实模型。但是配置 API 只支持新增，
仅 Provider 有删除接口；前端 `ModelSettingsDialog` 也只有三个新增表单，用户无法查看配置关系、轮换
API Key、调整端点调度参数或维护既有模型组。

本设计补齐三类资源的查询展示、更新和删除闭环。数据库表结构与运行时路由协议保持不变，现有
Provider、Endpoint、Group 的 UUID 及已完成会话中的 `modelGroupId` 不因编辑而变化。

## 方案选择

### 方案 A：删除旧资源后重新创建

该方案可复用新增接口，但会改变 UUID，并可能让已保存的 `modelGroupId` 和组成员关系失效；API Key
轮换也需要重建全部下游配置，因此不采用。

### 方案 B：使用 `PATCH` 做字段级合并

该方案请求体较小，但必须区分缺失、`null` 和空字符串，并增加字段合并规则。现有 Controller 使用
Java record 承载请求，前端编辑表单提交完整资源，字段级合并不会带来实际收益，因此不采用。

### 方案 C：使用 `PUT` 全量替换可编辑字段

该方案保留资源 UUID，输入校验复用新增流程，响应仍使用现有展示 Record。Provider 的 `apiKey`
单独允许省略，以满足不暴露密钥正文情况下的普通资料编辑和显式密钥轮换。确定采用该方案。

## REST 合约

保留现有 `GET /api/model-config` 和三个 `POST` 接口，新增以下精确端点：

- `PUT /api/model-config/providers/{providerId}`
- `PUT /api/model-config/endpoints/{endpointId}`
- `PUT /api/model-config/groups/{groupId}`
- `DELETE /api/model-config/endpoints/{endpointId}`
- `DELETE /api/model-config/groups/{groupId}`

现有 `DELETE /api/model-config/providers/{providerId}` 继续保留。三个 `PUT` 成功均返回 HTTP 200 和
更新后的对应 Record；三个 `DELETE` 成功均返回 HTTP 204，不返回正文。前端在变更成功后重新请求
`GET /api/model-config`，以数据库快照作为唯一权威状态。

### Provider 更新

`UpdateProviderRequest` 的精确字段为：

- `displayName`：必填非空白字符串。
- `baseUrl`：必填绝对 HTTP/HTTPS URI。
- `chatCompletionsPath`：必填非空白字符串，必须以 `/` 开头，并通过现有
  `OpenAiEndpoint.resolve` 校验。
- `apiKey`：可省略或传 `null`，表示保留数据库中的原密钥；显式字符串必须非空白，并替换原密钥。

响应继续只返回 `apiKeyMasked`，任何读取和更新响应都不得返回 API Key 正文。前端编辑 Provider 时
密钥输入框始终为空，并以现有 `apiKeyMasked` 表示已配置状态；用户不填写则不发送 `apiKey` 字段，
填写后才执行轮换。前端不得把掩码值当作密钥提交。

### Endpoint 更新

`UpdateEndpointRequest` 的精确字段为 `displayName`、`modelId`、`capabilities`、`priority`、`weight`
和 `enabled`。Endpoint 的 `providerId` 不允许通过更新改变；跨 Provider 移动必须新建 Endpoint，
再显式调整 Group 成员后删除旧 Endpoint。

校验与新增一致：名称和模型 ID 非空白；`capabilities` 至少包含一个
`InferenceCapability`；`priority >= 0`；`weight > 0`。`enabled=false` 只停止后续运行时选择，
不会自动修改任何 Group 成员关系。

### Group 更新

`UpdateGroupRequest` 的精确字段为 `displayName`、`taskType` 和 `endpointIds`。`endpointIds` 必须
至少包含一个 UUID，不得重复，列表顺序写入 `agent_model_group_endpoints.position` 并作为同优先级
之外的稳定展示顺序。所有 Endpoint 必须归当前用户所有；更新在单个事务中替换全部 membership，
任一校验或写入失败时保留原 Group 和成员关系。

## 所有权与引用约束

Service 的每次操作只从 `ActorResolver.current()` 取得用户身份，Controller 请求体不接受
`ownerUserId`。Repository 对更新、删除以及 Group 成员校验都必须携带精确 `userId` 条件：目标不存在
或属于其他用户时统一抛出 `ModelConfigurationNotFoundException`，由现有异常处理器映射为 HTTP 404，
不泄露其他用户资源是否存在。

删除使用以下确定规则：

- Provider 只要仍有任意 Endpoint 直接引用，就返回 HTTP 409，错误明确指出应先删除 Endpoint；
  不依赖 Endpoint 是否已加入 Group，也不使用数据库的 `ON DELETE CASCADE` 隐式删除配置。
- Endpoint 只要仍有任意 Group membership 引用，就返回 HTTP 409，错误明确指出应先从 Group 移除；
  不依赖 Group 是否会在运行时被选择。
- Group 删除时由现有外键 `ON DELETE CASCADE` 删除其 membership；不删除 Endpoint 或 Provider。

唯一约束冲突继续由现有 `DuplicateKeyException` 映射为 HTTP 409。请求格式、枚举和数值校验失败返回
HTTP 400。上述冲突不会改写已有资源。

## Repository 与运行时一致性

`ModelConfigurationRepository` 增加三种更新和两种删除操作。`JdbcModelConfigurationRepository`
使用带当前用户条件的精确更新，更新 `updated_at`，随后按当前用户读取并返回更新后的 Record。
Provider 更新在同一事务中读取旧密钥、选择保留或替换值并写回，避免应用层接触展示掩码。
Group 更新与 membership 替换必须使用现有 `TransactionTemplate`。

本阶段不改变数据库表结构，不新增 Flyway 迁移。更新接口不接受 `updatedAt` 版本条件；同一资源的
并发更新以数据库最后成功提交的完整 `PUT` 为准，响应和随后刷新得到同一权威快照。

`DynamicModelGroupRouteResolver` 每次解析 Group、Endpoint 和 Provider 均读取数据库。其动态
`LlmClient` 缓存使用现有 Provider runtime fingerprint；Base URL、Chat Completions 路径或 API Key
变化后 fingerprint 改变，下一次解析自动关闭旧客户端并创建新客户端，不新增手工缓存失效接口。
Endpoint 和 Group 更新不缓存配置快照，下一次解析直接生效。已进入执行中的一次路由使用已经冻结的
`ModelEndpoint` 列表，不在请求中途切换配置。

## 前端交互

`ModelSettingsDialog` 改为三段资源管理视图，直接展示
`controller.modelConfiguration.providers`、`endpoints` 和 `groups`，不再只显示新增表单：

- Provider 行显示名称、Base URL、Chat 路径和密钥掩码，提供编辑与删除命令。
- Endpoint 行显示所属 Provider 名称、显示名、模型 ID、能力、优先级、权重和启停状态，提供编辑与删除命令。
- Group 行显示名称、任务类型和按顺序解析出的 Endpoint 名称，提供编辑与删除命令。

新增和编辑共用每类资源的表单组件；编辑时以精确 ID 保存当前目标，取消时恢复只读列表。能力使用
复选框，启停使用开关，优先级和权重使用数值输入，Group 成员使用可多选且保序的 Endpoint 列表。
删除操作必须显示资源名称和引用影响的确认对话；HTTP 409 的 `ProblemDetail.detail` 原样显示在面板
错误区。一次只允许编辑一个资源，提交期间禁用该表单的保存和删除操作，避免重复请求。

对话栏继续只展示 Group，不直接展示 Provider 或 Endpoint。Group 被删除后，前端刷新快照；若当前
选择正是该 Group，则清除选择并回到现有未指定 Group 的默认路由行为。

## 审计与安全

配置变更日志只记录动作类型、当前 `userId`、资源类型和资源 UUID，不记录 API Key、Base URL、模型
ID 或显示名称。异常日志沿用 `AuditTextRedactor`。前端状态、浏览器日志、HTTP 响应和测试快照中均
不得出现密钥正文。

## 测试与验收

- Service 单元测试：Provider 保留密钥与轮换密钥、URI/路径校验、Endpoint 数值与能力校验、Group
  空成员和重复成员校验，以及所有调用使用当前 Actor。
- JDBC 集成测试：三类更新成功、`updated_at` 变化、用户隔离、Provider 直接 Endpoint 引用冲突、
  Endpoint Group 引用冲突、Group membership 事务替换和 Group 删除只级联 membership。
- Controller 测试：三个 PUT、两个新增 DELETE、204/400/404/409 状态和 Provider 响应密钥脱敏。
- 前端 API 测试：精确请求路径、HTTP 方法、Provider 省略密钥、ProblemDetail 错误和更新后快照解码。
- 前端组件测试：现有资源展示、三类编辑、密钥不回填、能力/启停/调度参数编辑、删除确认、引用冲突
  展示和删除当前 Group 后选择回退。
- 完成前执行 JDK 21 相关 Java 测试、全部前端测试、`git diff --check` 和
  `mvn clean package -DskipTests`。
- Docker 重建后用真实 HTTP API 创建独立验收资源，完成 Provider、Endpoint、Group 的读取、更新、
  引用冲突和按依赖顺序删除；不得删除数据库中现有 `acceptance-` 前缀的验收证据。真实验收产生的
  API Key 只从本机 `.env` 读取，不写入仓库、终端回显或测试报告。
