# 运行时模型池接入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让数据库模型组配置成为生产 `ModelRouter` 的真实运行时路由。

**Architecture:** Core 通过可选 `ModelGroupRouteResolver` 延迟解析模型组；Web 解析器按当前运行状态中的用户 ID读取数据库配置，使用 Provider 的精确请求路径构造 OpenAI 兼容端点，并缓存可关闭的动态客户端。

**Tech Stack:** Java 21、Spring Boot 3.3、Spring JDBC、PostgreSQL、Apache HttpClient、Resilience4j、JUnit 5。

---

### Task 1: Core 动态组解析接口

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/llm/ModelGroupRouteResolver.java`
- Modify: `agent-core/src/main/java/com/agent/core/llm/ModelRouter.java`
- Test: `agent-core/src/test/java/com/agent/core/llm/ModelRouterTest.java`

- [ ] 写失败测试：显式组 ID调用 resolver；resolver 返回端点后完成请求；resolver 无结果时抛组级错误；无组 ID仍使用默认路由。
- [ ] 运行 `mvn -pl agent-core -Dtest=ModelRouterTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认新测试因构造器和解析器缺失失败。
- [ ] 添加可选 resolver 构造器和 `endpointsFor` 动态分支，保留现有三个构造器行为。
- [ ] 重新运行目标测试并确认通过。

### Task 2: Provider 精确路径与运行时读取端口

**Files:**
- Create: `agent-web/src/main/resources/db/migration/V6__add_model_provider_chat_path.sql`
- Modify: `agent-web/src/main/java/com/agent/web/model/ModelProviderRecord.java`
- Modify: `agent-web/src/main/java/com/agent/web/model/ModelConfigurationRepository.java`
- Modify: `agent-web/src/main/java/com/agent/web/model/ModelConfigurationService.java`
- Modify: `agent-web/src/main/java/com/agent/web/controller/ModelConfigurationController.java`
- Modify: `agent-web/src/main/java/com/agent/web/persistence/JdbcModelConfigurationRepository.java`
- Modify: `agent-web/src/main/frontend/src/components/ModelSettingsDialog.tsx`

- [ ] 写失败测试：创建 Provider 保存精确路径；空路径使用 `/v1/chat/completions`；运行时读取端口只返回当前用户 Provider 的密钥和路径。
- [ ] 运行 Web 目标测试确认失败。
- [ ] 增加字段、迁移、JDBC 查询/插入和 API 请求字段，展示记录继续只返回掩码。
- [ ] 运行目标测试确认通过。

### Task 3: Web 动态解析器与 Spring 装配

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/model/DynamicModelGroupRouteResolver.java`
- Create: `agent-web/src/test/java/com/agent/web/model/DynamicModelGroupRouteResolverTest.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/ModelGatewayConfiguration.java`

- [ ] 写失败测试：用户只能解析自己的组；组内端点按优先级、权重排序；Provider 路径进入请求 URL；密钥不进入展示结果。
- [ ] 运行 Web 目标测试确认失败。
- [ ] 实现按状态用户 ID读取配置、缓存动态 `LlmClient`、复用 HTTP 客户端和熔断/准入组件；关闭时释放客户端。
- [ ] 将 resolver 注入 `ModelRouter`，保留 `.env` 默认路由。
- [ ] 运行目标测试确认通过。

### Task 4: 全量验证

- [ ] 运行 `mvn -pl agent-core -Dtest=ModelRouterTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] 运行 `mvn -pl agent-web -am -Dtest=ModelConfigurationServiceTest,ModelConfigurationControllerTest,DynamicModelGroupRouteResolverTest -Dsurefire.failIfNoSpecifiedTests=false -Dfrontend.skip=true test`。
- [ ] 运行 `git diff --check`。
- [ ] 运行 `mvn clean package -DskipTests`。
