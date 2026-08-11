# Model Pool Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为用户隔离的 Provider、Endpoint 和 Group 补齐可查看、编辑、密钥轮换、引用保护删除及前端完整维护闭环。

**Architecture:** 保持 PostgreSQL V5/V6 表结构和 `DynamicModelGroupRouteResolver` 不变，在现有 Repository、Service、Controller 三层增加全量 `PUT` 与受保护 `DELETE`。前端 API 以服务器快照为权威源，设置对话框拆为三个资源区段，成功写入后统一重新加载配置。

**Tech Stack:** Java 21、Spring Boot 3.3 WebFlux、Spring JDBC、PostgreSQL 16/Testcontainers、React 19、TypeScript、Vitest、Testing Library、Maven、Docker Compose

---

## 文件职责

- `agent-web/src/main/java/com/agent/web/model/ModelConfigurationRepository.java`：定义用户隔离的模型配置持久化命令。
- `agent-web/src/main/java/com/agent/web/model/ModelConfigurationService.java`：校验更新输入、解析当前 Actor、控制密钥保留语义。
- `agent-web/src/main/java/com/agent/web/persistence/JdbcModelConfigurationRepository.java`：执行精确所有权查询、事务更新和引用冲突检查。
- `agent-web/src/main/java/com/agent/web/controller/ModelConfigurationController.java`：暴露三个 `PUT` 和完整删除 REST 合约。
- `agent-web/src/main/frontend/src/api/conversationApi.ts`：发送精确请求并重新读取权威快照。
- `agent-web/src/main/frontend/src/hooks/useConversationWorkspace.ts`：向设置 UI 提供模型配置命令。
- `agent-web/src/main/frontend/src/components/ModelProviderSettingsSection.tsx`：Provider 列表、新增、编辑、密钥轮换和删除确认。
- `agent-web/src/main/frontend/src/components/ModelEndpointSettingsSection.tsx`：Endpoint 列表、调度参数、能力、启停和删除确认。
- `agent-web/src/main/frontend/src/components/ModelGroupSettingsSection.tsx`：Group 列表、任务类型、保序成员和删除确认。
- `agent-web/src/main/frontend/src/components/ModelSettingsDialog.tsx`：组合三个区段并统一展示异步错误。
- `agent-web/src/main/frontend/src/styles.css`：为资源行、编辑表单、开关和危险操作提供响应式样式。

### Task 1: Provider 更新与删除保护

**Files:**
- Modify: `agent-web/src/test/java/com/agent/web/model/ModelConfigurationServiceTest.java`
- Create: `agent-web/src/test/java/com/agent/web/model/JdbcModelConfigurationRepositoryIntegrationTest.java`
- Modify: `agent-web/src/main/java/com/agent/web/model/ModelConfigurationRepository.java`
- Modify: `agent-web/src/main/java/com/agent/web/model/ModelConfigurationService.java`
- Modify: `agent-web/src/main/java/com/agent/web/persistence/JdbcModelConfigurationRepository.java`

- [ ] **Step 1: 写 Provider Service 失败测试**

在 `ModelConfigurationServiceTest` 的 Fake Repository 中记录更新参数，并增加以下行为测试：

```java
@Test
void updatesProviderAndPreservesOrRotatesApiKeyExplicitly() {
    FakeRepository repository = new FakeRepository();
    ModelConfigurationService service = service(repository);
    UUID providerId = UUID.fromString("487583db-b055-4ba3-923d-78d67075f515");

    service.updateProvider(providerId, "主网关更新", "https://new.example",
            "/openai/chat", null);
    assertThat(repository.updatedApiKey).isNull();
    assertThat(repository.updatedOwnerId).isEqualTo(ACTOR.userId());

    service.updateProvider(providerId, "主网关更新", "https://new.example",
            "/openai/chat", "sk-rotated");
    assertThat(repository.updatedApiKey).isEqualTo("sk-rotated");
}

@Test
void rejectsBlankProviderUpdateValuesAndBlankExplicitKey() {
    ModelConfigurationService service = service(new FakeRepository());
    UUID providerId = UUID.fromString("487583db-b055-4ba3-923d-78d67075f515");
    assertThatThrownBy(() -> service.updateProvider(providerId, " ",
            "https://new.example", "/chat", null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.updateProvider(providerId, "主网关",
            "https://new.example", "/chat", " ")).isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: 运行 Service 测试并确认失败**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -pl agent-web -Dtest=ModelConfigurationServiceTest test
```

Expected: 编译失败，明确指出 `updateProvider` 尚不存在。

- [ ] **Step 3: 扩展 Repository 端口并实现 Service 最小逻辑**

在 `ModelConfigurationRepository` 增加：

```java
ModelProviderRecord updateProvider(UUID providerId, Actor actor, String displayName,
                                   String baseUrl, String chatCompletionsPath,
                                   String apiKey, Instant now);
```

在 `ModelConfigurationService` 增加 `updateProvider`。复用抽取后的 `validatedBaseUrl`、
`normalizeChatCompletionsPath` 和 `requireText`；`apiKey == null` 时传 `null` 给 Repository，
非空时去除两端空白，空白显式值抛出 `IllegalArgumentException`。

- [ ] **Step 4: 写 JDBC Provider 更新和删除冲突失败测试**

新建 `JdbcModelConfigurationRepositoryIntegrationTest`，按现有 Conversation 集成测试启动
`postgres:16-alpine`、执行 Flyway，并在每例前清空 V5 四张模型表和 `agent_users`。测试必须断言：

```java
@Test
void updatesProviderWithPreservedAndRotatedSecretForOwnerOnly() {
    ModelProviderRecord created = repository.createProvider(PROVIDER_ID, OWNER,
            "主网关", "https://old.example", "/v1/chat/completions", "sk-original", NOW);
    ModelProviderRecord preserved = repository.updateProvider(PROVIDER_ID, OWNER,
            "主网关更新", "https://new.example", "/chat", null, NOW.plusSeconds(1));
    assertThat(repository.apiKey(PROVIDER_ID, OWNER.userId())).contains("sk-original");
    assertThat(preserved.apiKeyMasked()).isEqualTo("sk-o****inal");

    repository.updateProvider(PROVIDER_ID, OWNER, "主网关更新",
            "https://new.example", "/chat", "sk-rotated", NOW.plusSeconds(2));
    assertThat(repository.apiKey(PROVIDER_ID, OWNER.userId())).contains("sk-rotated");
    assertThatThrownBy(() -> repository.updateProvider(PROVIDER_ID, OTHER,
            "越权", "https://other.example", "/chat", null, NOW))
            .isInstanceOf(ModelConfigurationNotFoundException.class);
}

@Test
void refusesToDeleteProviderWhileAnyEndpointExists() {
    createProviderAndEndpoint();
    assertThatThrownBy(() -> repository.deleteProvider(PROVIDER_ID, OWNER.userId()))
            .isInstanceOf(ModelConfigurationConflictException.class)
            .hasMessageContaining("先删除 Endpoint");
}
```

- [ ] **Step 5: 运行 JDBC 测试并确认失败**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -pl agent-web -Dtest=JdbcModelConfigurationRepositoryIntegrationTest test
```

Expected: `updateProvider` 缺失，且未入组 Endpoint 的 Provider 删除保护测试失败。

- [ ] **Step 6: 实现 JDBC Provider 事务更新和直接引用检查**

实现时先调用 `requireOwnedProvider(providerId, actor.userId())`，在 `TransactionTemplate` 内通过
`coalesce(:apiKey, api_key)` 保留密钥，并限定 `provider_id` 与 `owner_user_id`。删除检查改为：

```sql
select count(*)
from agent_model_endpoints
where provider_id = :providerId
```

计数大于零时抛出消息 `Provider 仍有 Endpoint，请先删除 Endpoint: <providerId>`。

- [ ] **Step 7: 运行 Provider 测试并提交**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -pl agent-web -Dtest=ModelConfigurationServiceTest,JdbcModelConfigurationRepositoryIntegrationTest test
```

Expected: 全部通过。

Commit:

```powershell
git add agent-web/src/main/java/com/agent/web/model agent-web/src/main/java/com/agent/web/persistence/JdbcModelConfigurationRepository.java agent-web/src/test/java/com/agent/web/model
git commit -m "feat(web): update model providers safely"
```

### Task 2: Endpoint 与 Group 更新删除

**Files:**
- Modify: `agent-web/src/test/java/com/agent/web/model/ModelConfigurationServiceTest.java`
- Modify: `agent-web/src/test/java/com/agent/web/model/JdbcModelConfigurationRepositoryIntegrationTest.java`
- Modify: `agent-web/src/main/java/com/agent/web/model/ModelConfigurationRepository.java`
- Modify: `agent-web/src/main/java/com/agent/web/model/ModelConfigurationService.java`
- Modify: `agent-web/src/main/java/com/agent/web/persistence/JdbcModelConfigurationRepository.java`

- [ ] **Step 1: 写 Service 失败测试**

增加以下精确调用与校验测试：

```java
@Test
void validatesAndDelegatesEndpointAndGroupUpdatesForCurrentActor() {
    FakeRepository repository = new FakeRepository();
    ModelConfigurationService service = service(repository);
    service.updateEndpoint(ENDPOINT_ID, "主模型", "gpt-exact",
            Set.of(InferenceCapability.CHAT_COMPLETIONS), 2, 5, false);
    service.updateGroup(GROUP_ID, "代码组", TaskType.CODE, List.of(ENDPOINT_ID));
    assertThat(repository.updatedOwnerId).isEqualTo(ACTOR.userId());
    assertThat(repository.updatedEndpointEnabled).isFalse();
    assertThat(repository.updatedEndpointIds).containsExactly(ENDPOINT_ID);
}

@Test
void rejectsDuplicateGroupMembers() {
    ModelConfigurationService service = service(new FakeRepository());
    assertThatThrownBy(() -> service.updateGroup(GROUP_ID, "代码组", TaskType.CODE,
            List.of(ENDPOINT_ID, ENDPOINT_ID)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("endpointIds 不能重复");
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -pl agent-web -Dtest=ModelConfigurationServiceTest test`

Expected: `updateEndpoint`、`updateGroup` 尚不存在。

- [ ] **Step 3: 定义并实现 Service/Repository 命令**

Repository 增加以下精确签名：

```java
ModelEndpointRecord updateEndpoint(UUID endpointId, Actor actor, String displayName,
        String modelId, Set<InferenceCapability> capabilities, int priority,
        int weight, boolean enabled, Instant now);
ModelGroupRecord updateGroup(UUID groupId, Actor actor, String displayName,
        TaskType taskType, List<UUID> endpointIds, Instant now);
void deleteEndpoint(UUID endpointId, String userId);
void deleteGroup(UUID groupId, String userId);
```

Service 使用与 create 相同的文本、能力、优先级和权重校验；Group 在访问 Repository 前通过
`new LinkedHashSet<>(endpointIds).size() == endpointIds.size()` 验证无重复，并为四种操作传入当前 Actor。

- [ ] **Step 4: 写 JDBC 事务与引用约束失败测试**

集成测试覆盖：更新 Endpoint 不改变 `provider_id`；其他用户更新返回 NotFound；Group 更新后成员顺序
与请求一致；含其他用户 Endpoint 的 Group 更新整体回滚；被 Group 引用的 Endpoint 删除返回包含
`先从 Group 移除` 的冲突；Group 删除后 membership 消失但 Endpoint 和 Provider 均保留。

```java
@Test
void replacesGroupMembershipAtomicallyAndDeletesOnlyMembership() {
    createTwoEndpointsAndGroup();
    ModelGroupRecord updated = repository.updateGroup(GROUP_ID, OWNER, "更新组",
            TaskType.VISION, List.of(SECOND_ENDPOINT_ID, ENDPOINT_ID), NOW.plusSeconds(1));
    assertThat(updated.endpointIds()).containsExactly(SECOND_ENDPOINT_ID, ENDPOINT_ID);

    repository.deleteGroup(GROUP_ID, OWNER.userId());
    assertThat(repository.findGroups(OWNER.userId())).isEmpty();
    assertThat(repository.findEndpoints(OWNER.userId())).hasSize(2);
    assertThat(repository.findProviders(OWNER.userId())).hasSize(1);
}
```

- [ ] **Step 5: 实现 JDBC 更新、删除和事务回滚**

`updateEndpoint` 使用 Provider join 校验所有权，再更新可编辑字段与 `updated_at`。
`updateGroup` 在一个 `TransactionTemplate` 内校验 Group 与每个 Endpoint 所有权、更新 Group、删除旧
membership、按请求索引插入新 membership。`deleteEndpoint` 先统计
`agent_model_group_endpoints.endpoint_id` 引用；`deleteGroup` 只删除当前用户拥有的 Group。

- [ ] **Step 6: 运行领域与 JDBC 测试并提交**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -pl agent-web -Dtest=ModelConfigurationServiceTest,JdbcModelConfigurationRepositoryIntegrationTest test
```

Expected: 全部通过。

Commit: `git commit -m "feat(web): manage model endpoints and groups"`

### Task 3: REST 更新删除合约

**Files:**
- Modify: `agent-web/src/test/java/com/agent/web/controller/ModelConfigurationControllerTest.java`
- Modify: `agent-web/src/test/java/com/agent/web/model/ModelConfigurationServiceTest.java`
- Modify: `agent-web/src/main/java/com/agent/web/controller/ModelConfigurationController.java`
- Modify: `agent-web/src/main/java/com/agent/web/controller/RunExceptionHandler.java`
- Modify: `agent-web/src/main/java/com/agent/web/model/ModelConfigurationService.java`

- [ ] **Step 1: 写三个 PUT 和两个 DELETE 失败测试**

使用 WebTestClient 验证精确路径、请求字段、响应码和 Service 参数。Provider 测试请求不包含
`apiKey` 并验证传给 Service 的值为 `null`；Endpoint 删除模拟
`ModelConfigurationConflictException("Endpoint 仍被 Group 引用，请先从 Group 移除: ...")`
并断言 HTTP 409 的 `$.detail` 精确一致；成功删除断言 204 且无正文。

```java
client.put().uri("/api/model-config/providers/{id}", providerId)
        .header("Content-Type", "application/json")
        .bodyValue("""
                {"displayName":"更新网关","baseUrl":"https://new.example",
                 "chatCompletionsPath":"/chat"}
                """)
        .exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.apiKeyMasked").isEqualTo("sk-o****inal");
```

- [ ] **Step 2: 运行 Controller 测试并确认失败**

Run: `mvn -pl agent-web -Dtest=ModelConfigurationControllerTest test`

Expected: PUT 和新增 DELETE 路由返回 405。

- [ ] **Step 3: 实现 Controller 合约**

新增 `@PutMapping` 导入和三个请求 record：

```java
public record UpdateProviderRequest(
        @NotBlank String displayName,
        @NotBlank String baseUrl,
        @NotBlank String chatCompletionsPath,
        String apiKey) {}
public record UpdateEndpointRequest(
        @NotBlank String displayName,
        @NotBlank String modelId,
        @NotEmpty Set<InferenceCapability> capabilities,
        @PositiveOrZero int priority,
        @Positive int weight,
        boolean enabled) {}
public record UpdateGroupRequest(
        @NotBlank String displayName,
        @NotNull TaskType taskType,
        @NotEmpty List<UUID> endpointIds) {}
```

三个 PUT 调用对应 Service 并返回更新 Record；Endpoint/Group DELETE 返回
`ResponseEntity.noContent().build()`。保留 `RunExceptionHandler` 现有 NotFound 与 Conflict 映射，只有
测试证明缺口时才调整异常列表。

- [ ] **Step 4: 增加不含配置正文的结构化审计日志**

在 `ModelConfigurationService` 的六种 create/update/delete 成功返回前后记录：

```java
LOGGER.info("Model configuration changed action={} userId={} resourceType={} resourceId={}",
        action, actor.userId(), resourceType, resourceId);
```

`action` 只使用 `CREATE`、`UPDATE`、`DELETE`；`resourceType` 只使用 `PROVIDER`、`ENDPOINT`、
`GROUP`。测试使用 Logback `ListAppender` 捕获一次 Provider 密钥轮换日志，断言包含 action、userId、
resourceType、resourceId，且不包含 `sk-rotated`、Base URL、模型 ID 和显示名称。

- [ ] **Step 5: 运行 Controller 与领域测试并提交**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -pl agent-web -Dtest=ModelConfigurationControllerTest,ModelConfigurationServiceTest,JdbcModelConfigurationRepositoryIntegrationTest test
```

Expected: 全部通过。

Commit: `git commit -m "feat(web): expose model configuration CRUD"`

### Task 4: 前端 API 与工作区状态命令

**Files:**
- Modify: `agent-web/src/main/frontend/src/api/conversationApi.test.ts`
- Modify: `agent-web/src/main/frontend/src/api/conversationApi.ts`
- Modify: `agent-web/src/main/frontend/src/hooks/useConversationWorkspace.test.tsx`
- Modify: `agent-web/src/main/frontend/src/hooks/useConversationWorkspace.ts`

- [ ] **Step 1: 写 API 失败测试**

新增 Fetch mock 用例，分别断言以下调用：

```typescript
await updateModelProvider('provider-1', {
  displayName: '更新网关', baseUrl: 'https://new.example',
  chatCompletionsPath: '/chat',
}, fetcher)
expect(fetcher).toHaveBeenNthCalledWith(1, '/api/model-config/providers/provider-1',
  expect.objectContaining({ method: 'PUT' }))
expect(JSON.parse(String(fetcher.mock.calls[0][1]?.body))).not.toHaveProperty('apiKey')

await deleteModelEndpoint('endpoint-1', fetcher)
expect(fetcher).toHaveBeenNthCalledWith(1, '/api/model-config/endpoints/endpoint-1',
  expect.objectContaining({ method: 'DELETE' }))
```

同组覆盖 `updateModelEndpoint`、`updateModelGroup`、`deleteModelProvider` 和 `deleteModelGroup`，每个
写操作的第二次请求必须是 `GET /api/model-config`。409 响应断言抛出的
`ConversationApiError.message` 来自 `ProblemDetail.detail`。

- [ ] **Step 2: 运行 API 测试并确认失败**

Run:

```powershell
Set-Location agent-web/src/main/frontend
npm run test:run -- src/api/conversationApi.test.ts
```

Expected: 新导出函数不存在。

- [ ] **Step 3: 实现强类型命令和 API 函数**

新增精确类型：

```typescript
export interface UpdateModelProviderCommand {
  displayName: string
  baseUrl: string
  chatCompletionsPath: string
  apiKey?: string
}
export type UpdateModelEndpointCommand = Omit<CreateModelEndpointCommand, 'providerId'>
export type UpdateModelGroupCommand = CreateModelGroupCommand
```

每个 ID 通过 `nonBlankStringAt` 校验并用 `encodeURIComponent` 写入路径。更新和删除成功后统一调用
`listModelConfiguration(fetcher)`；Provider 只在 `apiKey?.trim()` 非空时把 `apiKey` 加入 JSON。

- [ ] **Step 4: 写 Hook 失败测试并实现命令透传**

在 Hook mock API 中加入六个命令，使用 `renderHook` 调用结果并断言参数与返回快照。接口和返回类型
增加 `updateModelProvider`、`updateModelEndpoint`、`updateModelGroup`、`deleteModelProvider`、
`deleteModelEndpoint`、`deleteModelGroup`。命令成功后直接用返回快照调用
`setModelConfiguration(snapshot)`，避免每次 UI 再重复发出第三次 GET。

- [ ] **Step 5: 运行前端 API/Hook 测试并提交**

Run:

```powershell
Set-Location agent-web/src/main/frontend
npm run test:run -- src/api/conversationApi.test.ts src/hooks/useConversationWorkspace.test.tsx
```

Expected: 全部通过。

Commit: `git commit -m "feat(web): add model configuration client commands"`

### Task 5: 三类资源管理界面

**Files:**
- Create: `agent-web/src/main/frontend/src/components/ModelSettingsDialog.test.tsx`
- Create: `agent-web/src/main/frontend/src/components/ModelProviderSettingsSection.tsx`
- Create: `agent-web/src/main/frontend/src/components/ModelEndpointSettingsSection.tsx`
- Create: `agent-web/src/main/frontend/src/components/ModelGroupSettingsSection.tsx`
- Modify: `agent-web/src/main/frontend/src/components/ModelSettingsDialog.tsx`
- Modify: `agent-web/src/main/frontend/src/styles.css`

- [ ] **Step 1: 写资源展示与编辑失败测试**

构造包含一个 Provider、Endpoint 和 Group 的 `UseConversationWorkspaceResult`。断言现有名称、URL、
Chat 路径、密钥掩码、模型 ID、能力、优先级、权重、启停和 Group 成员名称全部可见。点击
`编辑 Provider 主网关` 后，断言 `API Key` 为空；填写其他字段并保存后断言：

```typescript
expect(controller.updateModelProvider).toHaveBeenCalledWith('provider-1', {
  displayName: '主网关更新',
  baseUrl: 'https://new.example',
  chatCompletionsPath: '/chat',
})
```

Endpoint 测试切换 `enabled`、选择 `TOOL_CALLING`、修改 priority/weight；Group 测试按选择顺序提交
两个 Endpoint ID。测试通过可访问名称定位控件，不依赖 CSS 选择器。

- [ ] **Step 2: 写删除确认与 409 展示失败测试**

点击 `删除 Endpoint 主模型` 后先断言出现确认对话且 API 未调用；点击确认后模拟 rejected Promise，
断言 `role=alert` 显示 `Endpoint 仍被 Group 引用，请先从 Group 移除`。Provider 和 Group 分别覆盖
取消与确认路径。删除选中的 Group 后，测试 Workbench 控制层接收新快照并清除已不存在的选择。

- [ ] **Step 3: 运行组件测试并确认失败**

Run:

```powershell
Set-Location agent-web/src/main/frontend
npm run test:run -- src/components/ModelSettingsDialog.test.tsx
```

Expected: 现有对话框没有资源行、编辑按钮和删除确认。

- [ ] **Step 4: 实现 Provider 区段**

`ModelProviderSettingsSection` 接收 `providers` 和 create/update/delete 回调；使用
`Pencil`、`Trash2`、`Plus`、`Save`、`X` 图标按钮并提供 `aria-label`/`title`。编辑表单 state 精确为：

```typescript
interface ProviderFormState {
  displayName: string
  baseUrl: string
  chatCompletionsPath: string
  apiKey: string
}
```

编辑初始化时 `apiKey: ''`；提交时只有非空值才添加 `apiKey`。删除确认显示资源名，不显示 URL 或密钥。

- [ ] **Step 5: 实现 Endpoint 与 Group 区段**

Endpoint 区段以 Provider map 显示所属名称；四个 `InferenceCapability` 使用复选框，`enabled` 使用
checkbox 开关，priority 使用 `min=0`、weight 使用 `min=1` 的 number input。编辑不提供 Provider
选择器，新增才提供。

Group 区段以 Endpoint map 显示成员。成员选择用 checkbox 列表维护 `endpointIds` 数组：选中追加到
末尾，取消只移除目标 ID，保证提交顺序明确。任务类型只包含 `CODE`、`VISION`、
`QUICK_CLASSIFICATION` 三个现有值。

- [ ] **Step 6: 将 Dialog 收敛为组合与统一错误边界**

`ModelSettingsDialog` 只保留关闭按钮、`busyResource`、`error` 和三个 Section。封装
`run(resourceId, operation)`：开始时清除错误并标记 busy，成功后使用 Hook 返回的快照，不再额外调用
`reloadModelConfiguration`，失败显示 `failure.message`，finally 清除 busy。任何时候只允许一个资源写操作。

- [ ] **Step 7: 增加响应式样式并运行测试**

资源区段使用全宽分隔带和紧凑行，不嵌套卡片；最长 ID/URL 使用 `overflow-wrap:anywhere`；按钮保持稳定
尺寸；窄屏将资源摘要和命令换行。执行：

```powershell
Set-Location agent-web/src/main/frontend
npm run test:run -- src/components/ModelSettingsDialog.test.tsx src/components/Workbench.test.tsx
npm run build
```

Expected: 测试和 TypeScript/Vite 构建全部通过，且无文本溢出编译警告。

- [ ] **Step 8: 提交前端界面**

Commit: `git commit -m "feat(web): build model pool management interface"`

### Task 6: 回归、运行时一致性与真实 Docker 验收

**Files:**
- Modify only if verification finds a documented defect: files owned by Tasks 1-5
- Update: `README.md` only when the visible model management workflow is absent from current instructions

- [ ] **Step 1: 验证动态客户端更新生效**

在 `DynamicModelGroupRouteResolverTest` 增加或确认现有测试：同一 Provider runtime 的 Base URL、
Chat 路径或 API Key 改变后，旧 `LlmClient.close()` 被调用一次、factory 创建新客户端；仅 Endpoint 或
Group 字段变化时下一次 resolve 直接返回新模型顺序和启停结果。

Run: `mvn -pl agent-web -Dtest=DynamicModelGroupRouteResolverTest test`

Expected: 全部通过。

- [ ] **Step 2: 运行完整自动化门禁**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -pl agent-web test
Set-Location agent-web/src/main/frontend
npm run test:run
npm run build
Set-Location ../../../..
git diff --check
mvn clean package -DskipTests
```

Expected: Java 测试、全部前端测试、前端构建、diff 检查和 7 模块 Maven package 全部成功。

- [ ] **Step 3: 重建本地 Docker**

```powershell
docker compose -f docker-compose.local.yml --env-file .env up -d --build
docker compose -f docker-compose.local.yml --env-file .env ps
```

Expected: `agent4j-postgres` 与 `agent4j-web-local` 均为 healthy，Web 可通过
`http://localhost:8080` 访问。

- [ ] **Step 4: 使用真实 HTTP API 执行 CRUD 验收**

创建名称以 `crud-acceptance-` 加当前北京时间戳开头的独立 Provider、两个 Endpoint 和一个 Group。
依次验证：GET 可见且密钥只有掩码；Provider 不传 API Key 更新后真实模型调用仍成功；轮换为本机
`.env` 的有效 Key 后调用成功；Endpoint 调整优先级、权重和启停后路由顺序改变；Group 成员替换后
顺序正确；直接删除 Provider/Endpoint 得到 409；按 Group、Endpoint、Provider 顺序删除得到 204。
请求脚本只从进程环境读取密钥，禁止输出请求 JSON 或 Authorization 值。

- [ ] **Step 5: 核查审计日志与数据库残留**

确认 `logs/agent4j-current.log` 使用北京时间，并包含配置动作、用户 ID、资源类型和资源 UUID，不包含
API Key、Base URL、模型 ID或显示名称。查询数据库确认 `crud-acceptance-` 本轮临时资源已按正常 API
删除；不修改或删除任何现有 `acceptance-` 前缀记录。

- [ ] **Step 6: 检查工作树并提交验收修正**

```powershell
git status --short
git diff --check
```

若 Task 6 产生 README 或回归修正，使用精确受影响文件提交：

```powershell
git commit -m "fix(web): complete model pool acceptance"
```

若没有文件变化，则保留干净工作树，不创建空提交。
