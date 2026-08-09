# Docker Product Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 保证两套 Docker Compose 启动后始终注册 Web 工作台依赖的生产会话与工作区接口。

**Architecture:** Compose 是产品启动边界，因此在 `agent-web` 服务中用字面量强制开启生产模式。通过读取 Compose 文件的 JUnit 契约测试防止 `.env` 再次关闭产品 Controller。

**Tech Stack:** Docker Compose、Spring Boot 3.3、JUnit 5、AssertJ、Java 21

---

### Task 1: 锁定 Docker 产品模式

**Files:**
- Create: `agent-web/src/test/java/com/agent/web/config/DockerComposeProductModeTest.java`
- Modify: `docker-compose.local.yml`
- Modify: `docker-compose.yml`
- Modify: `README.md`

- [ ] **Step 1: Write the failing test**

创建测试，读取 Maven 根目录下两份 Compose 文件，断言包含 `AGENT_PRODUCTION_ENABLED: "true"`，且不包含 `${AGENT_PRODUCTION_ENABLED:-true}`。

- [ ] **Step 2: Run test to verify it fails**

Run: `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'; mvn -pl agent-web "-Dfrontend.skip=true" "-Dtest=DockerComposeProductModeTest" test`

Expected: FAIL，因为当前两份 Compose 仍使用 `${AGENT_PRODUCTION_ENABLED:-true}`。

- [ ] **Step 3: Write minimal implementation**

将两份 Compose 的 `agent-web.environment.AGENT_PRODUCTION_ENABLED` 精确改为字面量 `"true"`。README 说明 Docker 产品启动会覆盖 `.env` 中的宿主直跑默认值。

- [ ] **Step 4: Run test to verify it passes**

Run: `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'; mvn -pl agent-web "-Dfrontend.skip=true" "-Dtest=DockerComposeProductModeTest" test`

Expected: PASS。

- [ ] **Step 5: Verify the real startup path**

Run: `docker compose -f docker-compose.local.yml --env-file .env up -d --build`

Expected: `agent4j-postgres` 与 `agent4j-web-local` healthy，`GET http://localhost:8080/api/workspaces` 返回 200，两个容器重启次数均为 0。

- [ ] **Step 6: Commit**

```text
fix(docker): enable product workbench endpoints
```
