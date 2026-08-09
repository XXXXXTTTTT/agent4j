# 第七篇 7B：GUI Agent 页面动作与证据闭环设计

## 1. 目标与教程对照

教程 `chapters/ch15-gui-agent.html` 的 GUI Agent 核心循环是“截图/页面状态 → 视觉模型决策 → 点击、填充或滚动 → 等待页面更新 → 再次观察”，并要求最大步数、错误恢复、操作白名单和审计记录。项目第七篇总设计将该能力列为第七篇 / 22 的增量范围：页面动作工具协议、证据选择器、操作级超时和视觉任务 EDD。

7B 只覆盖 Web Playwright 场景。桌面鼠标键盘、远程桌面和 Computer Use API 不在本里程碑范围；现有 `ReviewerNode` 继续负责代码链的最终质量裁决。

## 2. 方案选择

### 方案 A：扩展 ReviewerNode

把动作循环直接加入 ReviewerNode，改动文件少，但会混合“执行页面动作”和“审查结论”两种职责，并让现有 Coder→Ops→Reviewer 回归承担 GUI 状态机语义，拒绝采用。

### 方案 B：独立 GuiAgentNode 与受治理浏览器工具（采用）

新增 `GuiAgentNode` 作为纯浏览器任务执行节点；所有导航、点击、填充、滚动和证据采集都通过 `ToolRegistry`，工具调用携带现有 `ToolInvocationContext`，因此共用 Schema 校验、BROWSER 能力授权、超时和审计。每个 Run 由 `BrowserSessionRegistry` 创建一个独占 `BrowserAutomation`，节点结束时关闭会话，避免多个 Run 共享同一个 Playwright Page。

Planner 已经写入 `planner.taskKind` 和 `planner.requiredCapabilities`。生产图仅将精确的 `BROWSER_OPERATION` 路由到 `gui`；CODE、COMMAND 和 MIXED 继续进入 Coder→Ops→Reviewer。这样不会改变现有代码修复链。

### 方案 C：通过第五篇 Handoff 运行 GUI 子 Agent

使用独立子运行和 Handoff 表达 GUI Agent，隔离性更强，但需要同时扩展父子运行状态所有权、恢复和 Web 查询，超过第七篇 / 22 的单一增量，留待后续综合实战集成。

## 3. 公共协议

### 3.1 浏览器会话

`BrowserSessionRegistry` 按 `runId` 保存 `BrowserAutomation`，提供 `open(UUID)`, `require(UUID)` 和 `close(UUID)`。注册表只允许通过构造器注入的工厂创建会话；未知 Run 调用工具时直接失败。关闭按 Page、Context、Browser、Playwright 的依赖逆序执行，并保留完整异常堆栈。

`BrowserAutomation` 增加：

- `fill(String selector, String value, Duration timeout)`
- `scroll(int deltaY, Duration timeout)`
- `capture(BrowserEvidenceSelector selector, Duration timeout)`

现有导航、点击、DOM、截图和关闭方法保持兼容。所有 Playwright 操作仍提交到每个服务自己的单一虚拟线程。

### 3.2 受治理工具

新增 `BrowserToolDefinitions`，注册以下精确名称：

| 工具 | 参数 | 风险 | 结果 |
| --- | --- | --- | --- |
| `browser.navigate` | `url` | MEDIUM | 最终 URL 与 HTTP 状态 |
| `browser.click` | `selector` | MEDIUM | 执行完成与当前 URL |
| `browser.fill` | `selector`, `value` | MEDIUM | 执行完成与当前 URL |
| `browser.scroll` | `deltaY` | LOW | 执行完成与当前 URL |
| `browser.evidence` | `selector` | LOW | URL、DOM、PNG data URL、证据哈希 |

每个 Schema 都使用 `additionalProperties=false`，字符串有非空和长度上限，`deltaY` 有绝对值上限，URL 仅允许绝对 HTTP/HTTPS。工具只声明 `RequiredCapability.BROWSER`；执行通过会话注册表按 `runId` 取到当前 Page，不允许模型绕过 Registry 直接调用 Playwright。

### 3.3 证据选择器

`BrowserEvidenceSelector` 的 `selector` 精确使用 `page` 或 CSS/Playwright locator 字符串。`page` 表示当前页面完整证据，其他值表示对指定元素采集 `outerHTML` 与 PNG。`browser.evidence` 返回 `BrowserEvidence`，其中包含最终 URL、选择器、DOM、截图 data URL、DOM SHA-256 和截图 SHA-256。

## 4. GuiAgentNode 与状态

`GuiAgentNode` 使用 `TaskType.VISION` 调用 `ModelRouter`，严格解析完整 JSON 动作：

```json
{
  "action": "click|fill|scroll|done",
  "selector": "",
  "value": "",
  "deltaY": 0,
  "evidenceSelector": "page",
  "reason": "",
  "summary": "",
  "evidenceRefs": []
}
```

字段集合必须精确。`click`/`fill` 必须有 selector，`fill` 必须有 value，`scroll` 必须有非零 deltaY，`done` 必须有非空 summary 和至少一个已采集的 evidenceRef；其他动作的 summary 与 evidenceRefs 必须为空。Markdown fence、未知字段、错误类型和未引用证据都写入完整错误堆栈并结束当前节点。

节点使用以下精确状态键：

- `gui.url`, `gui.goal`, `gui.step`, `gui.actions`
- `gui.evidence`, `gui.finalUrl`, `gui.dom`, `gui.screenshotDataUrl`
- `gui.summary`, `gui.model`, `gui.request`, `gui.response`, `gui.error`
- 成功完成时同时写入 `final_response`

每轮先确保目标 URL 已导航，再调用 `browser.evidence` 形成当前观察；模型动作通过 `HarnessToolExecutor` 触发 Harness BEFORE/AFTER/FAILURE 事件；动作成功后再次采集证据并把证据 ID 放入下一轮上下文。操作失败保留工具错误栈，允许模型在剩余步数内重规划；达到 `maxSteps` 时写入 `gui.error`，不会无限循环。

## 5. 生产图路由

`ProductionGraphConfiguration` 注册浏览器工具和会话注册表，并在图中增加 `gui` 节点。Planner 的 `planner.taskKind=BROWSER_OPERATION` 路由到 `gui`，GUI 节点完成后到 `END`。其他路线保持：

```text
planner(chat/knowledge) -> END
planner(agent + BROWSER_OPERATION) -> gui -> END
planner(agent + CODE/COMMAND/MIXED) -> coder -> ops -> reviewer -> repair/END
```

Code Agent Profile 继续声明 `BrowserAutomation` 作为 Reviewer 能力；GUI 工具通过 Tool Registry 额外暴露 `BROWSER` 能力，不新增任意用户可编辑图配置。

## 6. 错误、超时与安全边界

- 工具注册表的 definition timeout 是硬上限；Playwright 每个 API 同时接收同一个操作级 `Duration`，超时取消内部 Future。
- 浏览器服务拒绝非绝对 HTTP/HTTPS URL；GUI 节点对初始 URL 使用现有 `ReviewerUrlValidator`。
- 未注册 Run、缺失 BROWSER 能力、Schema 违规、审批拒绝、Playwright 异常和模型协议错误都保留完整堆栈，并写入 `gui.error` 或 ToolResult。
- 工具审计记录参数 SHA-256、风险、状态和耗时；截图内容只进入 Run 证据状态，不写入模型路由之外的日志。
- 会话在节点成功、失败、预算耗尽和线程中断路径均关闭；关闭异常作为原异常 suppressed exception 保存。

## 7. 测试门禁

### 单元测试

- `BrowserEvidenceSelectorTest`：page/locator 选择器、空值、长度和非法 selector。
- `BrowserToolDefinitionsTest`：五个精确名称、Schema 严格性、能力授权、参数边界、未知 Run 和错误堆栈。
- `BrowserSessionRegistryTest`：每个 Run 独占会话、重复 open、关闭和清理失败。
- `GuiAgentNodeTest`：严格动作 JSON、动作字段约束、证据引用、步数上限、工具失败恢复和 `final_response`。
- `PlaywrightBrowserServiceTest`：真实 fill、scroll、locator evidence 与每个操作 timeout。

### 真实视觉 EDD

`agent-eval/src/test/java/com/agent/eval/GuiAgentWorkflowEddTest.java` 启动临时本地 HTTP 页面，使用真实 Playwright Chromium 和确定性 Vision 模型响应执行：navigate → evidence(page) → fill → evidence(#result) → click → evidence(#result) → done。报告固定包含：

```text
taskId/status/steps/toolCalls/evidenceRefs/finalUrl/domSha256/screenshotSha256/passed
```

EDD 必须断言页面最终 DOM 状态、至少一张真实 PNG、每个动作均有 Tool Registry 审计、最终 summary 引用已存在证据 ID，且未调用 Coder/Ops。无 Playwright 浏览器的环境使用 JUnit assumption 明确跳过；当前环境必须实际执行。

## 8. 不在本里程碑范围

不实现桌面坐标操作、远程桌面、文件上传、支付/发布类动作、跨域导航、第五篇 Handoff 子运行和第八篇安全红队任务集。这些能力在相应篇章单独设计，不能通过 7B 的工具参数偷偷开放。
