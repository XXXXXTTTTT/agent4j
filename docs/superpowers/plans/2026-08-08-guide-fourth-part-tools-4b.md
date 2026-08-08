# 第四篇 4B：MCP 工具适配层 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 MCP JSON-RPC 远程工具安全转换为现有 `ToolRegistry` 定义，保留严格协议、权限、审批、超时和审计语义。

**Architecture:** `McpTransport` 只负责传输；`McpClient` 负责握手、发现和调用协议；`McpToolRegistryAdapter` 负责名称空间和 handler 映射。远程调用永远通过 4A `ToolRegistry`，不新增旁路执行入口。

**Tech Stack:** Java 21 records、Jackson、Spring `RestClient`、Java `HttpServer`、JUnit 5、AssertJ。

---

### Task 1: JSON-RPC 不可变协议与异常

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/tool/mcp/McpJsonRpcRequest.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/mcp/McpJsonRpcResponse.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/mcp/McpProtocolException.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/mcp/McpTransportException.java`
- Create: `agent-core/src/test/java/com/agent/core/tool/mcp/McpJsonRpcProtocolTest.java`

- [x] **Step 1: Write failing protocol tests**: assert request serialization uses exact `jsonrpc/id/method/params`, notification omits `id`, response rejects duplicate fields, trailing JSON, simultaneous `result/error`, missing error `code/message`, and mismatched IDs.
- [x] **Step 2: Run red test**:

  ```powershell
  mvn -pl agent-core -am "-Dtest=McpJsonRpcProtocolTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
  ```

  Expected: compilation failure because MCP protocol types do not exist.
- [x] **Step 3: Implement records and strict parser**: freeze `JsonNode` values with deep copies; use an `ObjectReader` configured with duplicate detection and trailing-token failure; preserve protocol causes.
- [x] **Step 4: Run green test** with the same command: 7 tests passed.
- [x] **Step 5: Commit** `feat(tool): define mcp json-rpc protocol`.

### Task 2: MCP transport port and HTTP implementation

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/tool/mcp/McpTransport.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/mcp/McpHttpTransport.java`
- Create: `agent-core/src/test/java/com/agent/core/tool/mcp/McpHttpTransportTest.java`

- [ ] **Step 1: Write failing local `HttpServer` tests**: assert POST headers/body, non-2xx mapping, empty/non-JSON response rejection, SSE content-type rejection, timeout cause, and logs without params or API key.
- [ ] **Step 2: Run red test** with `mvn -pl agent-core -am -Dtest=McpHttpTransportTest -Dsurefire.failIfNoSpecifiedTests=false test`.
- [ ] **Step 3: Implement `McpTransport` and `McpHttpTransport`**: inject `RestClient`, `ObjectMapper`, endpoint and positive timeout; send strict JSON-RPC body; parse one response; log only endpoint/method/requestId/status/duration.
- [ ] **Step 4: Run green test and `mvn -pl agent-core -am test`**.
- [ ] **Step 5: Commit** `feat(tool): add mcp http transport`.

### Task 3: MCP client handshake and tool protocol

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/tool/mcp/McpRemoteTool.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/mcp/McpToolCallResult.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/mcp/McpClient.java`
- Create: `agent-core/src/test/java/com/agent/core/tool/mcp/McpClientTest.java`

- [ ] **Step 1: Write failing fake-transport tests**: verify initialize request then initialized notification, single initialization, listTools exact fields and duplicate rejection, uninitialized rejection, callTool object arguments, and remote `isError` preservation.
- [ ] **Step 2: Run red test** with `mvn -pl agent-core -am -Dtest=McpClientTest -Dsurefire.failIfNoSpecifiedTests=false test`.
- [ ] **Step 3: Implement client**: serialize exact methods `initialize`, `notifications/initialized`, `tools/list`, `tools/call`; validate response IDs and result shapes; expose immutable tool list and raw content.
- [ ] **Step 4: Run green test and core regression**.
- [ ] **Step 5: Commit** `feat(tool): implement mcp client discovery`.

### Task 4: Registry adapter and governance regression

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/tool/mcp/McpToolRegistryAdapter.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/mcp/McpRemoteToolException.java`
- Create: `agent-core/src/test/java/com/agent/core/tool/mcp/McpToolRegistryAdapterTest.java`

- [ ] **Step 1: Write failing adapter tests**: namespace names exactly, invalid names rejected, remote handler invoked once, Registry schema/permission/approval/timeout still gate execution, remote errors retain JSON and cause.
- [ ] **Step 2: Run red test** with `mvn -pl agent-core -am -Dtest=McpToolRegistryAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`.
- [ ] **Step 3: Implement adapter**: discover after initialization, build `ToolDefinition` with remote schema and injected risk/capabilities/timeout, close over exact remote name, and register atomically.
- [ ] **Step 4: Run green test and tool regression suite**.
- [ ] **Step 5: Commit** `feat(tool): govern mcp tools through registry`.

### Task 5: Deterministic MCP EDD and final gate

**Files:**
- Create: `agent-eval/src/test/java/com/agent/eval/McpToolAdapterEddTest.java`
- Modify: `docs/ENGINEERING_PITFALLS.md`
- Modify: `docs/superpowers/plans/2026-08-08-guide-fourth-part-tools-4b.md`

- [ ] **Step 1: Write EDD**: fixed fake MCP transport and report `agent-eval/target/edd/mcp-tool-adapter-edd.json` with exact fields `taskId/status/audited/durationMs/errorType/passed`; cover initialize, discovery, success, schema denied, capability denied, approval denied and remote failure.
- [ ] **Step 2: Run EDD and inspect report**.
- [ ] **Step 3: Update engineering pitfalls** with MCP protocol drift, remote schema trust, namespace collision, transport timeout and governance bypass cases.
- [ ] **Step 4: Run final gates**:

  ```powershell
  mvn -pl agent-core,agent-eval -am test
  mvn package "-DskipTests" "-Dfrontend.skip=true"
  git diff --check
  ```

- [ ] **Step 5: Commit** `test(eval): verify mcp tool adapter`.
