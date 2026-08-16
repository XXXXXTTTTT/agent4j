import { describe, expect, it, vi } from 'vitest'

import { PowerShellTerminalSession, type NativeTerminalSocket, type NativeTerminalSocketFactory } from './PowerShellTerminalSession'

class FakeSocket implements NativeTerminalSocket {
  readyState = 0
  onopen: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null
  readonly sent: string[] = []
  readonly close = vi.fn(() => { this.readyState = 3 })
  readonly send = vi.fn((data: string) => { this.sent.push(data) })

  constructor(readonly url: string) {}

  emitOpen(): void { this.readyState = 1; this.onopen?.(new Event('open')) }
  emitOutput(value: string): void { this.onmessage?.(new MessageEvent('message', { data: value })) }
  emitClose(): void { this.readyState = 3; this.onclose?.(new CloseEvent('close')) }
}

describe('PowerShellTerminalSession', () => {
  it('连接原生桥接器，将 xterm 输入和尺寸帧发送到 node-pty', () => {
    let socket: FakeSocket | null = null
    const output = vi.fn()
    const factory: NativeTerminalSocketFactory = (url) => {
      socket = new FakeSocket(url)
      return socket
    }
    const session = new PowerShellTerminalSession('/agent-workspace/project-a', factory, {
      onOutput: output, onStateChange: vi.fn(), onError: vi.fn(),
    })
    const active = socket as unknown as FakeSocket
    expect(new URL(active.url).origin).toBe('ws://127.0.0.1:8090')
    expect(new URL(active.url).pathname).toBe('/ws/terminal')
    expect(new URL(active.url).searchParams.get('workspacePath')).toBe('/agent-workspace/project-a')
    active.emitOpen()
    session.sendInput('Get-ChildItem\r')
    session.resize(120, 32)
    active.emitOutput('\u001b[32mPS C:\\project-a>\u001b[0m')
    expect(active.sent.map((frame) => JSON.parse(frame))).toEqual([
      { type: 'input', data: 'Get-ChildItem\r' },
      { type: 'resize', cols: 120, rows: 32 },
    ])
    expect(output).toHaveBeenCalledWith('\u001b[32mPS C:\\project-a>\u001b[0m')
  })

  it('重连后恢复最近一次终端尺寸，并缓存断线期间的输入', () => {
    vi.useFakeTimers()
    const sockets: FakeSocket[] = []
    const factory: NativeTerminalSocketFactory = (url) => {
      const socket = new FakeSocket(url)
      sockets.push(socket)
      return socket
    }
    const session = new PowerShellTerminalSession('/agent-workspace/project-a', factory, {
      onOutput: vi.fn(), onStateChange: vi.fn(), onError: vi.fn(),
    })
    sockets[0].emitOpen()
    session.resize(140, 36)
    sockets[0].emitClose()
    session.sendInput('Get-Location\r')
    vi.advanceTimersByTime(1000)
    sockets[1].emitOpen()
    expect(sockets[1].sent.map((frame) => JSON.parse(frame))).toEqual([
      { type: 'input', data: 'Get-Location\r' },
      { type: 'resize', cols: 140, rows: 36 },
    ])
    session.close()
    vi.useRealTimers()
  })
})
