import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useRef } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type {
  Actor,
  Conversation,
  RunView,
  TerminalSnapshot,
  TraceEvent,
  Workspace,
} from '../api/contracts'
import type { ConversationWorkspaceApi } from '../hooks/useConversationWorkspace'
import { useConversationWorkspace } from '../hooks/useConversationWorkspace'
import { useRunWorkbench } from '../hooks/useRunWorkbench'
import type { TerminalPanelHandle } from './TerminalPanel'
import { Workbench } from './Workbench'
import type { SocketLike, WebSocketFactory } from '../terminal/TerminalSession'

const RUN_ID = '95eea902-4ac5-42a6-bb39-3f4c6b16a0c7'
const INTERRUPT_ID = 'ae8980f2-07af-4a9f-9424-5aee88272f36'
const TIMESTAMP = '2026-08-13T00:00:00Z'

const ACTOR: Actor = { userId: 'user-1', displayName: 'Alice' }
const WORKSPACE: Workspace = {
  workspaceId: 'ws-1',
  ownerUserId: 'user-1',
  displayName: 'Agent4J',
  workspacePath: 'D:/agent4j',
  repositoryId: 'agent4j',
  permission: 'OWNER',
  createdAt: TIMESTAMP,
  updatedAt: TIMESTAMP,
}

function waitingRun(): RunView {
  return {
    runId: RUN_ID,
    version: 1,
    graphId: 'governed-cli',
    status: 'WAITING_APPROVAL',
    state: {
      messages: [],
      variables: { 'ops.command': 'mvn test' },
      trace: ['prepare'],
    },
    nextNode: 'ops',
    interruptRequest: {
      interruptId: INTERRUPT_ID,
      nodeName: 'ops',
      reason: 'MUTATING 命令需要审批',
      details: { 'ops.command': 'mvn test' },
    },
    approvalDecision: null,
    approvalReason: null,
    error: null,
    createdAt: TIMESTAMP,
  }
}

function terminalRun(status: 'COMPLETED' | 'REJECTED', decision: 'APPROVE' | 'REJECT'): RunView {
  const waiting = waitingRun()
  if (status === 'COMPLETED') {
    return {
      ...waiting,
      version: 2,
      status,
      state: {
        messages: [],
        variables: { 'ops.command': 'mvn test' },
        trace: ['prepare', 'ops'],
      },
      nextNode: null,
      interruptRequest: null,
      approvalDecision: null,
      approvalReason: null,
    }
  }
  return {
    ...waiting,
    version: 2,
    status,
    state: {
      messages: [],
      variables: { 'ops.command': 'mvn test' },
      trace: ['prepare', 'ops'],
    },
    nextNode: null,
    interruptRequest: waiting.interruptRequest,
    approvalDecision: decision,
    approvalReason: '拒绝执行 Maven 测试',
  }
}

function jsonResponse(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

class FakeSocket implements SocketLike {
  readyState = WebSocket.CONNECTING
  onopen: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null
  readonly close = vi.fn(() => {
    this.readyState = WebSocket.CLOSED
  })

  constructor(readonly url: string) {}

  emitMessage(frame: unknown): void {
    this.onmessage?.(new MessageEvent('message', { data: JSON.stringify(frame) }))
  }
}

function conversationApi(): ConversationWorkspaceApi {
  return {
    getIdentity: vi.fn(async () => ACTOR),
    listWorkspaces: vi.fn(async () => [WORKSPACE]),
    listConversations: vi.fn(async () => [] as Conversation[]),
    searchConversations: vi.fn(async () => [] as Conversation[]),
    createWorkspace: vi.fn(),
    createConversation: vi.fn(),
    submitConversationTurn: vi.fn(),
    listConversationTurns: vi.fn(async () => []),
    archiveConversation: vi.fn(),
  }
}

interface FlowHarness {
  fetcher: ReturnType<typeof vi.fn<typeof fetch>>
  sockets: FakeSocket[]
  completed: RunView
  rejected: RunView
}

function renderWorkbenchFlow(): FlowHarness {
  const completed = terminalRun('COMPLETED', 'APPROVE')
  const rejected = terminalRun('REJECTED', 'REJECT')
  let currentRun = waitingRun()
  const sockets: FakeSocket[] = []
  const webSocketFactory = vi.fn<WebSocketFactory>((url) => {
    const socket = new FakeSocket(url)
    sockets.push(socket)
    return socket
  })
  const fetcher = vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input)
    if (url === '/api/workspaces/ws-1/cli/commands' && init?.method === 'GET') {
      return jsonResponse([{
        name: 'test.maven',
        executable: 'mvn',
        fixedArguments: ['test'],
        riskLevel: 'MUTATING',
        requiredCapabilities: ['TERMINAL'],
        maxArguments: 64,
      }])
    }
    if (url === '/api/workspaces/ws-1/cli/runs' && init?.method === 'POST') {
      return jsonResponse(waitingRun(), 202)
    }
    if (url === `/api/runs/${RUN_ID}` && init?.method === 'GET') {
      return jsonResponse(currentRun)
    }
    if (url === `/api/runs/${RUN_ID}/history` && init?.method === 'GET') {
      return jsonResponse(currentRun.version === 1 ? [waitingRun()] : [waitingRun(), currentRun])
    }
    if (url === `/api/runs/${RUN_ID}/approval` && init?.method === 'POST') {
      const command = JSON.parse(String(init.body)) as { decision: string }
      currentRun = command.decision === 'APPROVE' ? completed : rejected
      return jsonResponse(currentRun)
    }
    throw new Error(`未处理请求: ${url}`)
  })
  vi.stubGlobal('fetch', fetcher)

  function FlowWorkbench() {
    const terminalRef = useRef<TerminalPanelHandle | null>(null)
    const controller = useRunWorkbench({
      fetcher,
      webSocketFactory,
      onTerminalReset: () => terminalRef.current?.reset(),
      onTerminalData: (text) => terminalRef.current?.write(text),
    })
    const conversation = useConversationWorkspace({ api: conversationApi() })
    return (
      <Workbench
        controller={controller}
        conversation={conversation}
        onTerminalReady={(terminal) => { terminalRef.current = terminal }}
      />
    )
  }

  render(<FlowWorkbench />)
  return { fetcher, sockets, completed, rejected }
}

async function createMutatingCliRun(user: ReturnType<typeof userEvent.setup>) {
  const input = await screen.findByRole('textbox', { name: '发送消息' })
  await user.type(input, '/')
  await screen.findByRole('option', { name: /test\.maven/ })
  await user.keyboard('{Enter}')
  await user.click(screen.getByRole('button', { name: '执行命令' }))
  await screen.findByRole('dialog', { name: '操作审批' })
}

afterEach(() => vi.unstubAllGlobals())

describe('Workbench governed CLI flow', () => {
  it('从斜杠命令创建待审批 Run，批准后展示终端与 Trace 证据', async () => {
    const user = userEvent.setup()
    const { completed, fetcher, sockets } = renderWorkbenchFlow()

    await createMutatingCliRun(user)
    expect(screen.getByTestId('run-status')).toHaveTextContent('WAITING_APPROVAL')
    expect(fetcher).toHaveBeenCalledWith('/api/workspaces/ws-1/cli/runs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ commandName: 'test.maven', arguments: [], timeoutSeconds: 30 }),
    })

    const approval = screen.getByRole('dialog', { name: '操作审批' })
    await user.type(within(approval).getByLabelText('审批说明'), '允许执行 Maven 测试')
    await user.click(within(approval).getByRole('button', { name: '批准' }))

    const approvalRequest = {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        decision: 'APPROVE',
        expectedVersion: 1,
        reason: '允许执行 Maven 测试',
        variableUpdates: {},
      }),
    }
    await waitFor(() => expect(fetcher).toHaveBeenCalledWith(`/api/runs/${RUN_ID}/approval`, approvalRequest))
    await waitFor(() => expect(screen.getByTestId('run-status')).toHaveTextContent('COMPLETED'))

    expect(sockets).toHaveLength(2)
    const terminal: TerminalSnapshot = {
      runId: RUN_ID,
      checkpointVersion: completed.version,
      stdout: 'mvn test\nBUILD SUCCESS\n',
      stderr: '',
      exitCode: 0,
      timedOut: false,
      error: null,
    }
    const trace: TraceEvent = {
      type: 'COMPLETED',
      eventId: '1b24c6c7-d2ea-4cdb-a540-c78a31b86e3f',
      runId: RUN_ID,
      checkpointVersion: completed.version,
      occurredAt: TIMESTAMP,
    }
    await act(async () => {
      sockets[1].emitMessage({ kind: 'SNAPSHOT', terminal })
      sockets[0].emitMessage({ kind: 'EVENT', event: trace })
    })

    await user.click(screen.getByRole('tab', { name: '终端' }))
    expect(within(screen.getByTestId('terminal-panel')).getByText(/BUILD SUCCESS/)).toBeVisible()
    await user.click(screen.getByRole('tab', { name: 'Trace' }))
    expect(within(screen.getByTestId('trace-timeline')).getByText('完成')).toBeVisible()
  })

  it('拒绝受治理命令后将工作台 Run 设为 REJECTED', async () => {
    const user = userEvent.setup()
    const { fetcher } = renderWorkbenchFlow()

    await createMutatingCliRun(user)
    const approval = screen.getByRole('dialog', { name: '操作审批' })
    await user.type(within(approval).getByLabelText('审批说明'), '拒绝执行 Maven 测试')
    await user.click(within(approval).getByRole('button', { name: '拒绝' }))

    const approvalRequest = {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        decision: 'REJECT',
        expectedVersion: 1,
        reason: '拒绝执行 Maven 测试',
        variableUpdates: {},
      }),
    }
    await waitFor(() => expect(fetcher).toHaveBeenCalledWith(`/api/runs/${RUN_ID}/approval`, approvalRequest))
    await waitFor(() => expect(screen.getByTestId('run-status')).toHaveTextContent('REJECTED'))
  })
})
