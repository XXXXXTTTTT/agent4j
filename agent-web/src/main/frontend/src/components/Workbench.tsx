import { Activity, Code2, Globe2, MessageSquare, PanelRightClose, PanelRightOpen, RefreshCw, ShieldCheck, Terminal, FolderGit2 } from 'lucide-react'
import { lazy, Suspense, useEffect, useRef, useState } from 'react'

import type { UseRunWorkbenchResult } from '../hooks/useRunWorkbench'
import { useChatScrollSync } from '../hooks/useChatScrollSync'
import { AgentConversation } from './AgentConversation'
import { ApprovalDialog } from './ApprovalDialog'
import { ConversationComposer } from './ConversationComposer'
import { ConversationSidebar } from './ConversationSidebar'
import { RunLauncher } from './RunLauncher'
import { TerminalPanel, type TerminalPanelHandle } from './TerminalPanel'
import { TraceTimeline } from './TraceTimeline'
import type { UseConversationWorkspaceResult } from '../hooks/useConversationWorkspace'
import { CapabilityWorkbenchRuntime } from './CapabilityWorkbenchRuntime'
import { WorkspaceExplorerPanel } from './WorkspaceExplorerPanel'
import { WorkbenchDockLayout } from './WorkbenchDockLayout'
import { ChatTimelineMinimap, type ChatTimelineEntry } from './ChatTimelineMinimap'
import type { ConversationTurn } from '../api/contracts'

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
type ActivityView = 'conversation' | 'project' | 'evidence' | 'capability'

const TABS: Array<{ id: WorkbenchTab; label: string; icon: typeof Code2 }> = [
  { id: 'code', label: '代码变更', icon: Code2 },
  { id: 'terminal', label: '终端', icon: Terminal },
  { id: 'review', label: '浏览器', icon: Globe2 },
  { id: 'trace', label: 'Trace', icon: Activity },
  { id: 'capability', label: '能力', icon: ShieldCheck },
]

const ACTIVITY_ITEMS: Array<{ id: ActivityView; label: string; icon: typeof Code2 }> = [
  { id: 'conversation', label: '对话', icon: MessageSquare },
  { id: 'project', label: '项目', icon: FolderGit2 },
  { id: 'evidence', label: '运行证据', icon: Activity },
  { id: 'capability', label: '能力', icon: ShieldCheck },
]

function turnStatus(status: ConversationTurn['status']): ChatTimelineEntry['status'] {
  if (status === 'FAILED') return 'failed'
  if (status === 'RUNNING') return 'running'
  if (status === 'PENDING') return 'pending'
  return 'success'
}

function buildTimelineEntries(turns: ConversationTurn[], run: UseRunWorkbenchResult['run']): ChatTimelineEntry[] {
  const entries: ChatTimelineEntry[] = []
  for (const turn of turns) {
    const status = turnStatus(turn.status)
    entries.push({ id: `${turn.turnId}-user`, label: `第 ${turn.turnIndex + 1} 轮 · 你`, summary: turn.userContent.slice(0, 80) || '空消息', role: 'user', status })
    entries.push({ id: `${turn.turnId}-agent`, label: `第 ${turn.turnIndex + 1} 轮 · Agent`, summary: turn.error ?? turn.assistantContent?.slice(0, 80) ?? '正在处理', role: 'agent', status })
  }
  if (run === null) return entries
  const variables = run.state.variables
  const task = variables['demo.task'] ?? variables['planner.task']
  if (task !== undefined) entries.push({ id: `${run.runId}-user`, label: '当前任务 · 你', summary: task.slice(0, 80), role: 'user', status: 'success' })
  entries.push({ id: `${run.runId}-agent`, label: '当前执行 · Agent', summary: variables['final_response']?.slice(0, 80) ?? '正在执行任务', role: 'agent', status: run.status === 'FAILED' ? 'failed' : run.status === 'COMPLETED' ? 'success' : 'running' })
  if (variables['tool.request'] !== undefined || variables['tool.result'] !== undefined || variables['tool.error'] !== undefined) {
    entries.push({ id: `${run.runId}-tool`, label: '工具检查点', summary: variables['tool.error'] ?? variables['tool.result']?.slice(0, 80) ?? '调用工具', role: 'tool', status: variables['tool.error'] === undefined ? 'success' : 'failed' })
  }
  if (variables['coder.request'] !== undefined || variables['coder.updatedFiles'] !== undefined) entries.push({ id: `${run.runId}-coder`, label: '代码变更', summary: variables['coder.summary']?.slice(0, 80) ?? '生成代码变更', role: 'agent', status: variables['coder.error'] === undefined ? 'success' : 'failed' })
  if (variables['ops.command'] !== undefined) entries.push({ id: `${run.runId}-ops`, label: '测试执行', summary: variables['ops.command'], role: 'tool', status: variables['ops.exitCode'] === undefined ? 'running' : variables['ops.exitCode'] === '0' ? 'success' : 'failed' })
  if (variables['reviewer.summary'] !== undefined || run.error !== null) entries.push({ id: `${run.runId}-result`, label: '执行结果', summary: variables['reviewer.summary'] ?? run.error ?? '执行完成', role: 'agent', status: run.error === null ? 'success' : 'failed' })
  return entries
}

/** 编排对话式任务流与执行证据检查器。 */
export function Workbench({ controller, onTerminalReady, conversation }: WorkbenchProps) {
  const [activeTab, setActiveTab] = useState<WorkbenchTab>('code')
  const [activeActivity, setActiveActivity] = useState<ActivityView>('conversation')
  const [inspectorOpen, setInspectorOpen] = useState(() =>
    typeof window.matchMedia !== 'function' || !window.matchMedia('(max-width: 1280px)').matches,
  )
  const [focusInspectorAfterOpen, setFocusInspectorAfterOpen] = useState(false)
  const inspectorHeadingRef = useRef<HTMLHeadingElement>(null)
  const [reviewOpened, setReviewOpened] = useState(false)
  const chatScroll = useChatScrollSync()
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
  const timelineEntries = buildTimelineEntries(conversation?.turns ?? [], run)
  function selectActivity(view: ActivityView): void {
    setActiveActivity(view)
    if (view === 'conversation' || view === 'project') setInspectorOpen(false)
    if (view === 'evidence') { setActiveTab('trace'); setInspectorOpen(true); setFocusInspectorAfterOpen(true) }
    if (view === 'capability') { setActiveTab('capability'); setInspectorOpen(true); setFocusInspectorAfterOpen(true) }
  }

  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return undefined
    const media = window.matchMedia('(max-width: 1280px)')
    const closeForNarrowLayout = () => { if (media.matches) setInspectorOpen(false) }
    media.addEventListener('change', closeForNarrowLayout)
    return () => media.removeEventListener('change', closeForNarrowLayout)
  }, [])

  useEffect(() => {
    if (!inspectorOpen || !focusInspectorAfterOpen) return
    inspectorHeadingRef.current?.focus()
    setFocusInspectorAfterOpen(false)
  }, [focusInspectorAfterOpen, inspectorOpen])

  useEffect(() => {
    if (conversation === undefined || run === null) return
    if (run.status !== 'COMPLETED' && run.status !== 'FAILED' && run.status !== 'REJECTED') return
    void conversation.reload().catch(() => undefined)
  }, [conversation?.reload, run?.runId, run?.status])

  const activityPanel = conversation === undefined ? null : (
    <nav className="workbench-activity-bar" role="navigation" aria-label="工作台活动栏">
      {ACTIVITY_ITEMS.map(({ id, label, icon: Icon }) => (
        <button
          key={id}
          type="button"
          aria-label={label}
          aria-current={activeActivity === id ? 'page' : undefined}
          title={label}
          onClick={() => selectActivity(id)}
        >
          <Icon aria-hidden="true" size={18} />
          <span className="workbench-activity-label">{label}</span>
        </button>
      ))}
    </nav>
  )

  const sidebarPanel = conversation === undefined ? null : (
    <div id="activity-project-panel" className="workbench-project-column" data-active-context={activeActivity}>
      {activeActivity === 'project' && conversation.activeWorkspace !== null
        ? <WorkspaceExplorerPanel workspaceId={conversation.activeWorkspace.workspaceId} />
        : <ConversationSidebar controller={conversation} connectionState={belongsToConversation ? controller.connectionState : { trace: null, terminal: null }} activeContext={activeActivity} />}
    </div>
  )

  const conversationPanel = (
    <main id="activity-conversation-panel" className="conversation-column" data-testid="workspace-main" data-active-context={activeActivity}>
      <div className="conversation-scroll-shell">
        <div className="conversation-scroll" ref={chatScroll.containerRef}>
          <AgentConversation run={run} currentNode={currentNode} turns={conversation?.turns} traceEvents={traceEvents} />
          {run === null ? null : <ApprovalDialog run={run} decide={controller.decide} />}
        </div>
        <ChatTimelineMinimap
          messages={timelineEntries}
          scrollProgress={chatScroll.scrollProgress}
          activeMessageId={chatScroll.activeMessageId}
          onSelectTurn={chatScroll.scrollToMessage}
          onProgressChange={(progress) => {
            const container = chatScroll.containerRef.current
            if (container === null) return
            container.scrollTop = progress * Math.max(0, container.scrollHeight - container.clientHeight)
          }}
        />
      </div>
      {conversation === undefined ? <RunLauncher controller={controller} /> : <ConversationComposer conversation={conversation} runController={controller} />}
    </main>
  )

  const inspectorPanel = (
    <aside id="execution-inspector" className="execution-inspector" aria-label="执行检查器" data-active-context={activeActivity} hidden={!inspectorOpen}>
      <div className="inspector-heading">
        <div>
          <p className="section-kicker">RUN EVIDENCE</p>
          <h2 ref={inspectorHeadingRef} tabIndex={-1}>执行详情</h2>
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
          <TerminalPanel
            active={inspectorOpen && activeTab === 'terminal'}
            workspacePath={conversation?.activeWorkspace?.workspacePath ?? null}
            terminalRef={onTerminalReady}
          />
        </div>
        <div id="review-view" role="tabpanel" hidden={activeTab !== 'review'}>
          {reviewOpened ? (
            <Suspense fallback={<div className="empty-tool-state">正在加载编辑器</div>}>
              <ReviewEvidencePanel run={run} history={history} />
            </Suspense>
          ) : null}
        </div>
        <div id="trace-view" role="tabpanel" hidden={activeTab !== 'trace'}>
          {inspectorOpen ? <TraceTimeline
            events={traceEvents}
            connectionState={belongsToConversation
              ? controller.connectionState
              : { trace: null, terminal: null }}
            persistedNodes={run?.state.trace ?? []}
          /> : null}
        </div>
        <div id="capability-view" role="tabpanel" hidden={activeTab !== 'capability'}>
          {conversation?.activeWorkspace == null ? <div className="empty-tool-state">请选择工作区后管理能力</div> : <CapabilityWorkbenchRuntime workspaceId={conversation.activeWorkspace.workspaceId} />}
        </div>
      </div>
    </aside>
  )

  return (
    <div className="workbench-shell" data-testid="workbench-shell" data-inspector-open={inspectorOpen} data-active-activity={activeActivity}>
      <header className="workbench-header">
        <div className="brand-lockup">
          <span className="brand-mark">A4J</span>
          <div><h1>Agent4J</h1><p>Code Agent</p></div>
        </div>
        {conversation === undefined ? null : <p className="header-context">{conversation.activeWorkspace?.displayName ?? '未选择工作区'} <span>/</span> {conversation.activeConversation?.title ?? '新会话'}</p>}
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
          <button
            className="icon-button inspector-toggle"
            type="button"
            aria-controls="execution-inspector"
            aria-expanded={inspectorOpen}
            aria-label={inspectorOpen ? '收纳检查器' : '展开检查器'}
            title={inspectorOpen ? '收纳检查器' : '展开检查器'}
            onClick={() => {
              setInspectorOpen((open) => {
                if (!open) setFocusInspectorAfterOpen(true)
                return !open
              })
            }}
          >
            {inspectorOpen ? <PanelRightClose aria-hidden="true" size={16} /> : <PanelRightOpen aria-hidden="true" size={16} />}
          </button>
        </div>
      </header>

      <div className={`agent-layout ${conversation === undefined ? '' : 'has-conversation-sidebar workbench-dock-host'}`}>
        {conversation === undefined ? <>
          {conversationPanel}
          {inspectorPanel}
        </> : (
          <WorkbenchDockLayout
            inspectorOpen={inspectorOpen}
            onInspectorOpenChange={setInspectorOpen}
            panels={{
              activity: activityPanel,
              sidebar: sidebarPanel,
              conversation: conversationPanel,
              inspector: inspectorPanel,
            }}
          />
        )}
      </div>
      {controller.error === null ? null : <p className="global-error" role="alert">{controller.error.message}</p>}
    </div>
  )
}
