# 串联工作流 Skill 设计

## 目标

支持 Claude Code 风格的 `/skill-a /skill-b 任务`：连续工作流 Skill 共享尾部任务参数，渲染后的模板合并为一次会话提交和一个 Agent Run。

## 边界

- 只有消息开头、连续出现且均为 `WORKFLOW_SKILL` 的已注册精确名称可组成链。
- 最多六个 Skill；第七个已注册工作流 Skill 返回稳定 `INVALID`。
- 系统指令、未知名称和非工作流命令不会被拼接或降级为普通聊天。
- 每个 Skill 都使用相同的尾部参数，并独立执行参数数量和权限校验。
- 任一 Skill 校验失败时不调用工作流桥接器，不创建半成品会话轮次。

## 架构

新增小型 `WorkflowPromptCommandHandler` 接口，提供纯模板渲染和所用的 `WorkflowCommandBridge`。既有 `WorkflowCommandHandler` 实现该接口；`CommandDispatcher` 只依赖接口，不依赖内置命令名或 Markdown 来源。

Dispatcher 解析首命令后检查连续参数中的 `/name`。若构成链，则对每一个定义进行精确查找、参数和权限校验，确认所有 Handler 使用同一个桥接器后渲染模板。模板按调用顺序以两个换行拼接，并由第一个调用的 bridge 一次提交。返回结果保留首命令名和现有 `runId`/`turnId` 数据。

## 验证

核心单元测试断言 `/plan /review "修复登录"` 仅调用一次 bridge，拼接 `plan` 和 `review` 两段模板且两者得到相同参数；权限拒绝或超过六个 Skill 时 bridge 调用次数为零。Web 工作流桥接测试继续确认一次提交产生一个持久化会话轮次。
