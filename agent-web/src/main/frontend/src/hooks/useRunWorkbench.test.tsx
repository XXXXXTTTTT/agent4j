import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import type { AgentState, ApprovalCommand, RunView } from '../api/contracts'
import type { SocketLike, WebSocketFactory } from '../terminal/TerminalSession'
import { useRunWorkbench } from './useRunWorkbench'

const RUN_ID_1 = '3ba24ffc-e536-48ab-9bb4-19442c609ebc'
const RUN_ID_2 = 'c890db6f-322d-42ec-960b-62d5782a6b75'
const INITIAL_STATE: AgentState = { messages: [], variables: {}, trace: [] }

function runningRun(runId: string, version = 0): RunView {
  return {
    runId,
    version,
    graphId: 'coder-ops',
    status: 'RUNNING',
    state: INITIAL_STATE,
    nextNode: 'coder',
    interruptRequest: null,
    approvalDecision: null,
    approvalReason: null,
    error: null,
    createdAt: '2026-08-03T00:00:00Z',
  }
}

function waitingRun(version: number): RunView {
  return {
    ...runningRun(RUN_ID_1, version),
    status: 'WAITING_APPROVAL',
    nextNode: 'ops',
    state: {
      messages: [],
      variables: { 'ops.command': 'mvn test' },
      trace: ['coder'],
    },
    interruptRequest: {
      interruptId: 'cb93865d-795a-4942-886a-a523c14bdb85',
      nodeName: 'ops',
      reason: '危险操作需要审批',
      details: { 'ops.command': 'mvn test' },
    },
  }
}

function completedRun(version: number): RunView {
  return {
    ...runningRun(RUN_ID_1, version),
    status: 'COMPLETED',
    nextNode: null,
    state: { messages: [], variables: {}, trace: ['coder', 'ops'] },
  }
}

function jsonResponse(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

class FakeSocket implements SocketLike {
  readyState = 0
  onopen: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null
  readonly close = vi.fn(() => {
    this.readyState = 3
  })

  constructor(readonly url: string) {}

  emitOpen(): void {
    this.readyState = 1
    this.onopen?.(new Event('open'))
  }

  emitMessage(value: unknown): void {
    this.onmessage?.(new MessageEvent('message', { data: JSON.stringify(value) }))
  }
}

function socketHarness() {
  const sockets: FakeSocket[] = []
  const factory = vi.fn<WebSocketFactory>((url) => {
    const socket = new FakeSocket(url)
    sockets.push(socket)
    return socket
  })
  return { sockets, factory }
}

describe('useRunWorkbench', () => {
  it('启动后读取历史并打开两条连接，切换 Run 和卸载时清理旧连接', async () => {
    let startCount = 0
    const fetchSpy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/runs' && init?.method === 'POST') {
        startCount += 1
        return jsonResponse(runningRun(startCount === 1 ? RUN_ID_1 : RUN_ID_2), 202)
      }
      if (url.endsWith('/history')) {
        const runId = url.includes(RUN_ID_1) ? RUN_ID_1 : RUN_ID_2
        return jsonResponse([runningRun(runId)])
      }
      throw new Error(`未处理请求: ${url}`)
    })
    const { sockets, factory } = socketHarness()
    const { result, unmount } = renderHook(() =>
      useRunWorkbench({
        fetcher: fetchSpy as typeof fetch,
        webSocketFactory: factory,
        onTerminalReset: vi.fn(),
        onTerminalData: vi.fn(),
      }),
    )

    await act(() => result.current.start('coder-ops', INITIAL_STATE))

    expect(result.current.run?.runId).toBe(RUN_ID_1)
    expect(result.current.history.map((run) => run.runId)).toEqual([RUN_ID_1])
    expect(sockets.map((socket) => new URL(socket.url).pathname)).toEqual([
      `/ws/runs/${RUN_ID_1}/trace`,
      `/ws/runs/${RUN_ID_1}/terminal`,
    ])
    expect(result.current.connectionState).toEqual({ trace: 0, terminal: 0 })

    act(() => sockets.forEach((socket) => socket.emitOpen()))
    expect(result.current.connectionState).toEqual({ trace: 1, terminal: 1 })

    await act(() => result.current.start('coder-ops', INITIAL_STATE))
    expect(sockets.slice(0, 2).every((socket) => socket.close.mock.calls.length === 1)).toBe(true)
    expect(sockets.slice(2).map((socket) => new URL(socket.url).pathname)).toEqual([
      `/ws/runs/${RUN_ID_2}/trace`,
      `/ws/runs/${RUN_ID_2}/terminal`,
    ])

    unmount()
    expect(sockets.slice(2).every((socket) => socket.close.mock.calls.length === 1)).toBe(true)
  })

  it('审批 409 时只重读最新 Run，不重复审批或刷新历史', async () => {
    let historyCalls = 0
    let approvalCalls = 0
    let latestCalls = 0
    const fetchSpy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/runs') return jsonResponse(waitingRun(1), 202)
      if (url.endsWith('/history')) {
        historyCalls += 1
        return jsonResponse([waitingRun(1)])
      }
      if (url.endsWith('/approval') && init?.method === 'POST') {
        approvalCalls += 1
        return jsonResponse({ detail: '版本冲突' }, 409)
      }
      if (url === `/api/runs/${RUN_ID_1}`) {
        latestCalls += 1
        return jsonResponse(waitingRun(2))
      }
      throw new Error(`未处理请求: ${url}`)
    })
    const { factory } = socketHarness()
    const { result } = renderHook(() =>
      useRunWorkbench({
        fetcher: fetchSpy as typeof fetch,
        webSocketFactory: factory,
        onTerminalReset: vi.fn(),
        onTerminalData: vi.fn(),
      }),
    )
    await act(() => result.current.start('coder-ops', INITIAL_STATE))
    const command: ApprovalCommand = {
      decision: 'APPROVE',
      expectedVersion: 1,
      reason: '已检查',
      variableUpdates: {},
    }

    await act(() => result.current.decide(command))

    expect(result.current.run?.version).toBe(2)
    expect(result.current.history.map((run) => run.version)).toEqual([1])
    expect({ approvalCalls, latestCalls, historyCalls }).toEqual({
      approvalCalls: 1,
      latestCalls: 1,
      historyCalls: 1,
    })
  })

  it('收到终态 Trace 后保存事件并刷新最新状态和历史', async () => {
    let latest = runningRun(RUN_ID_1)
    const fetchSpy = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/runs') return jsonResponse(runningRun(RUN_ID_1), 202)
      if (url === `/api/runs/${RUN_ID_1}`) return jsonResponse(latest)
      if (url.endsWith('/history')) {
        return jsonResponse(
          latest.version === 0 ? [runningRun(RUN_ID_1)] : [runningRun(RUN_ID_1), latest],
        )
      }
      throw new Error(`未处理请求: ${url}`)
    })
    const { sockets, factory } = socketHarness()
    const { result } = renderHook(() =>
      useRunWorkbench({
        fetcher: fetchSpy as typeof fetch,
        webSocketFactory: factory,
        onTerminalReset: vi.fn(),
        onTerminalData: vi.fn(),
      }),
    )
    await act(() => result.current.start('coder-ops', INITIAL_STATE))
    latest = completedRun(2)

    act(() =>
      sockets[0].emitMessage({
        kind: 'EVENT',
        event: {
          type: 'COMPLETED',
          eventId: '1a51de42-e150-40cc-93b1-f4c09d58ece4',
          runId: RUN_ID_1,
          checkpointVersion: 2,
          occurredAt: '2026-08-03T00:00:02Z',
        },
      }),
    )

    await waitFor(() => expect(result.current.run?.status).toBe('COMPLETED'))
    expect(result.current.traceEvents.map((event) => event.type)).toEqual(['COMPLETED'])
    expect(result.current.history.map((run) => run.version)).toEqual([0, 2])
    expect(result.current.error).toBeNull()
  })

  it('拒绝其他 Run 的 Trace 快照并关闭 Trace 连接', async () => {
    const fetchSpy = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/runs') return jsonResponse(runningRun(RUN_ID_1), 202)
      if (url.endsWith('/history')) return jsonResponse([runningRun(RUN_ID_1)])
      throw new Error(`未处理请求: ${url}`)
    })
    const { sockets, factory } = socketHarness()
    const { result } = renderHook(() =>
      useRunWorkbench({
        fetcher: fetchSpy as typeof fetch,
        webSocketFactory: factory,
        onTerminalReset: vi.fn(),
        onTerminalData: vi.fn(),
      }),
    )
    await act(() => result.current.start('coder-ops', INITIAL_STATE))

    act(() =>
      sockets[0].emitMessage({ kind: 'SNAPSHOT', run: runningRun(RUN_ID_2) }),
    )

    expect(result.current.error?.message).toContain('Trace run.runId 不匹配')
    expect(sockets[0].close).toHaveBeenCalledOnce()
  })

  it('审批 409 后重读失败时保留该失败并向调用方抛出', async () => {
    const fetchSpy = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/runs') return jsonResponse(waitingRun(1), 202)
      if (url.endsWith('/history')) return jsonResponse([waitingRun(1)])
      if (url.endsWith('/approval')) return jsonResponse({ detail: '版本冲突' }, 409)
      if (url === `/api/runs/${RUN_ID_1}`) {
        return jsonResponse({ detail: '数据库不可用' }, 503)
      }
      throw new Error(`未处理请求: ${url}`)
    })
    const { factory } = socketHarness()
    const { result } = renderHook(() =>
      useRunWorkbench({
        fetcher: fetchSpy as typeof fetch,
        webSocketFactory: factory,
        onTerminalReset: vi.fn(),
        onTerminalData: vi.fn(),
      }),
    )
    await act(() => result.current.start('coder-ops', INITIAL_STATE))
    let rejection: unknown

    await act(async () => {
      try {
        await result.current.decide({
          decision: 'APPROVE',
          expectedVersion: 1,
          reason: '已检查',
          variableUpdates: {},
        })
      } catch (error) {
        rejection = error
      }
    })

    expect(rejection).toBeInstanceOf(Error)
    expect(result.current.error?.message).toContain('HTTP 503')
  })
})
