# Docker 产品工作台启动契约设计

## 问题与证据

本地 Compose 启动后，前端请求 `GET /api/workspaces` 返回 500。TRACE 路由映射证明 `WorkspaceController` 和 `ConversationController` 没有注册；同一数据库账号执行工作区 SQL 正常。

`.env.example` 中 `AGENT_PRODUCTION_ENABLED=false` 被复制到 `.env` 后，通过 Compose 插值覆盖 `docker-compose.local.yml` 中的默认 `true`。Spring 因此按 sample 模式启动，而打包的 React 工作台仍依赖生产会话接口，形成前后端启动模式不一致。

临时将解析后的 `AGENT_PRODUCTION_ENABLED` 改为 `true` 后，同一镜像和数据卷中的 `/api/workspaces` 返回 HTTP 200，确认根因。

## 设计

`docker-compose.local.yml` 与 `docker-compose.yml` 都是完整 Web 产品的启动入口，因此两者在 `agent-web.environment` 中明确设置 `AGENT_PRODUCTION_ENABLED: "true"`，不允许 `.env` 关闭产品 Controller。

`.env.example` 继续保留 `AGENT_PRODUCTION_ENABLED=false`，供宿主机直接启动、纯 sample 测试等非 Compose 场景使用。README 明确说明 Compose 会覆盖该值。

## 回归验证

新增配置契约测试，读取两份 Compose 文件并断言产品模式为字面量 `"true"`，同时拒绝旧的可插值表达式。随后使用 `.env` 中仍为 `false` 的真实启动配置重建容器，验证：

- PostgreSQL 与 `agent-web` 均为 healthy；
- `GET /api/workspaces` 返回 200；
- 返回默认工作区；
- 容器重启次数为 0。

## 范围

本修复不修改 Agent 图、模型路由、数据库数据和前端业务逻辑，也不删除任何数据卷。
