# GUI Agent Browser Action Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有 Playwright 浏览器能力扩展为按 Run 隔离、受 Tool Registry 治理、可引用 DOM/截图证据的 GUI Agent，并接入生产 Planner 路由和真实视觉 EDD。

**Architecture:** `BrowserSessionRegistry` 按 `runId` 管理独占 `BrowserAutomation`；`BrowserToolDefinitions` 将导航、点击、填充、滚动和证据采集注册为严格 Schema 工具。`GuiAgentNode` 通过 `HarnessToolExecutor` 执行工具，在每轮观察后让 Vision 模型返回严格动作 JSON，只有引用已采集证据的 `done` 才能写入 `final_response`。生产图仅把 `BROWSER_OPERATION` 路由到 GUI 节点，代码和混合任务保持原链路。

**Tech Stack:** Java 21 records, Playwright Java, existing `ToolRegistry`/`HarnessToolExecutor`, Jackson strict JSON parsing, JUnit 5, real local HTTP page and Chromium EDD.

---

### Task 1: Browser evidence protocol and Playwright operations

**Files:** Create `agent-sandbox/src/main/java/com/agent/sandbox/browser/BrowserEvidenceSelector.java` and `BrowserEvidence.java`; modify `BrowserAutomation.java` and `PlaywrightBrowserService.java`; test in `agent-sandbox/src/test/java/com/agent/sandbox/browser/BrowserEvidenceSelectorTest.java` and `PlaywrightBrowserServiceTest.java`.

- [ ] **Step 1: Write the failing tests.** Assert `BrowserEvidenceSelector.page()` accepts the exact page sentinel, locator selectors reject blank and overlong values, and the browser fake exposes `fill`, `scroll`, and `capture` with the exact timeout.
- [ ] **Step 2: Run red:** `mvn -pl agent-sandbox -Dtest=BrowserEvidenceSelectorTest,PlaywrightBrowserServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` with Java 21. Expected failure: missing records and interface methods.
- [ ] **Step 3: Implement the immutable records.** `BrowserEvidenceSelector` exposes `page()` and `locator(String)`, stores either exact `page` or a nonblank selector capped at 2,048 code points; `BrowserEvidence` copies `BrowserScreenshot` and validates nonblank DOM, selector, and final URL.
- [ ] **Step 4: Implement Playwright methods on the existing single virtual-thread executor.** `fill` delegates to `Locator.fill`, `scroll` delegates to `page.mouse().wheel(0, deltaY)`, and `capture` returns page content plus either full-page PNG or locator `outerHTML` and PNG. Every API receives the supplied positive timeout and future failures preserve causes.
- [ ] **Step 5: Run green and commit:** `mvn -pl agent-sandbox -Dtest=BrowserEvidenceSelectorTest,PlaywrightBrowserServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`; commit `feat(sandbox): add browser evidence operations` with only the listed files.

### Task 2: Per-Run browser sessions and governed browser tools

**Files:** create `agent-core/src/main/java/com/agent/core/gui/BrowserSessionRegistry.java` and `agent-core/src/main/java/com/agent/core/tool/builtin/BrowserToolDefinitions.java`; test `agent-core/src/test/java/com/agent/core/gui/BrowserSessionRegistryTest.java` and `agent-core/src/test/java/com/agent/core/tool/builtin/BrowserToolDefinitionsTest.java`.

- [ ] **Step 1: Write failing tests.** Cover one session per `runId`, duplicate open rejection, unknown Run failure, close-on-error, five exact tool names, `additionalProperties=false`, required `BROWSER` capability, URL/selector/delta bounds, and complete ToolResult error stacks.
- [ ] **Step 2: Run red:** `mvn -pl agent-core -am -Dtest=BrowserSessionRegistryTest,BrowserToolDefinitionsTest -Dsurefire.failIfNoSpecifiedTests=false test`; expected failure is missing session and tool factory types.
- [ ] **Step 3: Implement `BrowserSessionRegistry`.** Inject `Supplier<BrowserAutomation>`, use a concurrent `UUID -> BrowserAutomation` map, reject duplicate opens, return exact sessions from `require`, and close all sessions while attaching later failures as suppressed exceptions.
- [ ] **Step 4: Implement strict `BrowserToolDefinitions`.** Register `browser.navigate`, `browser.click`, `browser.fill`, `browser.scroll`, and `browser.evidence`; each handler obtains the session from `ToolInvocationContext.runId`, awaits its future, returns JSON objects, and never calls Playwright outside the session. URL is absolute HTTP/HTTPS, selector is bounded nonblank text, and deltaY is bounded integer.
- [ ] **Step 5: Run green and commit:** same focused Maven command; commit `feat(tool): govern per-run browser actions`.

### Task 3: Strict GUI action protocol and execution node

**Files:** create `agent-core/src/main/java/com/agent/core/gui/BrowserActionDecision.java` and `agent-core/src/main/java/com/agent/core/nodes/GuiAgentNode.java`; test `agent-core/src/test/java/com/agent/core/gui/BrowserActionDecisionTest.java` and `agent-core/src/test/java/com/agent/core/nodes/GuiAgentNodeTest.java`.

- [ ] **Step 1: Write failing protocol tests.** Assert exact JSON fields for `click`, `fill`, `scroll`, and `done`; reject Markdown fences, unknown fields, wrong types, missing action parameters, invalid scroll bounds, and `done` references absent from collected evidence.
- [ ] **Step 2: Run red:** `mvn -pl agent-core -am -Dtest=BrowserActionDecisionTest,GuiAgentNodeTest -Dsurefire.failIfNoSpecifiedTests=false test`; expected failure is missing protocol and node classes.
- [ ] **Step 3: Implement `BrowserActionDecision` as an immutable strict record.** Use action values `CLICK`, `FILL`, `SCROLL`, `DONE`; enforce selector/value/deltaY/summary/evidenceRefs ownership per action without altering identifier spelling.
- [ ] **Step 4: Implement `GuiAgentNode`.** Require state `reviewer.url` and `planner.task`, open the Run session, navigate once, capture evidence, call `TaskType.VISION`, parse strict action JSON, execute the matching browser ToolCall through `HarnessToolExecutor`, append action/evidence JSON, and stop at `maxSteps`. Success writes `gui.summary`, `gui.evidence`, `gui.finalUrl`, `gui.dom`, `gui.screenshotDataUrl`, `gui.model`, `gui.request`, `gui.response`, `final_response`, and trace `gui`; every failure writes the full stack to `gui.error` and trace `gui`; `finally` closes the session.
- [ ] **Step 5: Run green and commit:** focused Maven command; commit `feat(gui): add evidence-driven browser agent node`.

### Task 4: Production registry and Planner graph routing

**Files:** modify `agent-web/src/main/java/com/agent/web/config/ProductionGraphConfiguration.java`; modify `ProductionAgentProperties.java` only when an exact GUI step property is absent; modify `agent-web/src/test/java/com/agent/web/config/ProductionCodeAgentIntegrationTest.java`; create `ProductionGuiAgentIntegrationTest.java` in the same package.

- [ ] **Step 1: Extend integration tests first.** Require all five browser tools in the production registry, the session registry bean, pure `BROWSER_OPERATION -> gui`, and unchanged CODE/MIXED -> coder routing.
- [ ] **Step 2: Run red:** `mvn -pl agent-web -am -Dtest=ProductionCodeAgentIntegrationTest,ProductionGuiAgentIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dfrontend.skip=true test`; expected failure is missing wiring.
- [ ] **Step 3: Register session manager and browser tools.** Keep the existing Reviewer `BrowserAutomation` bean, add a `BrowserSessionRegistry` factory bean, and register `CodePatchTool` plus `BrowserToolDefinitions` in the same production registry.
- [ ] **Step 4: Add GUI node and graph edge.** Preserve every existing overloaded `codeAgentGraph` method through delegation. Map exact `planner.taskKind == BROWSER_OPERATION` to `gui`, add `gui -> END`, and leave Coder/Ops/Reviewer repair edges unchanged.
- [ ] **Step 5: Run green and commit:** `mvn -pl agent-web -am -Dtest=ProductionCodeAgentIntegrationTest,ProductionGuiAgentIntegrationTest,ProductionGraphConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false -Dfrontend.skip=true test`; commit `feat(web): route browser tasks to gui agent`.

### Task 5: Real visual workflow EDD and engineering pitfall record

**Files:** create `agent-eval/src/test/java/com/agent/eval/GuiAgentWorkflowEddTest.java`; modify `docs/ENGINEERING_PITFALLS.md`.

- [ ] **Step 1: Write the EDD scenario.** Start a temporary JDK `HttpServer` serving a form whose click changes `#result`; use deterministic Vision responses for fill, click, and done; assert real DOM mutation, PNG bytes, evidence references, per-action Tool Registry audit, and no Coder/Ops calls.
- [ ] **Step 2: Run red:** `mvn -pl agent-eval -am -Dtest=GuiAgentWorkflowEddTest -Dsurefire.failIfNoSpecifiedTests=false -Dfrontend.skip=true test`; expected failure is missing EDD production types or unregistered browser tools.
- [ ] **Step 3: Connect deterministic responses to the real graph.** Keep network calls local and use the real Playwright Chromium binary; only environments without the browser use a JUnit assumption skip.
- [ ] **Step 4: Append the 7B pitfall to `docs/ENGINEERING_PITFALLS.md`.** Record shared Page cross-run contamination, CSS evidence scope mismatch, operation timeout cancellation, and evidence-less success prevention with concrete class/test references.
- [ ] **Step 5: Run green and commit:** same focused EDD command; commit `test(eval): add gui agent evidence workflow edd`.

### Task 5B: Live GUI model EDD

**Files:** create `agent-eval/src/test/java/com/agent/eval/LiveGuiAgentWorkflowEddTest.java`; update the
7B design and engineering pitfall record.

- [ ] **Step 1: Write the opt-in Live EDD first.** Require the exact existing LLM environment variables,
  start the same local form, and wire the real endpoint to the real GUI graph and Chromium. Default-disabled
  execution must use a JUnit assumption; enabled execution must never install a Mock HTTP server.
- [ ] **Step 2: Run the real endpoint red/green gate.** Load `D:\agent4j\.env` into the Maven process and run
  `LiveGuiAgentWorkflowEddTest`; require non-skipped execution, real provider monitoring, final DOM mutation,
  valid evidence hashes, Tool Registry audit, and a strict LIVE report without prompt/response bodies.
- [ ] **Step 3: Keep deterministic and Live reports distinct.** The existing GUI EDD remains the repeatable
  contract test; the Live EDD is the model-quality and end-to-end protocol test.
- [ ] **Step 4: Preserve DOM fallback for non-multimodal gateways.** Add a failing `GuiAgentNodeTest` in which
  the first multimodal `TaskType.VISION` request fails and the second text-only request completes the strict
  action protocol. Implement the smallest fallback, retain the first failure as suppressed when both calls fail,
  then rerun the enabled Live EDD and require a non-skipped real API result.
- [ ] **Step 5: Force structured browser decisions.** After the Live model proves prompt-only JSON unstable,
  require one `browser_action` function with strict JSON Schema and exact forced `toolChoice`; reject text-only,
  wrong-name and multiple ToolCall responses, parse only function arguments, and rerun deterministic plus Live EDD.
- [ ] **Step 6: Ground completion in global evidence.** Reproduce locator-only evidence hiding an out-of-scope
  DOM mutation. After each successful action, retain the requested locator evidence and append page evidence;
  require `done.summary` to occur in at least one referenced evidence DOM before writing `final_response`.

### Task 5C: Merge-review hardening

**Files:** modify `BrowserSessionRegistry`, `PlaywrightBrowserService`, `GuiAgentNode`,
`ProductionGraphConfiguration` and their focused tests.

- [ ] **Step 1: Reproduce each review finding with a failing test.** Cover cross-Run session reuse,
  open/close serialization, retryable close failure, production ToolAuditSink delivery, critical Hook error state,
  and sub-millisecond Playwright timeout rejection.
- [ ] **Step 2: Implement the smallest fixes.** Share one lifecycle lock, remove sessions only after successful
  close, inject durable production audit logging, preserve `gui.error`, and pass timeout to every supported
  Playwright API.
- [ ] **Step 3: Run all focused tests and repeat the full Task 6 gate before merge.**

### Task 6: Full verification and merge

- [ ] **Step 1: Run the complete Java 21 gate without skipping frontend tests:** `mvn -pl agent-core,agent-web,agent-eval -am test`.
- [ ] **Step 2: Run clean Java 21 package:** `mvn clean package '-DskipTests' '-Dfrontend.skip=true'`.
- [ ] **Step 3: Run `git diff --check`, inspect status and forbidden paths, then fast-forward merge `feat/guide-seventh-part-7b` into `master`.**
- [ ] **Step 4: On merged `master`, rerun `ProductionGuiAgentIntegrationTest`, `GuiAgentWorkflowEddTest`, and `BrowserToolDefinitionsTest` with `'-Dfrontend.skip=true'`.**
- [ ] **Step 5: Remove only `D:\agent4j\.worktrees\guide-seventh-part-7b`, prune worktree metadata, delete the merged feature branch, and verify `master` is clean.**
