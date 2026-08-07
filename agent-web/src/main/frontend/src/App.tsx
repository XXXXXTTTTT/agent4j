import { useEffect, useRef } from 'react'

import { useRunWorkbench } from './hooks/useRunWorkbench'
import { useConversationWorkspace } from './hooks/useConversationWorkspace'
import type { TerminalPanelHandle } from './components/TerminalPanel'
import { Workbench } from './components/Workbench'

/** 将浏览器工作台与 Run 协调 Hook 连接起来。 */
export function App() {
  const terminalRef = useRef<TerminalPanelHandle | null>(null)
  const controller = useRunWorkbench({
    onTerminalReset: () => terminalRef.current?.reset(),
    onTerminalData: (text) => terminalRef.current?.write(text),
  })
  const conversation = useConversationWorkspace()
  const latestRunId = conversation.turns.at(-1)?.runId ?? null
  useEffect(() => {
    if (latestRunId === null || controller.run?.runId === latestRunId) return
    void controller.followRun(latestRunId).catch(() => undefined)
  }, [controller.followRun, controller.run?.runId, latestRunId])
  return (
    <Workbench
      controller={controller}
      conversation={conversation}
      onTerminalReady={(terminal) => { terminalRef.current = terminal }}
    />
  )
}
