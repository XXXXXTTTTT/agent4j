# 4C Skills Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在 `ToolRegistry` 之上实现不可变、可发现、可版本化且只读的 Skill 编排目录，并以精确触发和渐进 Prompt 协议提供给后续 Agent 节点。

**Architecture:** `SkillDefinition` 只保存元数据、触发文本、有序工具名和策略文本。`SkillCatalog` 在构造时从 `ToolRegistry` 读取工具描述与 Schema，构造失败即整体失败，之后只读并可并发调用。目录输出 `SkillPromptContext`：默认只含摘要，trigger 命中或显式点名后才含完整工具元数据与策略；所有实际工具调用仍由现有 Registry 治理。

**Tech Stack:** Java 21 records、Jackson `JsonNode`、现有 `ToolRegistry`/4B MCP adapter、JUnit 5、AssertJ、agent-eval 确定性 EDD。

---

## 文件结构

- Create: `agent-core/src/main/java/com/agent/core/skill/SkillRegistrationException.java` — 目录构造失败异常。
- Create: `agent-core/src/main/java/com/agent/core/skill/SkillNotFoundException.java` — 显式点名失败异常。
- Create: `agent-core/src/main/java/com/agent/core/skill/SkillDefinition.java` — 输入定义与全部字段校验。
- Create: `agent-core/src/main/java/com/agent/core/skill/SkillSummary.java` — 默认发现的最小摘要。
- Create: `agent-core/src/main/java/com/agent/core/skill/SkillToolMetadata.java` — 触发后暴露的脱敏工具元数据。
- Create: `agent-core/src/main/java/com/agent/core/skill/ActivatedSkill.java` — 单个激活结果。
- Create: `agent-core/src/main/java/com/agent/core/skill/SkillPromptContext.java` — discovery/activation 分区与指纹。
- Create: `agent-core/src/main/java/com/agent/core/skill/SkillCatalog.java` — 原子构造、精确匹配与只读查询。
- Create: `agent-core/src/test/java/com/agent/core/skill/SkillDefinitionTest.java` — 定义边界与不可变性。
- Create: `agent-core/src/test/java/com/agent/core/skill/SkillCatalogTest.java` — 目录注册、渐进披露和指纹。
- Create: `agent-core/src/test/java/com/agent/core/skill/SkillTriggerMatchingTest.java` — 触发精确语义。
- Create: `agent-core/src/test/java/com/agent/core/skill/SkillCatalogConcurrencyTest.java` — 并发读与 Schema 隔离。
- Create: `agent-core/src/test/java/com/agent/core/skill/SkillMcpIntegrationTest.java` — 4B 发现与 Registry 治理闭环。
- Create: `agent-eval/src/test/java/com/agent/eval/SkillCatalogEddTest.java` — 七条确定性 Skill EDD 路线。
- Modify: `docs/ENGINEERING_PITFALLS.md` — 追加 4C 触发、版本、渐进披露与 Registry 旁路复盘。

### Task 1: Skill records 与异常协议

**Files:**
- Create: 上述 `SkillRegistrationException.java`、`SkillNotFoundException.java`、`SkillDefinition.java`、`SkillSummary.java`、`SkillToolMetadata.java`、`ActivatedSkill.java`、`SkillPromptContext.java`。
- Test: `agent-core/src/test/java/com/agent/core/skill/SkillDefinitionTest.java`。

- [ ] **Step 1: Write the failing test**

```java
@Test
void rejectsLeadingZeroVersionAndDuplicateToolNames() {
    assertThatThrownBy(() -> definition("01.2.3", List.of("weather", "weather")))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void preservesExactTriggerTextAndCopiesLists() {
    List<String> triggers = new ArrayList<>(List.of("下雨"));
    SkillDefinition definition = definition("1.0.0", triggers);
    triggers.add("天气");
    assertThat(definition.triggers()).containsExactly("下雨");
    assertThatThrownBy(() -> definition.triggers().add("x"))
            .isInstanceOf(UnsupportedOperationException.class);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl agent-core -Dtest=SkillDefinitionTest test`

Expected: FAIL because `com.agent.core.skill` types do not exist.

- [ ] **Step 3: Write minimal implementation**

`SkillDefinition` 的紧凑构造器必须使用精确版本正则
`(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)`，对描述和策略按
`codePointCount` 限长；`List.copyOf` 冻结声明顺序；名称和工具名复用 4A 的相同名称规则，
不做大小写或格式修补。异常构造器保存相关 Skill/工具文本和 cause。四个输出 record 对集合
使用 `List.copyOf`，`SkillToolMetadata` 对 Schema 进行 `deepCopy`，`SkillPromptContext`
对摘要、激活项和文本做非 null 校验，fingerprint 必须匹配 `[0-9a-f]{64}`。

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl agent-core -Dtest=SkillDefinitionTest test`

Expected: PASS，且没有 compiler warning 导致失败。

- [ ] **Step 5: Commit**

```text
feat(skill): define immutable skill domain records
```

### Task 2: SkillCatalog 原子注册与渐进 Prompt

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/skill/SkillCatalog.java`。
- Test: `agent-core/src/test/java/com/agent/core/skill/SkillCatalogTest.java`、`SkillTriggerMatchingTest.java`。

- [ ] **Step 1: Write the failing tests**

```java
@Test
void discoveryOnlyContainsSummariesUntilExactTriggerMatches() {
    SkillCatalog catalog = catalog(skill("weather", List.of("下雨"), List.of("weather.lookup")));
    SkillPromptContext context = catalog.resolve("今天吃什么", Set.of());
    assertThat(context.availableSkills()).extracting(SkillSummary::name).containsExactly("weather");
    assertThat(context.activationSection()).isEmpty();
    assertThat(catalog.resolve("明天下雨吗", Set.of()).activatedSkills())
            .extracting(ActivatedSkill::name).containsExactly("weather");
}

@Test
void rejectsCrossSkillTriggerCollisionAndUnknownToolAtomically() {
    assertThatThrownBy(() -> catalog(skill("a", List.of("天气"), List.of("weather.lookup")),
            skill("b", List.of("天气"), List.of("weather.lookup"))))
            .isInstanceOf(SkillRegistrationException.class);
    assertThatThrownBy(() -> catalog(skill("a", List.of(), List.of("missing.tool"))))
            .isInstanceOf(SkillRegistrationException.class);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl agent-core -Dtest=SkillCatalogTest,SkillTriggerMatchingTest test`

Expected: FAIL because `SkillCatalog` and prompt assembly do not exist.

- [ ] **Step 3: Write minimal implementation**

`SkillCatalog` 构造器按定义列表执行：校验同名、重复精确 trigger；对每个 tool name 调用
`ToolRegistry.find(exactName)`，从返回定义复制 `name/description/inputSchema` 为内部
`SkillToolMetadata`；任一失败都抛 `SkillRegistrationException`，不得发布部分 Map。内部保存
`Map<String, RegisteredSkill>` 的 `Map.copyOf` 快照和按名称排序的摘要。

实现以下精确 API：

```java
public List<SkillSummary> list();
public Optional<SkillDefinition> find(String name);
public SkillPromptContext resolve(String userInput, Set<String> explicitlyRequestedNames);
```

`resolve` 不 trim 输入，使用 `String.contains` 做原始 trigger 匹配；显式名称逐项 exact lookup，
未知名称抛 `SkillNotFoundException`；匹配结果按名称排序并去重。`discoverySection` 固定每行
`- name@version: description`；`activationSection` 固定输出 Skill 标识、有序工具的名称/描述/
canonical JSON Schema 以及策略片段。使用注入的 `ObjectMapper` 对 JSON 进行稳定字段排序后，
用 UTF-8 SHA-256 计算两个分区拼接文本的 fingerprint。该类不提供任何执行方法。

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl agent-core -Dtest=SkillCatalogTest,SkillTriggerMatchingTest test`

Expected: PASS，覆盖摘要不泄露策略、显式发现、原始大小写/Unicode、多个 Skill 命中和稳定指纹。

- [ ] **Step 5: Commit**

```text
feat(skill): add deterministic progressive skill catalog
```

### Task 3: 并发隔离与 4B MCP/Registry 集成

**Files:**
- Test: `agent-core/src/test/java/com/agent/core/skill/SkillCatalogConcurrencyTest.java`、`SkillMcpIntegrationTest.java`。

- [ ] **Step 1: Write the failing tests**

```java
@Test
void concurrentReadOperationsReturnImmutableEquivalentContexts() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<SkillPromptContext>> futures = IntStream.range(0, 32)
                .mapToObj(index -> executor.submit(() -> catalog.resolve("下雨", Set.of())))
                .toList();
        assertThat(futures).allSatisfy(future -> assertThat(future.get().fingerprint())
                .isEqualTo(futures.getFirst().get().fingerprint()));
    }
}

@Test
void mcpToolIsOnlyExposedAfterActivationAndStillRequiresRegistryApproval() {
    // fake MCP transport follows McpToolAdapterEddTest initialize/tools/list/tools/call contract
    SkillPromptContext context = catalogBuiltFromMcp().resolve("调用远程回显", Set.of());
    assertThat(context.activatedSkills().getFirst().tools()).extracting(SkillToolMetadata::name)
            .containsExactly("remote.echo");
    ToolResult result = registry.execute(call("remote.echo"), unapprovedHighRiskContext());
    assertThat(result.status()).isEqualTo(ToolResultStatus.APPROVAL_REQUIRED);
    assertThat(transport.callCalls()).isZero();
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl agent-core -Dtest=SkillCatalogConcurrencyTest,SkillMcpIntegrationTest test`

Expected: FAIL until the catalog snapshot and MCP adapter fixture are wired into tests.

- [ ] **Step 3: Write minimal integration implementation**

使用 `McpToolRegistryAdapter.registerDiscoveredTools` 完成 fake transport 的握手和注册，再把
同一个 `DefaultToolRegistry` 注入 `SkillCatalog`；Skill 测试只通过 `ToolRegistry.execute` 发起
调用。验证 `SkillCatalog` 内部 Schema accessor deep copy，调用方修改返回树不会污染后续 Prompt。
不修改 4B 生产代码，不新增 MCP 旁路。

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl agent-core -Dtest=SkillCatalogConcurrencyTest,SkillMcpIntegrationTest test`

Expected: PASS，32 个虚拟线程得到相同指纹，HIGH 风险未批准时远程调用次数为 0，批准后才允许一次调用。

- [ ] **Step 5: Commit**

```text
test(skill): verify concurrent and mcp governed skills
```

### Task 4: Skill EDD 与工程复盘

**Files:**
- Create: `agent-eval/src/test/java/com/agent/eval/SkillCatalogEddTest.java`。
- Modify: `docs/ENGINEERING_PITFALLS.md`，在第四篇 4B 记录之后追加“第四篇 4C”。

- [ ] **Step 1: Write the failing EDD test**

固定报告路径 `target/edd/skill-catalog-edd.json`，并固定字段集合：
`taskId/status/activatedSkills/exposedTools/fingerprint/passed`。七个任务 ID 精确为
`skill.discovery`、`skill.trigger`、`skill.explicit`、`skill.unmatched`、`skill.collision`、
`skill.unknown-tool`、`skill.registry-governance`。每项必须断言 `passed=true`，摘要场景的
`exposedTools` 为空，冲突/未知工具场景保存异常类型而不保存策略正文。

- [ ] **Step 2: Run EDD to verify it fails**

Run: `mvn -pl agent-eval -Dtest=SkillCatalogEddTest test`

Expected: FAIL at compilation because `SkillCatalogEddTest` and its fixed scenario helper have not yet been created.

- [ ] **Step 3: Write minimal EDD and review entry**

采用 `McpToolAdapterEddTest` 的 `EddResult`/Jackson 写报告模式；EDD 只保存工具名、状态、
指纹和异常类型，不写完整 Prompt、Schema 或参数。复盘条目按“问题现象 → 根因分析 →
解决方案/代码级实现 → 证据”结构记录：原始触发文本被错误归一化、同名多版本产生隐式选择、
Skill 直接调用 MCP 绕过 Registry、默认 Prompt 泄露全部工具 Schema。

- [ ] **Step 4: Run EDD to verify it passes**

Run: `mvn -pl agent-eval -Dtest=SkillCatalogEddTest test`

Expected: PASS，生成 `agent-eval/target/edd/skill-catalog-edd.json`，七项字段精确且全部通过。

- [ ] **Step 5: Commit**

```text
test(eval): add skill catalog edd
docs(knowledge): record skill orchestration pitfalls
```

### Task 5: 里程碑验证与交付

**Files:**
- No production files beyond Tasks 1–4。

- [ ] **Step 1: Run focused module tests**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -pl agent-core -Dtest='com.agent.core.skill.*' test
mvn -pl agent-eval -Dtest=SkillCatalogEddTest test
```

Expected: all Skill unit, integration and EDD tests pass; report exists only under ignored `target/`。

- [ ] **Step 2: Run module and full package verification**

```powershell
mvn -pl agent-core,agent-eval -am test
mvn clean package -DskipTests -Dfrontend.skip=true
git -c safe.directory='D:/agent4j/.worktrees/guide-third-part-knowledge' diff --check
```

Expected: no failures/errors, package succeeds, diff check is empty. Docker, PostgreSQL and external LLM
assumptions keep their existing explicit test gates.

- [ ] **Step 3: Verify repository hygiene**

Run: `git -c safe.directory='D:/agent4j/.worktrees/guide-third-part-knowledge' status --short`

Expected: only intended source/tests/docs are staged; `target/`, logs, `.env` and model output are absent.

- [ ] **Step 4: Confirm the milestone commit history**

Run: `git -c safe.directory='D:/agent4j/.worktrees/guide-third-part-knowledge' log -5 --oneline`

Expected: the history contains the scoped `feat(skill)` implementation commit and the scoped `test(eval)`/
`docs(knowledge)` commits from Task 4; no unrelated files are present.
