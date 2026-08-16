import { describe, expect, it, vi } from 'vitest'

import type { SocketLike, WebSocketFactory } from './TerminalSession'
import { InteractiveTerminalSession } from './InteractiveTerminalSession'

class FakeInteractiveSocket implements SocketLike {
  readyState = 0
  onopen: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null
  readonly sent: string[] = []
  readonly close = vi.fn(() => { this.readyState = 3 })
  readonly send = vi.fn((data: string) => { this.sent.push(data) })

  constructor(readonly url: string) {}

  emitOpen(): void {
    this.readyState = 1
    this.onopen?.(new Event('open'))
  }

  emitMessage(value: unknown): void {
    this.onmessage?.(new MessageEvent('message', { data: JSON.stringify(value) }))
  }

  emitClose(): void {
    this.readyState = 3
    this.onclose?.(new CloseEvent('close'))
  }
}

describe('InteractiveTerminalSession', () => {
  it('连接后发送输入、尺寸和中断消息，并解析终端输出', () => {
    let socket: FakeInteractiveSocket | null = null
    const output = vi.fn()
    const state = vi.fn()
    const exited = vi.fn()
    const factory: WebSocketFactory = (url) => {
      socket = new FakeInteractiveSocket(url)
      return socket
    }
    const session = new InteractiveTerminalSession('workspace-1', factory, { onOutput: output, onStateChange: state, onExit: exited, onError: vi.fn() })
    const active = socket as unknown as FakeInteractiveSocket
    expect(new URL(active.url).pathname).toBe('/ws/workspaces/workspace-1/terminal')
    active.emitOpen()
    session.sendInput('Get-ChildItem\r')
    session.resize(120, 32)
    session.interrupt()
    active.emitMessage({ type: 'output', data: '\u001b[32mok\u001b[0m\r\n' })
    active.emitMessage({ type: 'exit', exitCode: 0 })
    expect(active.sent.map((value) => JSON.parse(value))).toEqual([
      { type: 'input', data: 'Get-ChildItem\r' },
      { type: 'resize', cols: 120, rows: 32 },
      { type: 'interrupt' },
    ])
    expect(output).toHaveBeenCalledWith('\u001b[32mok\u001b[0m\r\n')
    expect(exited).toHaveBeenCalledWith(0)
    expect(state).toHaveBeenCalledWith(1)
    session.close()
    expect(active.close).toHaveBeenCalledOnce()
  })

  it('连接建立前缓存输入，非法帧触发错误并关闭连接', () => {
    let socket: FakeInteractiveSocket | null = null
    const errors: Error[] = []
    const session = new InteractiveTerminalSession('workspace-1', (url) => {
      socket = new FakeInteractiveSocket(url)
      return socket
    }, { onOutput: vi.fn(), onStateChange: vi.fn(), onExit: vi.fn(), onError: (error) => errors.push(error) })
    const active = socket as unknown as FakeInteractiveSocket
    session.sendInput('echo ready\r')
    active.emitOpen()
    expect(JSON.parse(active.sent[0])).toEqual({ type: 'input', data: 'echo ready\r' })
    active.emitMessage({ type: 'unknown' })
    expect(errors[0].message).toContain('interactiveTerminalFrame.type')
    expect(active.close).toHaveBeenCalledOnce()
  })

  it('断开后按退避策略自动重连，显式 close 后不再创建新连接', () => {
    vi.useFakeTimers()
    try {
      const sockets: FakeInteractiveSocket[] = []
      const state = vi.fn()
      const session = new InteractiveTerminalSession('workspace-1', (url) => {
        const socket = new FakeInteractiveSocket(url)
        sockets.push(socket)
        return socket
      }, { onOutput: vi.fn(), onStateChange: state, onExit: vi.fn(), onError: vi.fn() })
      sockets[0].emitOpen()
      sockets[0].emitClose()
      vi.advanceTimersByTime(999)
      expect(sockets).toHaveLength(1)
      vi.advanceTimersByTime(1)
      expect(sockets).toHaveLength(2)
      session.close()
      vi.advanceTimersByTime(10000)
      expect(sockets).toHaveLength(2)
      expect(state).toHaveBeenCalledWith(3)
    } finally {
      vi.useRealTimers()
    }
  })
})
