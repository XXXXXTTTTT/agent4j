import '@xterm/xterm/css/xterm.css'

import { Terminal as TerminalIcon } from 'lucide-react'
import { useEffect, useImperativeHandle, useRef, useState, type Ref } from 'react'

export interface TerminalPanelHandle {
  reset(): void
  write(text: string): void
}

interface TerminalPanelProps {
  active: boolean
  terminalRef: Ref<TerminalPanelHandle>
}

/** 保存 ANSI 原文，并负责 xterm、FitAddon 与 ResizeObserver 的成对释放。 */
export function TerminalPanel({ active, terminalRef }: TerminalPanelProps) {
  const hostRef = useRef<HTMLDivElement | null>(null)
  const xtermRef = useRef<import('@xterm/xterm').Terminal | null>(null)
  const bufferRef = useRef('')
  const [transcript, setTranscript] = useState('')

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
          cursorBlink: false,
          disableStdin: true,
          fontFamily: '"Cascadia Mono", "SFMono-Regular", Consolas, monospace',
          fontSize: 13,
          lineHeight: 1.25,
          theme: {
            background: '#11120f', foreground: '#f1f0e9', cursor: '#8ab4ff',
            black: '#171814', brightBlack: '#4a4d43', red: '#ef8991', brightRed: '#ef8991',
            green: '#71c58c', brightGreen: '#71c58c', yellow: '#e3b86b', brightYellow: '#e3b86b',
            blue: '#8ab4ff', brightBlue: '#8ab4ff', magenta: '#c6a0dc', brightMagenta: '#c6a0dc',
            cyan: '#8fbfc1', brightCyan: '#8fbfc1', white: '#bebdb4', brightWhite: '#f1f0e9',
          },
        })
        const fitAddon = new FitAddon()
        terminal.loadAddon(fitAddon)
        terminal.open(host)
        xtermRef.current = terminal
        if (bufferRef.current.length > 0) terminal.write(bufferRef.current)
        const fit = () => {
          if (host.clientWidth > 0 && host.clientHeight > 0) fitAddon.fit()
        }
        observer = new ResizeObserver(fit)
        observer.observe(host)
        fit()
      },
    )
    return () => {
      disposed = true
      observer?.disconnect()
      xtermRef.current?.dispose()
      xtermRef.current = null
    }
  }, [])

  useEffect(() => {
    if (active) window.dispatchEvent(new Event('resize'))
  }, [active])

  return (
    <section className="tool-panel terminal-panel" data-testid="terminal-panel">
      <div className="tool-panel-bar terminal-bar">
        <div className="panel-title"><TerminalIcon aria-hidden="true" size={16} /><span>PTY OUTPUT</span></div>
        <span className="terminal-mode">ANSI / UTF-8</span>
      </div>
      <div className="xterm-host" ref={hostRef} aria-label="终端输出" />
      <pre className="terminal-transcript">{transcript}</pre>
    </section>
  )
}
