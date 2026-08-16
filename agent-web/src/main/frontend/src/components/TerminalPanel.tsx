import '@xterm/xterm/css/xterm.css'

import { Terminal as TerminalIcon } from 'lucide-react'
import { useEffect, useImperativeHandle, useRef, useState, type Ref } from 'react'
import { useAppearance } from '../appearance/AppearanceProvider'
import { getTerminalTheme } from '../appearance/terminalTheme'
import { InteractiveTerminalSession } from '../terminal/InteractiveTerminalSession'
import type { WebSocketFactory } from '../terminal/TerminalSession'

export interface TerminalPanelHandle {
  reset(): void
  write(text: string): void
}

interface TerminalPanelProps {
  active: boolean
  workspaceId: string | null
  terminalRef: Ref<TerminalPanelHandle>
}

function defaultWebSocketFactory(url: string) {
  return new WebSocket(url)
}

/** 提供可输入的工作区 PTY，并保留 Agent Run 的 ANSI 日志输出。 */
export function TerminalPanel({ active, workspaceId, terminalRef }: TerminalPanelProps) {
  const { preferences, resolvedColorMode } = useAppearance()
  const hostRef = useRef<HTMLDivElement | null>(null)
  const xtermRef = useRef<import('@xterm/xterm').Terminal | null>(null)
  const interactiveSessionRef = useRef<InteractiveTerminalSession | null>(null)
  const bufferRef = useRef('')
  const [transcript, setTranscript] = useState('')
  const [connectionState, setConnectionState] = useState<'disconnected' | 'connecting' | 'connected' | 'closed'>('disconnected')
  const [terminalError, setTerminalError] = useState<string | null>(null)

  useImperativeHandle(terminalRef, () => ({
    reset() {
      bufferRef.current = ''
      setTranscript('')
      xtermRef.current?.reset()
    },
    write(text: string) {
      bufferRef.current += text
      setTranscript((current) => current + text)
      xtermRef.current?.write(text)
    },
  }), [])

  useEffect(() => {
    const host = hostRef.current
    if (host === null || typeof ResizeObserver === 'undefined') return undefined
    let disposed = false
    let observer: ResizeObserver | null = null
    void Promise.all([import('@xterm/xterm'), import('@xterm/addon-fit')]).then(
      ([{ Terminal }, { FitAddon }]) => {
        if (disposed) return
        const terminal = new Terminal({
          convertEol: false,
          cursorBlink: true,
          disableStdin: workspaceId === null,
          fontFamily: '"Cascadia Mono", "SFMono-Regular", Consolas, monospace',
          fontSize: 13,
          lineHeight: 1.25,
          theme: getTerminalTheme(resolvedColorMode, preferences.themePreset, preferences.accentColor),
        })
        const fitAddon = new FitAddon()
        terminal.loadAddon(fitAddon)
        terminal.open(host)
        xtermRef.current = terminal
        if (bufferRef.current.length > 0) terminal.write(bufferRef.current)
        const append = (text: string) => {
          bufferRef.current += text
          setTranscript((current) => current + text)
          terminal.write(text)
        }
        let dataDisposable: { dispose(): void } | null = null
        if (workspaceId !== null) {
          setConnectionState('connecting')
          const session = new InteractiveTerminalSession(
            workspaceId,
            defaultWebSocketFactory as WebSocketFactory,
            {
              onOutput: append,
              onStateChange: (readyState) => {
                if (readyState === 0) setConnectionState('connecting')
                else if (readyState === 1) setConnectionState('connected')
                else setConnectionState('closed')
              },
              onExit: (exitCode) => append(`\r\n[终端进程已退出，exit ${exitCode ?? 'unknown'}]\r\n`),
              onError: (error) => setTerminalError(error.message),
            },
          )
          interactiveSessionRef.current = session
          dataDisposable = terminal.onData((data) => session.sendInput(data))
        } else {
          setConnectionState('disconnected')
        }
        const fit = () => {
          if (host.clientWidth > 0 && host.clientHeight > 0) {
            fitAddon.fit()
            interactiveSessionRef.current?.resize(terminal.cols, terminal.rows)
          }
        }
        observer = new ResizeObserver(fit)
        observer.observe(host)
        fit()
      },
    )
    return () => {
      disposed = true
      observer?.disconnect()
      interactiveSessionRef.current?.close()
      interactiveSessionRef.current = null
      xtermRef.current?.dispose()
      xtermRef.current = null
    }
  }, [preferences.accentColor, preferences.themePreset, resolvedColorMode, workspaceId])

  useEffect(() => {
    if (active) xtermRef.current?.focus()
  }, [active])

  return (
    <section className="tool-panel terminal-panel" data-testid="terminal-panel">
      <div className="tool-panel-bar terminal-bar">
        <div className="panel-title"><TerminalIcon aria-hidden="true" size={16} /><span>WORKSPACE TERMINAL</span></div>
        <span className={`terminal-mode is-${connectionState}`}>{workspaceId === null ? '选择工作区后可输入' : `PTY / ${connectionState}`}</span>
      </div>
      <div className="xterm-host" ref={hostRef} aria-label="交互式工作区终端" />
      {terminalError === null ? null : <p className="terminal-error" role="alert">{terminalError}</p>}
      <pre className="terminal-transcript" aria-live="polite">{transcript}</pre>
    </section>
  )
}
