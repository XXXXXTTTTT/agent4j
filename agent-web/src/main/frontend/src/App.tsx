import { useRef } from 'react'

import { useRunWorkbench } from './hooks/useRunWorkbench'
import type { TerminalPanelHandle } from './components/TerminalPanel'
import { Workbench } from './components/Workbench'

/** 将浏览器工作台与 Run 协调 Hook 连接起来。 */
export function App() {
  const terminalRef = useRef<TerminalPanelHandle | null>(null)
  const controller = useRunWorkbench({
    onTerminalReset: () => terminalRef.current?.reset(),
    onTerminalData: (text) => terminalRef.current?.write(text),
  })
  return (
    <Workbench
      controller={controller}
      onTerminalReady={(terminal) => { terminalRef.current = terminal }}
    />
  )
}
