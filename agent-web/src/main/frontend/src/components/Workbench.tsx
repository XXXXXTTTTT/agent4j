import { Code2, Eye, Terminal } from 'lucide-react'
import { lazy, Suspense, useState } from 'react'

import type { UseRunWorkbenchResult } from '../hooks/useRunWorkbench'
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

type WorkbenchTab = 'code' | 'terminal' | 'review'

const TABS: Array<{ id: WorkbenchTab; label: string; icon: typeof Code2 }> = [
  { id: 'code', label: '代码', icon: Code2 },
  { id: 'terminal', label: '终端', icon: Terminal },
  { id: 'review', label: '审查', icon: Eye },
]

/** 编排启动、执行证据和状态信号三个工作区列。 */
export function Workbench({ controller, onTerminalReady }: WorkbenchProps) {
  const [activeTab, setActiveTab] = useState<WorkbenchTab>('code')
  const [reviewOpened, setReviewOpened] = useState(false)
  const diff = controller.run?.state.variables['coder.unifiedDiff']

  return (
    <div className="workbench-shell" data-testid="workbench-shell">
      <header className="workbench-header">
        <div className="brand-lockup">
          <span className="brand-mark">A4J</span>
          <div><p className="section-kicker">AGENT RUNTIME SYSTEM</p><h1>Workstation</h1></div>
        </div>
        <div className="header-run-state">
          <span className="header-dot" aria-hidden="true" />
          {controller.run?.graphId ?? '等待图任务'}
        </div>
      </header>

      <div className="workbench-grid">
        <aside className="left-rail">
          <RunLauncher controller={controller} />
          {controller.run === null ? null : (
            <ApprovalDialog run={controller.run} decide={controller.decide} />
          )}
        </aside>

        <main className="workspace-main" data-testid="workspace-main">
          <div className="workspace-heading">
            <div>
              <p className="section-kicker">EXECUTION SURFACE</p>
              <h2>工作区</h2>
            </div>
            <span className="checkpoint-label">
              {controller.run === null ? '未选择 Run' : `Checkpoint v${controller.run.version}`}
            </span>
          </div>
          <div className="workspace-tabs" role="tablist" aria-label="工作区视图">
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
          </div>
        </main>

        <TraceTimeline events={controller.traceEvents} connectionState={controller.connectionState} />
      </div>
      {controller.error === null ? null : <p className="global-error" role="alert">{controller.error.message}</p>}
    </div>
  )
}
