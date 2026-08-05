import { Activity, Code2, Globe2, RefreshCw, Terminal } from 'lucide-react'
import { lazy, Suspense, useState } from 'react'

import type { UseRunWorkbenchResult } from '../hooks/useRunWorkbench'
import { AgentConversation } from './AgentConversation'
import { ApprovalDialog } from './ApprovalDialog'
import { RunLauncher } from './RunLauncher'
import { TerminalPanel, type TerminalPanelHandle } from './TerminalPanel'
import { TraceTimeline } from './TraceTimeline'

const CodeDiffPanel = lazy(() => import('./CodeDiffPanel').then((module) => ({
  default: module.CodeDiffPanel,
})))
const ReviewEvidencePanel = lazy(() => import('./ReviewEvidencePanel').then((module) => ({
  default: module.ReviewEvidencePanel,
})))

interface WorkbenchProps {
  controller: UseRunWorkbenchResult
  onTerminalReady(terminal: TerminalPanelHandle | null): void
}

type WorkbenchTab = 'code' | 'terminal' | 'review' | 'trace'

const TABS: Array<{ id: WorkbenchTab; label: string; icon: typeof Code2 }> = [
  { id: 'code', label: '代码变更', icon: Code2 },
  { id: 'terminal', label: '终端', icon: Terminal },
  { id: 'review', label: '浏览器', icon: Globe2 },
  { id: 'trace', label: 'Trace', icon: Activity },
]

/** 编排对话式任务流与执行证据检查器。 */
export function Workbench({ controller, onTerminalReady }: WorkbenchProps) {
  const [activeTab, setActiveTab] = useState<WorkbenchTab>('code')
  const [reviewOpened, setReviewOpened] = useState(false)
  const diff = controller.run?.state.variables['coder.unifiedDiff']
  const latestTrace = controller.traceEvents.at(-1)
  const currentNode = latestTrace?.type === 'NODE_STARTED'
    ? latestTrace.nodeName
    : controller.run?.nextNode ?? null
  const run = controller.run

  return (
    <div className="workbench-shell" data-testid="workbench-shell">
      <header className="workbench-header">
        <div className="brand-lockup">
          <span className="brand-mark">A4J</span>
          <div><h1>Agent4J</h1><p>Code Agent</p></div>
        </div>
        <div className="header-run-state" aria-live="polite">
          {run === null ? <span>新任务</span> : (
            <>
              <span className={`header-dot status-${run.status.toLowerCase()}`} aria-hidden="true" />
              <span>{run.graphId}</span>
              <code>v{run.version}</code>
              <button
                className="icon-button"
                type="button"
                title="刷新任务"
                aria-label="刷新任务"
                onClick={() => void controller.reload().catch(() => undefined)}
              >
                <RefreshCw aria-hidden="true" size={16} />
              </button>
            </>
          )}
        </div>
      </header>

      <div className="agent-layout">
        <main className="conversation-column" data-testid="workspace-main">
          <div className="conversation-scroll">
            <AgentConversation run={run} currentNode={currentNode} />
            {run === null ? null : <ApprovalDialog run={run} decide={controller.decide} />}
          </div>
          <RunLauncher controller={controller} />
        </main>

        <aside className="execution-inspector" aria-label="执行检查器">
          <div className="inspector-heading">
            <div>
              <p className="section-kicker">RUN EVIDENCE</p>
              <h2>执行详情</h2>
            </div>
            <span className="checkpoint-label">{run === null ? '尚未运行' : `Checkpoint ${run.version}`}</span>
          </div>
          <div className="workspace-tabs" role="tablist" aria-label="检查器视图">
            {TABS.map(({ id, label, icon: Icon }) => (
              <button
                key={id}
                type="button"
                role="tab"
                aria-selected={activeTab === id}
                aria-controls={`${id}-view`}
                onClick={() => {
                  setActiveTab(id)
                  if (id === 'review') setReviewOpened(true)
                }}
              >
                <Icon aria-hidden="true" size={16} />
                {label}
              </button>
            ))}
          </div>
          <div className="workspace-views">
            <div id="code-view" role="tabpanel" hidden={activeTab !== 'code'}>
              <Suspense fallback={<div className="empty-tool-state">正在加载编辑器</div>}>
                <CodeDiffPanel unifiedDiff={diff} />
              </Suspense>
            </div>
            <div id="terminal-view" role="tabpanel" hidden={activeTab !== 'terminal'}>
              <TerminalPanel active={activeTab === 'terminal'} terminalRef={onTerminalReady} />
            </div>
            <div id="review-view" role="tabpanel" hidden={activeTab !== 'review'}>
              {reviewOpened ? (
                <Suspense fallback={<div className="empty-tool-state">正在加载编辑器</div>}>
                  <ReviewEvidencePanel run={controller.run} history={controller.history} />
                </Suspense>
              ) : null}
            </div>
            <div id="trace-view" role="tabpanel" hidden={activeTab !== 'trace'}>
              <TraceTimeline
                events={controller.traceEvents}
                connectionState={controller.connectionState}
                persistedNodes={run?.state.trace ?? []}
              />
            </div>
          </div>
        </aside>
      </div>
      {controller.error === null ? null : <p className="global-error" role="alert">{controller.error.message}</p>}
    </div>
  )
}
