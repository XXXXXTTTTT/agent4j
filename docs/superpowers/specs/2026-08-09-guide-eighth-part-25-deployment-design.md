# 第八篇第 25 章 Deployment 工程化设计

## 目标

为 Agent4J 的 Docker 部署补齐可编排健康探针、优雅关闭、资源边界、数据库迁移门禁和备份恢复演练，并用确定性部署 EDD 校验两套 Compose 与运行镜像契约。

## 范围与边界

- `agent-web` 增加 Spring Boot Actuator，仅暴露 liveness/readiness 健康端点；不改变 Agent 节点、图引擎或 LLM 业务逻辑。
- readiness 包含 Spring `readinessState` 与 PostgreSQL `db` 检查，Flyway 迁移完成后才允许接收流量；liveness 只反映进程存活状态。
- 开启 Spring 优雅关闭，容器入口使用 `exec` 让 SIGTERM 直接到达 Java 进程。
- 两套 Compose 都声明 Agent 服务的 CPU、内存和 PID 上限，并为 Agent 服务增加 HTTP readiness healthcheck；PostgreSQL 保留既有健康检查。
- 运行镜像安装 curl 作为健康检查依赖，生产和本地 Dockerfile 保持 Java 21。
- 添加不含凭据的 PostgreSQL 备份/恢复演练文档和确定性 Deployment EDD；不自动执行破坏性恢复。

## 公开契约

| 项目 | 精确值 |
| --- | --- |
| liveness | `GET /actuator/health/liveness` |
| readiness | `GET /actuator/health/readiness` |
| 优雅关闭等待 | `spring.lifecycle.timeout-per-shutdown-phase=30s` |
| Agent 容器端口 | `8080` |
| 默认资源上限 | `cpus=2.0`、`mem_limit=2g`、`pids_limit=512` |
| readiness 探针 | `curl -fsS http://localhost:8080/actuator/health/readiness` |

## 验证策略

1. `ActuatorHealthConfigurationTest` 验证端点映射、健康组和关闭配置。
2. `DeploymentEddTest` 读取两套 Compose、两个 Dockerfile、应用配置和恢复文档，精确断言 Java 21、探针、资源边界、`exec`、Flyway 与备份命令存在。
3. Maven 模块测试、完整打包和 Docker Compose 配置解析均必须通过；Docker 可用时执行真实 Agent/PostgreSQL readiness smoke test，Docker 不可用时只跳过该 smoke test，不跳过确定性 EDD。
