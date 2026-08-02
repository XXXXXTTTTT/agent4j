import { describe, expect, it, vi } from 'vitest'

import type { SocketLike, WebSocketFactory } from './TerminalSession'
import { TerminalSession } from './TerminalSession'

const RUN_ID = '3ba24ffc-e536-48ab-9bb4-19442c609ebc'

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
    this.onmessage?.(
      new MessageEvent('message', {
        data: typeof value === 'string' ? value : JSON.stringify(value),
      }),
    )
  }
}

describe('TerminalSession', () => {
  it('按快照顺序写入 stdout、stderr 并原样转发 ANSI 日志', () => {
    let socket: FakeSocket | null = null
    const factory: WebSocketFactory = (url) => {
      socket = new FakeSocket(url)
      return socket
    }
    const reset = vi.fn()
    const write = vi.fn()
    const state = vi.fn()
    const error = vi.fn()
    const session = new TerminalSession(RUN_ID, factory, {
      onReset: reset,
      onData: write,
      onStateChange: state,
      onError: error,
    })
    const activeSocket = socket as unknown as FakeSocket

    expect(new URL(activeSocket.url).pathname).toBe(`/ws/runs/${RUN_ID}/terminal`)
    activeSocket.emitOpen()
    activeSocket.emitMessage({
      kind: 'SNAPSHOT',
      terminal: {
        runId: RUN_ID,
        checkpointVersion: 3,
        stdout: 'stdout\r\n',
        stderr: 'stderr\r\n',
        exitCode: 0,
        timedOut: false,
        error: null,
      },
    })
    activeSocket.emitMessage({
      kind: 'LOG',
      event: {
        eventId: 'c890db6f-322d-42ec-960b-62d5782a6b75',
        runId: RUN_ID,
        nodeName: 'ops',
        sequence: 0,
        stream: 'PTY',
        text: '\u001b[32mok\u001b[0m\r\n',
        occurredAt: '2026-08-03T00:00:01Z',
      },
    })

    expect(reset).toHaveBeenCalledOnce()
    expect(write.mock.calls.map(([text]) => text)).toEqual([
      'stdout\r\n',
      'stderr\r\n',
      '\u001b[32mok\u001b[0m\r\n',
    ])
    expect(state).toHaveBeenCalledWith(1)
    expect(error).not.toHaveBeenCalled()

    session.close()
    session.close()
    expect(activeSocket.close).toHaveBeenCalledOnce()
  })

  it('将非法帧作为明确错误返回并关闭连接', () => {
    let socket: FakeSocket | null = null
    const errors: Error[] = []
    const session = new TerminalSession(
      RUN_ID,
      (url) => {
        socket = new FakeSocket(url)
        return socket
      },
      {
        onReset: () => undefined,
        onData: () => undefined,
        onStateChange: () => undefined,
        onError: (error) => errors.push(error),
      },
    )
    const activeSocket = socket as unknown as FakeSocket

    activeSocket.emitMessage('{"kind":"log"}')

    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('terminalFrame.kind')
    expect(activeSocket.close).toHaveBeenCalledOnce()
    session.close()
  })

  it('拒绝其他 Run 的终端帧', () => {
    let socket: FakeSocket | null = null
    const errors: Error[] = []
    new TerminalSession(
      RUN_ID,
      (url) => {
        socket = new FakeSocket(url)
        return socket
      },
      {
        onReset: () => undefined,
        onData: () => undefined,
        onStateChange: () => undefined,
        onError: (error) => errors.push(error),
      },
    )
    const activeSocket = socket as unknown as FakeSocket

    activeSocket.emitMessage({
      kind: 'SNAPSHOT',
      terminal: {
        runId: 'c890db6f-322d-42ec-960b-62d5782a6b75',
        checkpointVersion: 0,
        stdout: '',
        stderr: '',
        exitCode: null,
        timedOut: null,
        error: null,
      },
    })

    expect(errors[0].message).toContain('terminal.runId 不匹配')
    expect(activeSocket.close).toHaveBeenCalledOnce()
  })
})
