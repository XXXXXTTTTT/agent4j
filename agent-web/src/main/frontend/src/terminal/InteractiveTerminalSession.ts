import { workbenchWebSocketUrl, type SocketLike, type WebSocketFactory } from './TerminalSession'

export interface InteractiveSocketLike extends SocketLike {
  send(data: string): void
}

export interface InteractiveTerminalHandlers {
  onOutput(text: string): void
  onStateChange(readyState: number): void
  onExit(exitCode: number | null): void
  onError(error: Error): void
}

type InteractiveFrame =
  | { type: 'ready'; sessionId: string; cwd: string; shell: string }
  | { type: 'output'; data: string }
  | { type: 'exit'; exitCode: number | null }
  | { type: 'error'; message: string }

function objectAt(value: unknown, path: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new TypeError(`${path} 必须是对象`)
  return value as Record<string, unknown>
}

function stringAt(value: unknown, path: string): string {
  if (typeof value !== 'string') throw new TypeError(`${path} 必须是字符串`)
  return value
}

function decodeFrame(value: unknown): InteractiveFrame {
  const object = objectAt(value, 'interactiveTerminalFrame')
  const type = stringAt(object.type, 'interactiveTerminalFrame.type')
  if (type === 'ready') {
    return { type, sessionId: stringAt(object.sessionId, 'interactiveTerminalFrame.sessionId'), cwd: stringAt(object.cwd, 'interactiveTerminalFrame.cwd'), shell: stringAt(object.shell, 'interactiveTerminalFrame.shell') }
  }
  if (type === 'output') return { type, data: stringAt(object.data, 'interactiveTerminalFrame.data') }
  if (type === 'exit') {
    const exitCode = object.exitCode
    if (exitCode !== null && (typeof exitCode !== 'number' || !Number.isInteger(exitCode))) throw new TypeError('interactiveTerminalFrame.exitCode 必须是整数或 null')
    return { type, exitCode }
  }
  if (type === 'error') return { type, message: stringAt(object.message, 'interactiveTerminalFrame.message') }
  throw new TypeError(`interactiveTerminalFrame.type 包含未知值: ${type}`)
}

function asError(value: unknown): Error {
  return value instanceof Error ? value : new Error(String(value))
}

/** 管理工作区交互式 PTY WebSocket，支持键盘输入、尺寸同步和中断。 */
export class InteractiveTerminalSession {
  private socket: InteractiveSocketLike
  private readonly pending: string[] = []
  private reconnectTimer: number | null = null
  private reconnectAttempt = 0
  private closed = false

  constructor(
    private readonly workspaceId: string,
    private readonly factory: WebSocketFactory,
    private readonly handlers: InteractiveTerminalHandlers,
  ) {
    if (workspaceId.trim().length === 0) throw new Error('workspaceId 不能为空')
    this.socket = factory(workbenchWebSocketUrl(`/ws/workspaces/${encodeURIComponent(workspaceId)}/terminal`)) as InteractiveSocketLike
    this.bindSocket()
    handlers.onStateChange(this.socket.readyState)
  }

  sendInput(data: string): void {
    if (this.closed || data.length === 0) return
    this.send({ type: 'input', data })
  }

  resize(cols: number, rows: number): void {
    if (!Number.isInteger(cols) || cols < 2 || !Number.isInteger(rows) || rows < 1) return
    this.send({ type: 'resize', cols, rows })
  }

  interrupt(): void {
    this.send({ type: 'interrupt' })
  }

  close(): void {
    if (this.closed) return
    this.closed = true
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.pending.length = 0
    this.socket.onopen = null
    this.socket.onmessage = null
    this.socket.onerror = null
    this.socket.onclose = null
    if (this.socket.readyState < 2) this.socket.close(1000, 'client closed')
    this.handlers.onStateChange(3)
  }

  private send(frame: Record<string, unknown>): void {
    const payload = JSON.stringify(frame)
    if (this.socket.readyState === 1) {
      this.socket.send(payload)
      return
    }
    if (this.socket.readyState === 0 && this.pending.length < 256) this.pending.push(payload)
  }

  private flushPending(): void {
    while (this.pending.length > 0 && this.socket.readyState === 1) {
      this.socket.send(this.pending.shift() as string)
    }
  }

  private bindSocket(): void {
    this.socket.onopen = () => {
      this.reconnectAttempt = 0
      this.flushPending()
      this.handlers.onStateChange(this.socket.readyState)
    }
    this.socket.onclose = () => {
      this.handlers.onStateChange(this.socket.readyState)
      this.scheduleReconnect()
    }
    this.socket.onerror = () => this.handlers.onError(new Error('交互终端 WebSocket 连接失败'))
    this.socket.onmessage = (event) => this.handleMessage(event)
  }

  private scheduleReconnect(): void {
    if (this.closed || this.reconnectTimer !== null || this.reconnectAttempt >= 5) return
    const delay = Math.min(1000 * (2 ** this.reconnectAttempt), 10000)
    this.reconnectAttempt += 1
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      if (this.closed) return
      this.socket = this.factory(workbenchWebSocketUrl(`/ws/workspaces/${encodeURIComponent(this.workspaceId)}/terminal`)) as InteractiveSocketLike
      this.bindSocket()
      this.handlers.onStateChange(this.socket.readyState)
    }, delay)
  }

  private handleMessage(event: MessageEvent): void {
    try {
      const frame = decodeFrame(JSON.parse(String(event.data)) as unknown)
      if (frame.type === 'output') this.handlers.onOutput(frame.data)
      else if (frame.type === 'exit') this.handlers.onExit(frame.exitCode)
      else if (frame.type === 'error') this.handlers.onError(new Error(frame.message))
    } catch (error) {
      this.handlers.onError(asError(error))
      this.close()
    }
  }
}
