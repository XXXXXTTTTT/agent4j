import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import type { RunView, TraceEvent } from '../api/contracts'
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
    reload: vi.fn(async () => undefined),
    decide: vi.fn(async () => undefined),
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
  it('按精确 graphId 和 initialState JSON 启动 Run', async () => {
    const user = userEvent.setup()
    const state = controller()
    render(<Workbench controller={state} onTerminalReady={() => undefined} />)

    await user.type(screen.getByLabelText('图 ID'), 'product-workbench-flow')
    const input = screen.getByLabelText('初始状态 JSON')
    await user.clear(input)
    await user.click(input)
    await user.paste(
      '{"messages":[],"variables":{"ops.command":"mvn test"},"trace":[]}',
    )
    await user.click(screen.getByRole('button', { name: '启动 Run' }))

    expect(state.start).toHaveBeenCalledWith('product-workbench-flow', {
      messages: [],
      variables: { 'ops.command': 'mvn test' },
      trace: [],
    })
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

    expect(screen.getByRole('tab', { name: '代码' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByTestId('review-panel')).not.toBeInTheDocument()
    await user.selectOptions(await screen.findByLabelText('Diff 文件'), 'src/Service.java')
    expect(screen.getByTestId('monaco-diff')).toHaveAttribute('data-modified', 'after\n')

    await user.click(screen.getByRole('tab', { name: '终端' }))
    expect(screen.getByTestId('terminal-panel')).toBeVisible()
    expect(screen.getByTestId('monaco-diff')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: '审查' }))
    expect(await screen.findByTestId('review-panel')).toBeVisible()
  })

  it('渲染七种 Trace 事件', () => {
    render(
      <Workbench
        controller={controller({ run: runView(), traceEvents: traceEvents() })}
        onTerminalReady={() => undefined}
      />,
    )

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
    await user.click(screen.getByRole('tab', { name: '审查' }))
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
    await user.click(screen.getByRole('tab', { name: '审查' }))
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
