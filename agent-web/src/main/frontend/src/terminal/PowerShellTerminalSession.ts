export interface NativeTerminalSocket {
  readonly readyState: number
  onopen: ((event: Event) => void) | null
  onmessage: ((event: MessageEvent) => void) | null
  onerror: ((event: Event) => void) | null
  onclose: ((event: CloseEvent) => void) | null
  send(data: string): void
  close(code?: number, reason?: string): void
}

export type NativeTerminalSocketFactory = (url: string) => NativeTerminalSocket

export interface PowerShellTerminalHandlers {
  onOutput(text: string): void
  onStateChange(readyState: number): void
  onError(error: Error): void
}

declare global {
  interface Window {
    __AGENT_TERMINAL_BRIDGE_URL__?: string
  }
}

function bridgeUrl(workspacePath: string): string {
  const base = window.__AGENT_TERMINAL_BRIDGE_URL__ ?? 'ws://127.0.0.1:8090'
  const url = new URL('/ws/terminal', base)
  url.searchParams.set('workspacePath', workspacePath)
  return url.toString()
}

/** 连接 Windows 宿主机 node-pty 桥接器，并原样转发 xterm ANSI 数据。 */
export class PowerShellTerminalSession {
  private socket: NativeTerminalSocket
  private readonly pending: string[] = []
  private reconnectTimer: number | null = null
  private reconnectAttempt = 0
  private closed = false
  private lastSize: { cols: number; rows: number } | null = null

  constructor(
    private readonly workspacePath: string,
    private readonly factory: NativeTerminalSocketFactory,
    private readonly handlers: PowerShellTerminalHandlers,
  ) {
    if (workspacePath.trim().length === 0) throw new Error('workspacePath 不能为空')
    this.socket = factory(bridgeUrl(workspacePath))
    this.bindSocket()
    handlers.onStateChange(this.socket.readyState)
  }

  sendInput(data: string): void {
    if (this.closed || data.length === 0) return
    this.send({ type: 'input', data })
  }

  resize(cols: number, rows: number): void {
    if (!Number.isInteger(cols) || cols < 2 || !Number.isInteger(rows) || rows < 1) return
    this.lastSize = { cols, rows }
    this.send({ type: 'resize', cols, rows })
  }

  close(): void {
    if (this.closed) return
    this.closed = true
    if (this.reconnectTimer !== null) window.clearTimeout(this.reconnectTimer)
    this.reconnectTimer = null
    this.pending.length = 0
    this.socket.onopen = null
    this.socket.onmessage = null
    this.socket.onerror = null
    this.socket.onclose = null
    if (this.socket.readyState < 2) this.socket.close(1000, 'client closed')
    this.handlers.onStateChange(3)
  }

  private bindSocket(): void {
    this.socket.onopen = () => {
      this.reconnectAttempt = 0
      this.flushPending()
      if (this.lastSize !== null) this.send({ type: 'resize', ...this.lastSize })
      this.handlers.onStateChange(this.socket.readyState)
    }
    this.socket.onmessage = (event) => this.handlers.onOutput(String(event.data))
    this.socket.onerror = () => this.handlers.onError(new Error('PowerShell PTY 桥接连接失败'))
    this.socket.onclose = () => {
      this.handlers.onStateChange(this.socket.readyState)
      this.scheduleReconnect()
    }
  }

  private send(frame: Record<string, unknown>): void {
    const payload = JSON.stringify(frame)
    if (this.socket.readyState === 1) {
      this.socket.send(payload)
      return
    }
    if (this.socket.readyState !== 1 && this.pending.length < 256) this.pending.push(payload)
  }

  private flushPending(): void {
    while (this.pending.length > 0 && this.socket.readyState === 1) this.socket.send(this.pending.shift() as string)
  }

  private scheduleReconnect(): void {
    if (this.closed || this.reconnectTimer !== null || this.reconnectAttempt >= 5) return
    const delay = Math.min(1000 * (2 ** this.reconnectAttempt), 10000)
    this.reconnectAttempt += 1
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      if (this.closed) return
      this.socket = this.factory(bridgeUrl(this.workspacePath))
      this.bindSocket()
      this.handlers.onStateChange(this.socket.readyState)
    }, delay)
  }
}
