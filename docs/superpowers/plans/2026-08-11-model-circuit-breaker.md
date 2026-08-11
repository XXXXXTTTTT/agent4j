# Model Circuit Breaker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将模型网关熔断从 Resilience4j 默认值改为适配 Agent 低调用量的强类型配置，使主端点连续失败后及时跳过并降低真实对话延迟。

**Architecture:** `ModelCircuitBreakerProperties` 绑定 `agent.llm.circuit-breaker`，由 `ModelGatewayConfiguration` 转换为不可变 `CircuitBreakerConfig`，再为每个 TaskType/模型端点创建独立 CircuitBreaker。Core `ModelRouter` 保持现有 fallback 与 OPEN 状态语义。

**Tech Stack:** Java 21 records, Spring Boot 3.3 Configuration Properties, Resilience4j 2.4.0, JUnit 5, AssertJ, Spring MockRestServiceServer

---

## File Map

- Create: `agent-web/src/main/java/com/agent/web/config/ModelCircuitBreakerProperties.java`
- Create: `agent-web/src/test/java/com/agent/web/config/ModelCircuitBreakerPropertiesTest.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/ModelGatewayConfiguration.java`
- Modify: `agent-web/src/test/java/com/agent/web/config/ModelGatewayConfigurationTest.java`
- Modify: `agent-core/src/test/java/com/agent/core/llm/ModelRouterTest.java`
- Modify: `agent-web/src/main/resources/application.properties`
- Modify: `.env.example`
- Modify: `README.md`
- Modify: `docs/ENGINEERING_PITFALLS.md`

### Task 1: Strongly typed circuit-breaker properties

**Files:**
- Create: `agent-web/src/test/java/com/agent/web/config/ModelCircuitBreakerPropertiesTest.java`
- Create: `agent-web/src/main/java/com/agent/web/config/ModelCircuitBreakerProperties.java`

- [ ] **Step 1: Write the failing defaults test**

```java
@Test
void usesAgentSizedDefaults() {
    ModelCircuitBreakerProperties properties =
            new ModelCircuitBreakerProperties(null, null, null, null, null);

    assertThat(properties.failureRateThreshold()).isEqualTo(100.0f);
    assertThat(properties.minimumNumberOfCalls()).isEqualTo(2);
    assertThat(properties.slidingWindowSize()).isEqualTo(2);
    assertThat(properties.waitDurationInOpenState())
            .isEqualTo(Duration.ofSeconds(30));
    assertThat(properties.permittedNumberOfCallsInHalfOpenState())
            .isEqualTo(1);
}
```

- [ ] **Step 2: Write failing validation tests**

Test failure-rate thresholds below 0 and above 100, non-positive call/window/half-open counts, negative open duration, and `minimumNumberOfCalls > slidingWindowSize`. Every assertion must check the exact configuration key in the exception message.

- [ ] **Step 3: Run tests and verify RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -pl agent-web -Dtest=ModelCircuitBreakerPropertiesTest test
```

Expected: compilation failure because `ModelCircuitBreakerProperties` does not exist.

- [ ] **Step 4: Implement the minimal immutable record**

```java
@ConfigurationProperties(prefix = "agent.llm.circuit-breaker")
public record ModelCircuitBreakerProperties(
        Float failureRateThreshold,
        Integer minimumNumberOfCalls,
        Integer slidingWindowSize,
        Duration waitDurationInOpenState,
        Integer permittedNumberOfCallsInHalfOpenState) {

    public ModelCircuitBreakerProperties {
        failureRateThreshold =
                failureRateThreshold == null ? 100.0f : failureRateThreshold;
        minimumNumberOfCalls =
                minimumNumberOfCalls == null ? 2 : minimumNumberOfCalls;
        slidingWindowSize =
                slidingWindowSize == null ? 2 : slidingWindowSize;
        waitDurationInOpenState = waitDurationInOpenState == null
                ? Duration.ofSeconds(30)
                : waitDurationInOpenState;
        permittedNumberOfCallsInHalfOpenState =
                permittedNumberOfCallsInHalfOpenState == null
                        ? 1
                        : permittedNumberOfCallsInHalfOpenState;
        validate(
                failureRateThreshold,
                minimumNumberOfCalls,
                slidingWindowSize,
                waitDurationInOpenState,
                permittedNumberOfCallsInHalfOpenState);
    }
}
```

- [ ] **Step 5: Run the test and verify GREEN**

```powershell
mvn -pl agent-web -Dtest=ModelCircuitBreakerPropertiesTest test
```

Expected: all methods in the selected test class pass.

### Task 2: Production configuration assembly

**Files:**
- Modify: `agent-web/src/test/java/com/agent/web/config/ModelGatewayConfigurationTest.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/ModelGatewayConfiguration.java`

- [ ] **Step 1: Write a failing assembly test**

Construct properties with `73.0f`, `4`, `6`, `Duration.ofSeconds(17)`, `2`; invoke `modelRouter`; obtain the first CODE endpoint breaker; assert all five values through its `CircuitBreakerConfig`.

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
mvn -pl agent-web -Dtest=ModelGatewayConfigurationTest test
```

Expected: compilation failure because `modelRouter` has no circuit-breaker properties parameter.

- [ ] **Step 3: Wire the exact properties**

```java
@EnableConfigurationProperties({
        ModelGatewayProperties.class,
        ModelCircuitBreakerProperties.class
})
```

```java
CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.custom()
        .failureRateThreshold(circuitBreakerProperties.failureRateThreshold())
        .minimumNumberOfCalls(circuitBreakerProperties.minimumNumberOfCalls())
        .slidingWindowSize(circuitBreakerProperties.slidingWindowSize())
        .waitDurationInOpenState(
                circuitBreakerProperties.waitDurationInOpenState())
        .permittedNumberOfCallsInHalfOpenState(
                circuitBreakerProperties.permittedNumberOfCallsInHalfOpenState())
        .build();
```

Pass this immutable config into the existing `endpoint` factory. Keep one CircuitBreaker instance per endpoint.

- [ ] **Step 4: Run Web config tests and verify GREEN**

```powershell
mvn -pl agent-web -Dtest=ModelGatewayConfigurationTest,ModelGatewayPropertiesTest,ModelCircuitBreakerPropertiesTest test
```

Expected: all selected tests pass.

### Task 3: Two-failure fallback behavior

**Files:**
- Modify: `agent-core/src/test/java/com/agent/core/llm/ModelRouterTest.java`

- [ ] **Step 1: Write the regression test**

Create a primary endpoint with the production-sized breaker. Configure two primary HTTP failures and three fallback successes. Invoke VISION three times. Assert calls 1 and 2 return fallback, call 3 also returns fallback, the primary mock server has consumed exactly two requests, and the primary breaker state is OPEN.

- [ ] **Step 2: Verify the test fails before its fixture exists**

```powershell
mvn -pl agent-core -Dtest=ModelRouterTest#opensCircuitAfterTwoFailuresAndSkipsPrimaryOnThirdCall test
```

Expected: compilation failure because the production-sized endpoint fixture overload does not exist.

- [ ] **Step 3: Add only the required breaker fixture and verify GREEN**

```java
CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        .failureRateThreshold(100.0f)
        .minimumNumberOfCalls(2)
        .slidingWindowSize(2)
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .permittedNumberOfCallsInHalfOpenState(1)
        .build();
```

Run the focused method, then all `ModelRouterTest` methods.

### Task 4: Environment mapping and documentation

**Files:**
- Modify: `agent-web/src/main/resources/application.properties`
- Modify: `.env.example`
- Modify: `README.md`
- Modify: `docs/ENGINEERING_PITFALLS.md`

- [ ] **Step 1: Add exact environment mappings**

```properties
agent.llm.circuit-breaker.failure-rate-threshold=\${AGENT_LLM_CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD:100}
agent.llm.circuit-breaker.minimum-number-of-calls=\${AGENT_LLM_CIRCUIT_BREAKER_MINIMUM_NUMBER_OF_CALLS:2}
agent.llm.circuit-breaker.sliding-window-size=\${AGENT_LLM_CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE:2}
agent.llm.circuit-breaker.wait-duration-in-open-state=\${AGENT_LLM_CIRCUIT_BREAKER_WAIT_DURATION_IN_OPEN_STATE:30s}
agent.llm.circuit-breaker.permitted-number-of-calls-in-half-open-state=\${AGENT_LLM_CIRCUIT_BREAKER_PERMITTED_NUMBER_OF_CALLS_IN_HALF_OPEN_STATE:1}
```

Add the same environment variable names and values to `.env.example`.

- [ ] **Step 2: Document runtime semantics**

README must state that two consecutive failures open the breaker for that endpoint, OPEN skips HTTP and uses fallback, and one probe is allowed after 30 seconds. The engineering retrospective must record the observed main-model HTTP 500 sequence, old default `minimumNumberOfCalls=100`, new defaults, and real EDD validation method without message bodies or secrets.

### Task 5: Verification, real EDD and integration

**Files:** all files above

- [ ] **Step 1: Run focused verification**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -pl agent-core,agent-web -am -Dtest=ModelRouterTest,ModelGatewayConfigurationTest,ModelGatewayPropertiesTest,ModelCircuitBreakerPropertiesTest test
```

- [ ] **Step 2: Run complete verification**

```powershell
mvn clean verify
git diff --check
git status --short
```

Expected: Maven exits 0, diff check emits no output, and status lists only planned files.

- [ ] **Step 3: Rebuild Docker and run real multi-turn EDD**

```powershell
docker compose -f docker-compose.local.yml --env-file .env up -d --build
docker compose -f docker-compose.local.yml --env-file .env ps
```

Use the existing real conversation EDD entrypoint. Capture conversation ID, run IDs, terminal status, elapsed time, two primary HTTP failures, the following OPEN skip, and fallback success from persisted logs.

- [ ] **Step 4: Commit and merge**

Review `git status` before staging. Exclude `.env`, `logs/`, `target/`, screenshots, Docker data, and unrelated user changes. Commit with:

```text
fix(llm): tune circuit breaker for real traffic
```

Merge the branch into `master` only after fresh verification, then remove the isolated worktree without touching the root log directory.
