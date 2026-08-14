# Agent4J 生产多 Agent 编排设计

## 现状

`agent-core` 已实现 `com.agent.core.multiagent`：精确 Agent 目录、FORK/FRESH 上下文、最小状态投影、虚拟线程子运行、深度/次数/超时限制、输出键合并和独立事件。现有生产 `code-agent` 图仍以 Planner、Coder、Ops、Reviewer 单图串行执行，前端无法选择协作模式，模型组仅能选择当前对话的单一组。

## 目标

将已有 handoff 内核接入生产工作流，提供以下可实际运行的模式：

- `SERIAL_DEVELOPMENT`：Planner → Coder → Ops → Reviewer。仅 Coder 可以写工作区。
- `PARALLEL_RESEARCH`：两个只读 Researcher 并行收集代码、测试与知识证据；Coordinator 汇总后交给单一 Coder，再进入 Ops 与 Reviewer。
- `REVIEW_LOOP`：Coder 与 Ops 后由 FRESH Reviewer 独立审查 Diff、测试和 Trace；未通过时将结构化反馈交回 Coder，直到预算耗尽或通过。

每个模式必须有可选模型组：协调、研究、实施、验证。用户可在会话提交前显式选择模式与角色模型组；为空时只使用会话模型组作为全部角色的明确回退。不存在、停用或不兼容模型组时请求必须被拒绝。

## 工作区治理

- Researcher 不拥有任何写状态键，不注册补丁、终端写入或浏览器副作用工具。
- Coder 是每次 Run 唯一拥有代码写入和 `coder.*` 输出的角色。
- Ops 只执行已有受治理命令目录中的命令，维持现有审批中断。
- Reviewer 使用 `FRESH`，只读取经 Coordinator 允许的 Diff、Ops 与审查输入键；不读取完整会话正文、不修改工作区。
- 多个子 Agent 绝不并行写同一工作树；所有合并继续经过 `AgentStateProjector`。

## 运行协议与可观测性

每个模式运行创建明确的主 Run 和子 Run。对子运行记录 `parentRunId`、`childRunId`、`fromAgent`、`toAgent`、`mode`、`role`、`modelGroupId`、生命周期事件和耗时；不记录未脱敏的 handoff 内容。现有 Trace WebSocket 和会话审计需要展示模式与子 Agent 状态。

模型组路由由现有动态模型组配置提供。协调角色用于高判断任务，研究和实施角色可使用用户配置的成本较低组；角色不会绕过现有 Model Router、熔断或审计。

## API 与前端

会话提交请求增加可选 `orchestrationMode` 与 `roleModelGroups`。`roleModelGroups` 只接受 `COORDINATOR`、`RESEARCHER`、`IMPLEMENTER`、`VERIFIER` 四个精确键。前端会话输入区新增模式选择器；选择并行研究或评审闭环时出现角色模型组选择器，并从已加载模型组读取真实选项。

Agent 会话与 Trace 以子 Agent 卡片呈现模式、角色、模型组、状态和交接方向；不渲染模型的隐藏推理文本。

## 失败与预算

所有模式使用既有总执行预算，并配置最大 handoff 深度与次数。任一 Researcher 失败时 Coordinator 可在同一预算内继续使用其他已完成只读证据；Coder、Ops 或 Reviewer 失败则按模式定义进入现有失败/修复路径。子 Run 超时、状态越权、模型组解析失败和嵌套审批均以明确错误写入主 Run，不伪装为完成。

## 验收

- 单元与集成测试证明三个模式的节点顺序、并行只读、唯一写入者和 FRESH 审查上下文。
- API 测试证明非法模式、未知角色键和不可用模型组被拒绝。
- 前端测试证明模式与角色组选择被精确提交并在运行卡片中显示。
- EGG 覆盖模式合同、handoff 状态权限、Trace 和审批边界。
- EDD 使用已配置真实模型运行一个小型代码任务，保存主/子 Run、模型请求审计、终端输出、Diff、测试结果和最终审查证据。
