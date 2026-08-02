import { decodeTerminalFrame } from '../api/runApi'

export interface SocketLike {
  readonly readyState: number
  onopen: ((event: Event) => void) | null
  onmessage: ((event: MessageEvent) => void) | null
  onerror: ((event: Event) => void) | null
  onclose: ((event: CloseEvent) => void) | null
  close(code?: number, reason?: string): void
}

export type WebSocketFactory = (url: string) => SocketLike

export interface TerminalSessionHandlers {
  onReset(): void
  onData(text: string): void
  onStateChange(readyState: number): void
  onError(error: Error): void
}

export function workbenchWebSocketUrl(path: string): string {
  const url = new URL(path, window.location.href)
  if (url.protocol === 'http:') {
    url.protocol = 'ws:'
  } else if (url.protocol === 'https:') {
    url.protocol = 'wss:'
  } else {
    throw new Error(`不支持的工作台协议: ${url.protocol}`)
  }
  return url.toString()
}

function asError(value: unknown): Error {
  return value instanceof Error ? value : new Error(String(value))
}

/** 管理单个 Run 的终端 WebSocket，并原样转发终端文本。 */
export class TerminalSession {
  private readonly socket: SocketLike
  private closed = false

  constructor(
    private readonly runId: string,
    factory: WebSocketFactory,
    private readonly handlers: TerminalSessionHandlers,
  ) {
    if (runId.trim().length === 0) throw new Error('runId 不能为空')
    this.socket = factory(workbenchWebSocketUrl(`/ws/runs/${runId}/terminal`))
    this.socket.onopen = () => handlers.onStateChange(this.socket.readyState)
    this.socket.onclose = () => handlers.onStateChange(this.socket.readyState)
    this.socket.onerror = () => handlers.onError(new Error('终端 WebSocket 连接失败'))
    this.socket.onmessage = (event) => this.handleMessage(event)
    handlers.onStateChange(this.socket.readyState)
  }

  private handleMessage(event: MessageEvent): void {
    try {
      const frame = decodeTerminalFrame(JSON.parse(String(event.data)) as unknown)
      if (frame.kind === 'SNAPSHOT') {
        if (frame.terminal.runId !== this.runId) {
          throw new Error(`terminal.runId 不匹配: ${frame.terminal.runId}`)
        }
        this.handlers.onReset()
        this.handlers.onData(frame.terminal.stdout)
        this.handlers.onData(frame.terminal.stderr)
        return
      }
      if (frame.event.runId !== this.runId) {
        throw new Error(`terminal event.runId 不匹配: ${frame.event.runId}`)
      }
      this.handlers.onData(frame.event.text)
    } catch (error) {
      this.handlers.onError(asError(error))
      this.close()
    }
  }

  close(): void {
    if (this.closed) return
    this.closed = true
    this.socket.onopen = null
    this.socket.onmessage = null
    this.socket.onerror = null
    this.socket.onclose = null
    if (this.socket.readyState < 2) this.socket.close()
    this.handlers.onStateChange(3)
  }
}
