# 模型网关熔断参数设计

## 背景与根因

真实会话 EDD 显示，主模型 `gpt-5.6-luna` 返回 HTTP 500 后，每次请求仍会先访问主端点，再回退到 `gpt-5.4-mini`。生产装配使用 Resilience4j `CircuitBreakerConfig.ofDefaults()`，其 `minimumNumberOfCalls=100`；Agent 每轮只有 1-2 次同端点调用，因此熔断器无法及时开路。Core 的 `ModelRouter` 已支持在 OPEN 状态跳过端点，问题仅存在于生产参数。

## 目标

新增前缀为 `agent.llm.circuit-breaker` 的强类型配置，在少量真实请求中及时开路，并保留失败率保护、半开探测和按 TaskType/端点隔离的 fallback 链。

## 精确配置

| 配置键 | 默认值 | 语义 |
| --- | --- | --- |
| `failure-rate-threshold` | `100` | 窗口内全部调用失败时开路 |
| `minimum-number-of-calls` | `2` | 两次调用即可计算失败率 |
| `sliding-window-size` | `2` | 按最近两次调用计算 |
| `wait-duration-in-open-state` | `30s` | OPEN 保持 30 秒 |
| `permitted-number-of-calls-in-half-open-state` | `1` | HALF_OPEN 只放行一次探测 |

约束为：失败率阈值在 0 到 100 之间；调用数、窗口大小与半开许可数必须大于 0；等待时间至少为 1ms；`minimum-number-of-calls` 不能大于 `sliding-window-size`。非法值在应用启动绑定阶段直接失败，不静默使用框架默认值。

## 运行语义

1. 同一端点连续两次失败后进入 OPEN。
2. OPEN 期间 `ModelRouter` 不向该端点发送 HTTP 请求，直接尝试同一 TaskType 的下一个端点。
3. 30 秒后只允许一次 HALF_OPEN 探测；成功回到 CLOSED，失败回到 OPEN。
4. 一次失败后一次成功不会开路。
5. 每个 TaskType/端点继续拥有独立 CircuitBreaker，不共享运行状态。

## 变更边界

- `agent-web` 负责属性绑定和生产 CircuitBreakerConfig 装配。
- `agent-core` 不修改路由算法，仅增加“两次失败后第三次跳过主端点”的行为回归测试。
- 更新 `application.properties`、`.env.example`、`README.md` 和 `docs/ENGINEERING_PITFALLS.md`。
- 不提交 `.env`、API Key、日志、Docker 数据或构建产物。

## 验证门禁

先执行属性默认值/边界测试、生产装配参数测试和 Core 路由行为测试，再执行 JDK 21 `mvn clean verify`。重建本地 Compose 后运行真实多轮 EDD，从北京时间应用日志和审计日志确认：前两次主端点失败被记录，OPEN 期间不再出现主端点 HTTP 请求，fallback 直接接管，并记录响应耗时。
