import { Activity, Code2, Globe2, RefreshCw, ShieldCheck, Terminal } from 'lucide-react'
import { lazy, Suspense, useState } from 'react'
import { useEffect } from 'react'

import type { UseRunWorkbenchResult } from '../hooks/useRunWorkbench'
import { AgentConversation } from './AgentConversation'
import { ApprovalDialog } from './ApprovalDialog'
import { ConversationComposer } from './ConversationComposer'
import { ConversationSidebar } from './ConversationSidebar'
import { RunLauncher } from './RunLauncher'
import { TerminalPanel, type TerminalPanelHandle } from './TerminalPanel'
import { TraceTimeline } from './TraceTimeline'
import type { UseConversationWorkspaceResult } from '../hooks/useConversationWorkspace'
import { CapabilityWorkbenchRuntime } from './CapabilityWorkbenchRuntime'

const CodeDiffPanel = lazy(() => import('./CodeDiffPanel').then((module) => ({
  default: module.CodeDiffPanel,
})))
const ReviewEvidencePanel = lazy(() => import('./ReviewEvidencePanel').then((module) => ({
  default: module.ReviewEvidencePanel,
})))

interface WorkbenchProps {
  controller: UseRunWorkbenchResult
  onTerminalReady(terminal: TerminalPanelHandle | null): void
  conversation?: UseConversationWorkspaceResult
}

type WorkbenchTab = 'code' | 'terminal' | 'review' | 'trace' | 'capability'

const TABS: Array<{ id: WorkbenchTab; label: string; icon: typeof Code2 }> = [
  { id: 'code', label: '代码变更', icon: Code2 },
  { id: 'terminal', label: '终端', icon: Terminal },
  { id: 'review', label: '浏览器', icon: Globe2 },
  { id: 'trace', label: 'Trace', icon: Activity },
  { id: 'capability', label: '能力', icon: ShieldCheck },
]

/** 编排对话式任务流与执行证据检查器。 */
export function Workbench({ controller, onTerminalReady, conversation }: WorkbenchProps) {
  const [activeTab, setActiveTab] = useState<WorkbenchTab>('code')
  const [reviewOpened, setReviewOpened] = useState(false)
  const belongsToConversation = conversation === undefined
    || controller.run === null
    || controller.run.graphId === 'governed-cli'
    || conversation.turns.some((turn) => turn.runId === controller.run?.runId)
  const run = belongsToConversation ? controller.run : null
  const history = belongsToConversation ? controller.history : []
  const traceEvents = belongsToConversation ? controller.traceEvents : []
  const diff = run?.state.variables['coder.unifiedDiff']
  const latestTrace = traceEvents.at(-1)
  const currentNode = latestTrace?.type === 'NODE_STARTED'
    ? latestTrace.nodeName
    : run?.nextNode ?? null

  useEffect(() => {
    if (conversation === undefined || run === null) return
    if (run.status !== 'COMPLETED' && run.status !== 'FAILED' && run.status !== 'REJECTED') return
    void conversation.reload().catch(() => undefined)
  }, [conversation?.reload, run?.runId, run?.status])

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

      <div className={`agent-layout ${conversation === undefined ? '' : 'has-conversation-sidebar'}`}>
        {conversation === undefined ? null : <ConversationSidebar controller={conversation} />}
        <main className="conversation-column" data-testid="workspace-main">
          <div className="conversation-scroll">
            <AgentConversation run={run} currentNode={currentNode} turns={conversation?.turns} />
            {run === null ? null : <ApprovalDialog run={run} decide={controller.decide} />}
          </div>
          {conversation === undefined ? <RunLauncher controller={controller} /> : <ConversationComposer conversation={conversation} runController={controller} />}
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
                  <ReviewEvidencePanel run={run} history={history} />
                </Suspense>
              ) : null}
            </div>
            <div id="trace-view" role="tabpanel" hidden={activeTab !== 'trace'}>
              <TraceTimeline
                events={traceEvents}
                connectionState={belongsToConversation
                  ? controller.connectionState
                  : { trace: null, terminal: null }}
                persistedNodes={run?.state.trace ?? []}
              />
            </div>
            <div id="capability-view" role="tabpanel" hidden={activeTab !== 'capability'}>
              {conversation?.activeWorkspace == null ? <div className="empty-tool-state">请选择工作区后管理能力</div> : <CapabilityWorkbenchRuntime workspaceId={conversation.activeWorkspace.workspaceId} />}
            </div>
          </div>
        </aside>
      </div>
      {controller.error === null ? null : <p className="global-error" role="alert">{controller.error.message}</p>}
    </div>
  )
}
