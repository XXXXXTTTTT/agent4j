import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import type { Actor, Conversation, ConversationTurn, RunView, TraceEvent, Workspace } from '../api/contracts'
import type { UseConversationWorkspaceResult } from '../hooks/useConversationWorkspace'
import type { UseRunWorkbenchResult } from '../hooks/useRunWorkbench'
import { Workbench } from './Workbench'

vi.mock('../monaco/MonacoEditors', () => ({
  DiffEditor: ({ original, modified }: { original: string; modified: string }) => (
    <div data-testid="monaco-diff" data-original={original} data-modified={modified} />
  ),
  Editor: ({ value }: { value: string }) => <pre data-testid="monaco-dom">{value}</pre>,
}))

const RUN_ID = '3ba24ffc-e536-48ab-9bb4-19442c609ebc'

function runView(overrides: Partial<RunView> = {}): RunView {
  return {
    runId: RUN_ID,
    version: 2,
    graphId: 'product-workbench-flow',
    status: 'COMPLETED',
    state: {
      messages: [],
      variables: {},
      trace: ['coder', 'ops', 'reviewer'],
    },
    nextNode: null,
    interruptRequest: null,
    approvalDecision: null,
    approvalReason: null,
    error: null,
    createdAt: '2026-08-03T00:00:00Z',
    ...overrides,
  }
}

function controller(
  overrides: Partial<UseRunWorkbenchResult> = {},
): UseRunWorkbenchResult {
  return {
    run: null,
    history: [],
    traceEvents: [],
    connectionState: { trace: null, terminal: null },
    error: null,
    start: vi.fn(async () => undefined),
    startTask: vi.fn(async () => undefined),
    followRun: vi.fn(async () => undefined),
    clearRun: vi.fn(),
    reload: vi.fn(async () => undefined),
    decide: vi.fn(async () => undefined),
    ...overrides,
  }
}

function conversationController(
  overrides: Partial<UseConversationWorkspaceResult> = {},
): UseConversationWorkspaceResult {
  return {
    identity: { userId: 'user-1', displayName: 'Alice' } satisfies Actor,
    workspaces: [{
      workspaceId: 'ws-1', ownerUserId: 'user-1', displayName: 'Agent4J', workspacePath: 'D:/agent4j', repositoryId: 'agent4j', permission: 'OWNER', createdAt: '2026-08-07T01:00:00Z', updatedAt: '2026-08-07T01:00:00Z',
    } satisfies Workspace],
    activeWorkspace: {
      workspaceId: 'ws-1', ownerUserId: 'user-1', displayName: 'Agent4J', workspacePath: 'D:/agent4j', repositoryId: 'agent4j', permission: 'OWNER', createdAt: '2026-08-07T01:00:00Z', updatedAt: '2026-08-07T01:00:00Z',
    } satisfies Workspace,
    conversations: [{ conversationId: 'conv-1', workspaceId: 'ws-1', createdBy: 'user-1', title: '模型咨询', status: 'ACTIVE', createdAt: '2026-08-07T01:00:00Z', updatedAt: '2026-08-07T01:00:00Z' } satisfies Conversation],
    activeConversation: { conversationId: 'conv-1', workspaceId: 'ws-1', createdBy: 'user-1', title: '模型咨询', status: 'ACTIVE', createdAt: '2026-08-07T01:00:00Z', updatedAt: '2026-08-07T01:00:00Z' } satisfies Conversation,
    turns: [{ turnId: 'turn-1', conversationId: 'conv-1', turnIndex: 0, userContent: '你是什么模型', assistantContent: '我是 AI。', runId: 'run-1', status: 'COMPLETED', error: null, createdAt: '2026-08-07T01:00:00Z', completedAt: '2026-08-07T01:00:05Z' } satisfies ConversationTurn],
    searchQuery: '', loading: false, submitting: false, error: null,
    selectWorkspace: vi.fn(async () => undefined), selectConversation: vi.fn(async () => undefined), search: vi.fn(async () => undefined), createWorkspace: vi.fn(async () => undefined), browseWorkspaceDirectories: vi.fn(async (path: string) => ({ currentPath: path, parentPath: null, entries: [] })), importWorkspace: vi.fn(async () => undefined), createConversation: vi.fn(async () => undefined), submit: vi.fn(async (): Promise<ConversationTurn> => ({ turnId: 'turn-2', conversationId: 'conv-1', turnIndex: 1, userContent: '继续', assistantContent: null, runId: 'run-2', status: 'PENDING', error: null, createdAt: '2026-08-07T01:00:06Z', completedAt: null })), archive: vi.fn(async () => undefined), reload: vi.fn(async () => undefined),
    ...overrides,
  }
}

function traceEvents(): TraceEvent[] {
  const common = {
    eventId: 'c890db6f-322d-42ec-960b-62d5782a6b75',
    runId: RUN_ID,
    checkpointVersion: 2,
    occurredAt: '2026-08-03T00:00:01Z',
  }
  return [
    { ...common, type: 'NODE_STARTED', nodeName: 'coder' },
    { ...common, type: 'NODE_COMPLETED', nodeName: 'coder', nextNode: 'ops' },
    {
      ...common,
      type: 'INTERRUPTED',
      nodeName: 'ops',
      request: {
        interruptId: '1a51de42-e150-40cc-93b1-f4c09d58ece4',
        nodeName: 'ops',
        reason: '需要审批',
        details: { 'ops.command': 'mvn test' },
      },
    },
    { ...common, type: 'APPROVED', nodeName: 'ops', reason: '已检查' },
    { ...common, type: 'REJECTED', nodeName: 'ops', reason: '拒绝' },
    { ...common, type: 'FAILED', error: 'java.lang.IllegalStateException' },
    { ...common, type: 'COMPLETED' },
  ]
}

describe('Workbench', () => {
  it('展示持久化会话侧栏、历史轮次并将新轮次接入 Run 证据', async () => {
    const user = userEvent.setup()
    const conversations = conversationController()
    const runs = controller({ followRun: vi.fn(async () => undefined) })
    render(<Workbench controller={runs} conversation={conversations} onTerminalReady={() => undefined} />)

    expect(screen.getByLabelText('会话与工作区')).toBeVisible()
    expect(screen.getByRole('option', { name: 'Agent4J' })).toBeVisible()
    expect(screen.getByText('你是什么模型')).toBeVisible()
    const input = screen.getByRole('textbox', { name: '发送消息' })
    await user.type(input, '继续说明')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(conversations.submit).toHaveBeenCalledWith('继续说明')
    expect(runs.followRun).toHaveBeenCalledWith('run-2')
  })

  it('从工作区侧栏创建并切换工作区', async () => {
    const user = userEvent.setup()
    const createWorkspace = vi.fn(async () => undefined)
    render(
      <Workbench
        controller={controller()}
        conversation={conversationController({ createWorkspace })}
        onTerminalReady={() => undefined}
      />,
    )

    await user.click(screen.getByRole('button', { name: '新建工作区' }))
    await user.type(screen.getByLabelText('工作区名称'), 'Sandbox')
    await user.clear(screen.getByLabelText('工作区路径'))
    await user.type(screen.getByLabelText('工作区路径'), '/agent-workspace/sandbox')
    await user.type(screen.getByLabelText('仓库标识'), 'sandbox')
    await user.click(screen.getByRole('button', { name: '创建工作区' }))

    expect(createWorkspace).toHaveBeenCalledWith({
      displayName: 'Sandbox', workspacePath: '/agent-workspace/sandbox', repositoryId: 'sandbox',
    })
    expect(screen.queryByRole('dialog', { name: '新建工作区' })).not.toBeInTheDocument()
  })

  it('当前 Run 轮次只渲染一次用户消息并使用实时执行视图', () => {
    const currentTurn: ConversationTurn = {
      turnId: 'turn-current',
      conversationId: 'conv-1',
      turnIndex: 1,
      userContent: '验证工作台审批与执行证据',
      assistantContent: null,
      runId: RUN_ID,
      status: 'RUNNING',
      error: null,
      createdAt: '2026-08-07T01:00:06Z',
      completedAt: null,
    }
    render(
      <Workbench
        controller={controller({
          run: runView({
            status: 'RUNNING',
            state: {
              messages: [],
              variables: { 'planner.task': currentTurn.userContent },
              trace: [],
            },
          }),
        })}
        conversation={conversationController({ turns: [currentTurn] })}
        onTerminalReady={() => undefined}
      />,
    )

    const conversation = screen.getByLabelText('Agent 会话')
    expect(within(conversation).getAllByText(currentTurn.userContent)).toHaveLength(1)
    expect(within(conversation).queryByText('正在处理这条消息。')).not.toBeInTheDocument()
    expect(within(conversation).getByText('正在读取任务并建立执行计划。')).toBeVisible()
  })

  it('不展示不属于当前会话的旧 Run 与审批证据', () => {
    render(
      <Workbench
        controller={controller({
          run: runView({
            status: 'WAITING_APPROVAL',
            state: {
              messages: [],
              variables: { 'planner.task': '旧会话任务' },
              trace: [],
            },
            interruptRequest: {
              interruptId: '1a51de42-e150-40cc-93b1-f4c09d58ece4',
              nodeName: 'ops',
              reason: '旧会话审批',
              details: { 'ops.command': 'mvn test' },
            },
          }),
        })}
        conversation={conversationController()}
        onTerminalReady={() => undefined}
      />,
    )

    expect(screen.getByText('你是什么模型')).toBeVisible()
    expect(screen.queryByText('旧会话任务')).not.toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: '操作审批' })).not.toBeInTheDocument()
  })

  it('在聊天快路径展示 final_response 而不是代码执行占位', () => {
    render(
      <Workbench
        controller={controller({
          run: runView({
            state: {
              messages: [],
              variables: {
                'planner.task': '你是什么模型',
                'planner.route': 'chat',
                final_response: '我是 Agent4J 的智能助手。',
                'planner.model': 'quick-model',
              },
              trace: ['planner'],
            },
          }),
        })}
        onTerminalReady={() => undefined}
      />,
    )

    const conversation = screen.getByLabelText('Agent 会话')
    expect(within(conversation).getByText('我是 Agent4J 的智能助手。')).toBeVisible()
    expect(within(conversation).queryByText('正在读取任务并建立执行计划。')).not.toBeInTheDocument()
  })

  it('展示工具阶段和图片生成工件', () => {
    render(
      <Workbench
        controller={controller({
          run: runView({
            state: {
              messages: [],
              variables: {
                'planner.task': '请生成一张蓝色方块图片',
                'planner.route': 'agent',
                'planner.taskKind': 'TOOL_OPERATION',
                'tool.model': 'image-model',
                'skill.active': 'image-generation@1.0.0',
                'tool.result': JSON.stringify({
                  type: 'image',
                  dataUrl: 'data:image/png;base64,AA==',
                  revisedPrompt: '蓝色方块',
                  model: 'image-model',
                }),
                final_response: '图片已生成。',
              },
              trace: ['planner', 'tool-agent'],
            },
          }),
        })}
        onTerminalReady={() => undefined}
      />,
    )

    const conversation = screen.getByLabelText('Agent 会话')
    expect(within(conversation).getByText('调用工具')).toBeVisible()
    expect(within(conversation).queryByText('生成代码变更')).not.toBeInTheDocument()
    expect(within(conversation).getByText('image-generation@1.0.0')).toBeVisible()
    expect(within(conversation).getByRole('img', { name: 'Agent 生成图片' }))
      .toHaveAttribute('src', 'data:image/png;base64,AA==')
    expect(within(conversation).getByText('蓝色方块')).toBeVisible()
  })

  it('将任务、计划、执行结果和审查结论呈现为连续 Agent 会话', () => {
    render(
      <Workbench
        controller={controller({
          run: runView({
            graphId: 'demo-agent',
            state: {
              messages: [],
              variables: {
                'demo.task': '修复登录超时并运行测试',
                'planner.plan': '先定位超时配置，再修改代码并验证回归测试。',
                'planner.request': '用户任务：修复登录超时并运行测试',
                'planner.response': '先定位超时配置，再修改代码并验证回归测试。',
                'planner.model': 'code-model',
                'coder.request': '生成登录超时修复 Diff',
                'coder.response': '{"summary":"修复超时","command":"mvn test"}',
                'coder.model': 'code-model',
                'coder.summary': '修复超时配置',
                'coder.updatedFiles': 'src/LoginService.java',
                'ops.command': 'mvn test',
                'ops.exitCode': '0',
                'ops.stdout': 'Tests run: 12, Failures: 0',
                'reviewer.approved': 'true',
                'reviewer.summary': '任务链路已完成',
                'reviewer.feedback': '代码变更与测试结果通过审查',
                'reviewer.request': '代码与 Ops 证据',
                'reviewer.response': '{"approved":true}',
              },
              trace: ['planner', 'coder', 'ops', 'reviewer'],
            },
          }),
        })}
        onTerminalReady={() => undefined}
      />,
    )

    const conversation = screen.getByLabelText('Agent 会话')
    expect(within(conversation).getByText('修复登录超时并运行测试')).toBeVisible()
    expect(within(conversation).getAllByText('先定位超时配置，再修改代码并验证回归测试。')).toHaveLength(2)
    expect(within(conversation).getByText('src/LoginService.java')).toBeVisible()
    expect(within(conversation).getByText('mvn test')).toBeVisible()
    expect(within(conversation).getByText('任务链路已完成')).toBeVisible()
    expect(within(conversation).getByText('代码变更与测试结果通过审查')).toBeVisible()
    expect(within(conversation).getByText('用户任务：修复登录超时并运行测试')).toBeVisible()
    expect(within(conversation).getByText('生成登录超时修复 Diff')).toBeVisible()
    expect(within(conversation).getByText('{"summary":"修复超时","command":"mvn test"}')).toBeInTheDocument()
  })

  it('呈现持久化消息与 Ops 失败证据，而不是只显示成功摘要', () => {
    render(
      <Workbench
        controller={controller({
          run: runView({
            status: 'COMPLETED',
            state: {
              messages: [
                { role: 'user', content: '请运行回归测试' },
                { role: 'assistant', content: '我正在执行测试。' },
                {
                  role: 'tool',
                  content: '命令返回失败',
                  name: 'ops',
                  tool_call_id: 'tool-1',
                },
              ],
              variables: {
                'ops.command': 'mvn test',
                'ops.stderr': '编译失败',
                'ops.timedOut': 'true',
                'ops.error': 'java.lang.IllegalStateException: test failed',
                'ops.logError': '日志发布失败',
                'reviewer.error': '审查未执行',
              },
              trace: ['ops'],
            },
          }),
        })}
        onTerminalReady={() => undefined}
      />,
    )

    const conversation = screen.getByLabelText('Agent 会话')
    expect(within(conversation).getByText('请运行回归测试')).toBeVisible()
    expect(within(conversation).getByText('我正在执行测试。')).toBeVisible()
    expect(within(conversation).getByText('编译失败')).toBeVisible()
    expect(within(conversation).getByText('命令超时')).toBeVisible()
    expect(within(conversation).getByText(/java\.lang\.IllegalStateException: test failed/)).toBeVisible()
    expect(within(conversation).getByText('日志发布失败')).toBeVisible()
    expect(within(conversation).getByText('审查未执行')).toBeVisible()
  })

  it('空闲时将自然语言输入作为页面主操作', () => {
    render(<Workbench controller={controller()} onTerminalReady={() => undefined} />)

    expect(screen.getByRole('heading', { name: '今天要让 Agent 完成什么？' })).toBeVisible()
    expect(screen.getByLabelText('任务描述')).toBeVisible()
    expect(screen.getByRole('button', { name: '运行 Agent' })).toBeEnabled()
  })

  it('通过四个检查器视图访问代码、终端、审查和 Trace', async () => {
    const user = userEvent.setup()
    render(
      <Workbench
        controller={controller({ run: runView(), traceEvents: traceEvents() })}
        onTerminalReady={() => undefined}
      />,
    )

    const inspector = screen.getByLabelText('执行检查器')
    for (const label of ['代码变更', '终端', '浏览器', 'Trace']) {
      expect(within(inspector).getByRole('tab', { name: label })).toBeVisible()
    }
    await user.click(within(inspector).getByRole('tab', { name: 'Trace' }))
    expect(screen.getByTestId('trace-timeline')).toBeVisible()
  })

  it('没有实时事件时从权威 state.trace 恢复阶段轨迹', async () => {
    const user = userEvent.setup()
    render(
      <Workbench
        controller={controller({
          run: runView({ state: { messages: [], variables: {}, trace: ['planner', 'coder'] } }),
          traceEvents: [],
        })}
        onTerminalReady={() => undefined}
      />,
    )

    await user.click(screen.getByRole('tab', { name: 'Trace' }))
    const timeline = screen.getByTestId('trace-timeline')
    expect(within(timeline).getByText('已保存节点轨迹')).toBeVisible()
    expect(within(timeline).getByText('planner')).toBeVisible()
    expect(within(timeline).getByText('coder')).toBeVisible()
  })

  it('按自然语言任务启动 production code-agent Run', async () => {
    const user = userEvent.setup()
    const state = controller()
    render(<Workbench controller={state} onTerminalReady={() => undefined} />)

    const task = screen.getByLabelText('任务描述')
    await user.clear(task)
    await user.type(task, '修复登录超时')
    await user.click(screen.getByRole('button', { name: '运行 Agent' }))

    expect(state.startTask).toHaveBeenCalledWith('修复登录超时')
  })

  it('在三个 Tab 间切换并按精确路径选择 Diff 文件', async () => {
    const user = userEvent.setup()
    const diff = `diff --git a/src/App.java b/src/App.java
--- a/src/App.java
+++ b/src/App.java
@@ -1 +1 @@
-old
+new
diff --git a/src/Service.java b/src/Service.java
--- a/src/Service.java
+++ b/src/Service.java
@@ -1 +1 @@
-before
+after
`
    render(
      <Workbench
        controller={controller({
          run: runView({
            state: {
              messages: [],
              variables: { 'coder.unifiedDiff': diff },
              trace: [],
            },
          }),
        })}
        onTerminalReady={() => undefined}
      />,
    )

    expect(screen.getByRole('tab', { name: '代码变更' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByTestId('review-panel')).not.toBeInTheDocument()
    await user.selectOptions(await screen.findByLabelText('Diff 文件'), 'src/Service.java')
    expect(screen.getByTestId('monaco-diff')).toHaveAttribute('data-modified', 'after\n')

    await user.click(screen.getByRole('tab', { name: '终端' }))
    expect(screen.getByTestId('terminal-panel')).toBeVisible()
    expect(screen.getByTestId('monaco-diff')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: '浏览器' }))
    expect(await screen.findByTestId('review-panel')).toBeVisible()
  })

  it('在浏览器证据中呈现 Reviewer 摘要、反馈、模型和错误', async () => {
    const user = userEvent.setup()
    render(
      <Workbench
        controller={controller({
          run: runView({
            state: {
              messages: [],
              variables: {
                'reviewer.summary': '截图存在布局问题',
                'reviewer.feedback': '需要调整移动端布局',
                'reviewer.model': 'vision-model',
                'reviewer.error': '浏览器审查异常',
              },
              trace: [],
            },
          }),
        })}
        onTerminalReady={() => undefined}
      />,
    )

    await user.click(screen.getByRole('tab', { name: '浏览器' }))
    const panel = await screen.findByTestId('review-panel')
    expect(within(panel).getByText('截图存在布局问题')).toBeVisible()
    expect(within(panel).getByText('需要调整移动端布局')).toBeVisible()
    expect(within(panel).getByText('vision-model')).toBeVisible()
    expect(within(panel).getByText('浏览器审查异常')).toBeVisible()
  })

  it('渲染七种 Trace 事件', async () => {
    const user = userEvent.setup()
    render(
      <Workbench
        controller={controller({ run: runView(), traceEvents: traceEvents() })}
        onTerminalReady={() => undefined}
      />,
    )

    await user.click(screen.getByRole('tab', { name: 'Trace' }))
    const timeline = screen.getByTestId('trace-timeline')
    for (const label of ['节点开始', '节点完成', '已挂起', '已批准', '已拒绝', '失败', '完成']) {
      expect(within(timeline).getByText(label)).toBeVisible()
    }
  })

  it('发送批准、批准修改和拒绝的精确 payload', async () => {
    const user = userEvent.setup()
    const decide = vi.fn(async () => undefined)
    const waiting = runView({
      status: 'WAITING_APPROVAL',
      nextNode: 'ops',
      state: {
        messages: [],
        variables: { 'ops.command': 'mvn test', hidden: 'value' },
        trace: ['coder'],
      },
      interruptRequest: {
        interruptId: '1a51de42-e150-40cc-93b1-f4c09d58ece4',
        nodeName: 'ops',
        reason: '危险操作需要审批',
        details: { 'ops.command': 'mvn test', absent: 'value' },
      },
    })
    render(
      <Workbench
        controller={controller({ run: waiting, decide })}
        onTerminalReady={() => undefined}
      />,
    )
    const dialog = screen.getByRole('dialog', { name: '操作审批' })
    const reason = within(dialog).getByLabelText('审批说明')

    await user.type(reason, '已核对命令')
    await user.click(within(dialog).getByRole('button', { name: '批准' }))
    expect(decide).toHaveBeenLastCalledWith({
      decision: 'APPROVE',
      expectedVersion: 2,
      reason: '已核对命令',
      variableUpdates: {},
    })

    await user.click(within(dialog).getByRole('button', { name: '修改' }))
    expect(within(dialog).queryByLabelText('absent')).not.toBeInTheDocument()
    expect(within(dialog).queryByLabelText('hidden')).not.toBeInTheDocument()
    const command = within(dialog).getByLabelText('ops.command')
    await user.clear(command)
    await user.type(command, 'mvn verify')
    await user.click(within(dialog).getByRole('button', { name: '批准修改' }))
    expect(decide).toHaveBeenLastCalledWith({
      decision: 'APPROVE',
      expectedVersion: 2,
      reason: '已核对命令',
      variableUpdates: { 'ops.command': 'mvn verify' },
    })

    await user.click(within(dialog).getByRole('button', { name: '拒绝' }))
    expect(decide).toHaveBeenLastCalledWith({
      decision: 'REJECT',
      expectedVersion: 2,
      reason: '已核对命令',
      variableUpdates: {},
    })
  })

  it('切换证据版本并仅显示合法 PNG Data URL 和纯文本 DOM', async () => {
    const user = userEvent.setup()
    const first = runView({
      version: 3,
      state: {
        messages: [],
        trace: [],
        variables: {
          'reviewer.screenshotDataUrl': 'data:image/png;base64,AQID',
          'reviewer.dom': '<main>version 3</main>',
          'reviewer.finalUrl': 'https://example.test/three',
        },
      },
    })
    const second = runView({
      version: 4,
      state: {
        messages: [],
        trace: [],
        variables: {
          'reviewer.screenshotDataUrl': 'data:image/png;base64,BAUG',
          'reviewer.dom': '<main>version 4</main>',
          'reviewer.finalUrl': 'https://example.test/four',
        },
      },
    })
    const { rerender } = render(
      <Workbench
        controller={controller({ run: second, history: [first, second] })}
        onTerminalReady={() => undefined}
      />,
    )
    await user.click(screen.getByRole('tab', { name: '浏览器' }))
    await user.click(await screen.findByRole('button', { name: '版本 3' }))

    expect(screen.getByRole('img', { name: '版本 3 审查截图' })).toHaveAttribute(
      'src',
      'data:image/png;base64,AQID',
    )
    await user.click(screen.getByRole('tab', { name: 'DOM' }))
    expect(screen.getByTestId('monaco-dom')).toHaveTextContent('<main>version 3</main>')
    expect(document.querySelector('.evidence-content main')).toBeNull()

    rerender(
      <Workbench
        controller={controller({
          run: runView({
            state: {
              messages: [],
              trace: [],
              variables: { 'reviewer.screenshotDataUrl': 'https://example.test/image.png' },
            },
          }),
          history: [],
        })}
        onTerminalReady={() => undefined}
      />,
    )
    expect(screen.getByText('截图格式无效')).toBeVisible()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('权威 Run 前进时默认跟随最新证据版本', async () => {
    const user = userEvent.setup()
    const initial = runView({
      version: 0,
      status: 'RUNNING',
      nextNode: 'prepare',
      state: { messages: [], variables: {}, trace: [] },
    })
    const latest = runView({
      version: 2,
      state: {
        messages: [],
        variables: { 'reviewer.screenshotDataUrl': 'data:image/png;base64,AQID' },
        trace: [],
      },
    })
    const { rerender } = render(
      <Workbench
        controller={controller({ run: initial, history: [initial] })}
        onTerminalReady={() => undefined}
      />,
    )
    await user.click(screen.getByRole('tab', { name: '浏览器' }))
    expect(screen.getByText('等待 ReviewerNode 截图')).toBeVisible()

    rerender(
      <Workbench
        controller={controller({ run: latest, history: [initial, latest] })}
        onTerminalReady={() => undefined}
      />,
    )

    expect(screen.getByRole('img', { name: '版本 2 审查截图' })).toBeVisible()
  })
})
