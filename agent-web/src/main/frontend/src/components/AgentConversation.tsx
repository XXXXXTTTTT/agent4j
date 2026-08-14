import {
  Bot,
  Check,
  Circle,
  CircleAlert,
  Code2,
  Image as ImageIcon,
  LoaderCircle,
  Terminal,
  User,
} from 'lucide-react'

import type { ChatMessage, ConversationTurn, RunView, TraceEvent } from '../api/contracts'
import { MarkdownMessage } from './MarkdownMessage'

interface AgentConversationProps {
  run: RunView | null
  currentNode: string | null
  turns?: ConversationTurn[]
  traceEvents?: TraceEvent[]
}

const STAGES = [
  { id: 'planner', label: '分析与规划' },
  { id: 'coder', label: '生成代码变更' },
  { id: 'ops', label: '运行测试' },
  { id: 'reviewer', label: '质量审查' },
] as const

const TOOL_STAGES = [
  { id: 'tool-agent', node: 'tool', label: '调用工具' },
] as const

function statusLabel(status: RunView['status']): string {
  switch (status) {
    case 'RUNNING': return '执行中'
    case 'WAITING_APPROVAL': return '等待审批'
    case 'COMPLETED': return '已完成'
    case 'REJECTED': return '已拒绝'
    case 'FAILED': return '执行失败'
  }
}

function stageState(run: RunView, currentNode: string | null, traceName: string, nodeName = traceName) {
  if (run.state.trace.includes(traceName)) return 'complete'
  if (currentNode === nodeName) return 'active'
  return 'pending'
}

interface ImageArtifact {
  dataUrl: string
  model: string
  revisedPrompt: string
}

const IMAGE_DATA_URL = /^data:image\/(?:png|jpeg|webp|gif);base64,[A-Za-z0-9+/]+={0,2}$/

function safeImageUrl(value: string): boolean {
  if (IMAGE_DATA_URL.test(value)) return true
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

function parseImageArtifact(value: string | undefined): ImageArtifact | null {
  if (value === undefined) return null
  try {
    const parsed: unknown = JSON.parse(value)
    if (typeof parsed !== 'object' || parsed === null) return null
    const artifact = parsed as Record<string, unknown>
    if (artifact.type !== 'image'
      || typeof artifact.dataUrl !== 'string'
      || typeof artifact.model !== 'string'
      || !safeImageUrl(artifact.dataUrl)) return null
    return {
      dataUrl: artifact.dataUrl,
      model: artifact.model,
      revisedPrompt: typeof artifact.revisedPrompt === 'string' ? artifact.revisedPrompt : '',
    }
  } catch {
    return null
  }
}

function StageIcon({ state }: { state: 'complete' | 'active' | 'pending' }) {
  if (state === 'complete') return <Check aria-hidden="true" size={14} />
  if (state === 'active') return <LoaderCircle aria-hidden="true" size={14} />
  return <Circle aria-hidden="true" size={12} />
}

function messageRoleLabel(role: ChatMessage['role']): string {
  switch (role) {
    case 'system': return '系统'
    case 'user': return '你'
    case 'assistant': return 'Agent4J'
    case 'tool': return '工具'
  }
}

function messageText(message: ChatMessage): string {
  const parts: string[] = []
  if (typeof message.content === 'string') parts.push(message.content)
  if (Array.isArray(message.content)) {
    for (const part of message.content) {
      parts.push(part.type === 'text' ? part.text : `图片：${part.image_url.url}`)
    }
  }
  if (message.tool_calls !== undefined) {
    for (const call of message.tool_calls) {
      parts.push(`工具调用 ${call.function.name}\n${call.function.arguments}`)
    }
  }
  if (parts.length === 0 && message.tool_call_id !== undefined && message.tool_call_id !== null) {
    parts.push(`工具结果 ${message.tool_call_id}`)
  }
  return parts.join('\n').trim() || '空消息'
}

function MessageIcon({ role }: { role: ChatMessage['role'] }) {
  return role === 'user' ? <User aria-hidden="true" size={16} /> : role === 'tool' ? <Terminal aria-hidden="true" size={16} /> : <Bot aria-hidden="true" size={16} />
}

function TurnStatus({ status }: { status: ConversationTurn['status'] }) {
  if (status === 'PENDING' || status === 'RUNNING') return <span className="turn-status is-running">执行中</span>
  if (status === 'FAILED') return <span className="turn-status is-failed">失败</span>
  return <span className="turn-status is-complete">已完成</span>
}

function PersistedTurns({ turns }: { turns: ConversationTurn[] }) {
  return (
    <>
      {turns.map((turn) => (
        <div className="persisted-turn" key={turn.turnId}>
          <article className="conversation-message user-message">
            <span className="message-avatar"><User aria-hidden="true" size={16} /></span>
            <div className="message-body"><span className="message-author">你</span><MarkdownMessage markdown={turn.userContent} /></div>
          </article>
          {turn.assistantContent === null && turn.error === null ? (
            <article className="conversation-message agent-message">
              <span className="message-avatar agent-avatar"><Bot aria-hidden="true" size={17} /></span>
              <div className="message-body"><div className="agent-message-heading"><span className="message-author">Agent4J</span><TurnStatus status={turn.status} /></div><p className="agent-progress-copy">正在处理这条消息。</p></div>
            </article>
          ) : (
            <article className={`conversation-message agent-message ${turn.status === 'FAILED' ? 'is-failed' : ''}`}>
              <span className="message-avatar agent-avatar"><Bot aria-hidden="true" size={17} /></span>
              <div className="message-body"><div className="agent-message-heading"><span className="message-author">Agent4J</span><TurnStatus status={turn.status} /></div><MarkdownMessage markdown={turn.assistantContent ?? turn.error ?? ''} /></div>
            </article>
          )}
        </div>
      ))}
    </>
  )
}

function PersistedMessages({ messages }: { messages: ChatMessage[] }) {
  return (
    <>
      {messages.map((message, index) => (
        <article className={`conversation-message persisted-message role-${message.role}`} key={`message-${index}`}>
          <span className="message-avatar"><MessageIcon role={message.role} /></span>
          <div className="message-body">
            <div className="event-heading">
              <span className="message-author">{messageRoleLabel(message.role)}</span>
              {message.name === undefined || message.name === null ? null : <code className="message-role-meta">{message.name}</code>}
            </div>
            <MarkdownMessage markdown={messageText(message)} />
          </div>
        </article>
      ))}
    </>
  )
}

/** 将权威 Run 状态转换为用户可读的连续 Agent 会话。 */
export function AgentConversation({ run, currentNode, turns = [], traceEvents = [] }: AgentConversationProps) {
  if (run === null && turns.length === 0) {
    return (
      <section className="conversation-empty" aria-label="Agent 会话">
        <span className="empty-agent-mark"><Bot aria-hidden="true" size={28} /></span>
        <h2>今天要让 Agent 完成什么？</h2>
      </section>
    )
  }

  if (run === null) {
    return <section className="conversation-stream" aria-label="Agent 会话"><PersistedTurns turns={turns} /></section>
  }

  const variables = run.state.variables
  const messages = run.state.messages
  const task = variables['demo.task'] ?? variables['planner.task']
  const plan = variables['planner.plan']
  const plannerRequest = variables['planner.request']
  const plannerResponse = variables['planner.response']
  const plannerModel = variables['planner.model']
  const plannerRoute = variables['planner.route']
  const finalResponse = variables['final_response']
  const plannerError = variables['planner.error']
  const toolRequest = variables['tool.request']
  const toolResponse = variables['tool.response']
  const toolResult = variables['tool.result']
  const toolModel = variables['tool.model']
  const toolError = variables['tool.error']
  const activeSkills = variables['skill.active']
  const coderRequest = variables['coder.request']
  const coderResponse = variables['coder.response']
  const coderModel = variables['coder.model']
  const coderSummary = variables['coder.summary']
  const coderError = variables['coder.error']
  const updatedFiles = variables['coder.updatedFiles']
  const command = variables['ops.command']
  const exitCode = variables['ops.exitCode']
  const stdout = variables['ops.stdout']
  const stderr = variables['ops.stderr']
  const timedOut = variables['ops.timedOut']
  const opsError = variables['ops.error']
  const opsLogError = variables['ops.logError']
  const reviewSummary = variables['reviewer.summary']
  const reviewFeedback = variables['reviewer.feedback']
  const reviewerError = variables['reviewer.error']
  const approved = variables['reviewer.approved']
  const imageArtifact = parseImageArtifact(toolResult)
  const executionErrors = [plannerError, toolError, coderError, opsError, opsLogError, reviewerError].filter(
    (value): value is string => value !== undefined && value.length > 0,
  )
  const hasFailureEvidence = timedOut === 'true' || executionErrors.length > 0 || run.error !== null
  const hasUserMessage = messages.some((message) => message.role === 'user')
  const currentTurn = turns.find((turn) => turn.runId === run.runId)
  const historicalTurns = turns.filter((turn) => turn.runId !== run.runId)
  const currentTask = turns.length > 0
    ? currentTurn?.userContent ?? task
    : hasUserMessage ? undefined : task
  const handoffEvents = traceEvents.filter((event): event is Extract<TraceEvent, { type: 'HANDOFF' }> => event.type === 'HANDOFF')

  return (
    <section className="conversation-stream" aria-label="Agent 会话">
      {turns.length > 0 ? <PersistedTurns turns={historicalTurns} /> : messages.length > 0 ? <PersistedMessages messages={messages} /> : null}
      {currentTask !== undefined ? (
        <article className="conversation-message user-message">
          <span className="message-avatar"><User aria-hidden="true" size={16} /></span>
          <div><span className="message-author">你</span><MarkdownMessage markdown={currentTask} /></div>
        </article>
      ) : null}

      <article className="conversation-message agent-message">
        <span className="message-avatar agent-avatar"><Bot aria-hidden="true" size={17} /></span>
        <div className="message-body">
          <div className="agent-message-heading">
            <span className="message-author">Agent4J</span>
            <span className={`run-status status-${run.status.toLowerCase()}`}>{statusLabel(run.status)}</span>
          </div>
          {finalResponse !== undefined ? (
            <div className="final-response">
              <MarkdownMessage markdown={finalResponse} />
            </div>
          ) : plan === undefined ? (
            <p className="agent-progress-copy">正在读取任务并建立执行计划。</p>
          ) : (
            <div className="plan-block"><strong>执行计划</strong><MarkdownMessage markdown={plan} /></div>
          )}
          {plannerRequest === undefined && plannerResponse === undefined ? null : (
            <details className="evidence-details">
              <summary>Planner 模型调用 {plannerModel === undefined ? '' : `· ${plannerModel}`}</summary>
              {plannerRequest === undefined ? null : <pre>{plannerRequest}</pre>}
              {plannerResponse === undefined ? null : <pre>{plannerResponse}</pre>}
              {plannerError === undefined ? null : <pre className="run-error-detail">{plannerError}</pre>}
            </details>
          )}
          {plannerRoute === 'chat' ? null : (
            <ol className="execution-stages" aria-label="执行阶段">
              {(variables['planner.taskKind'] === 'TOOL_OPERATION' ? TOOL_STAGES : STAGES).map((stage) => {
                const node = 'node' in stage ? stage.node : stage.id
                const state = stageState(run, currentNode, stage.id, node)
                return (
                  <li key={stage.id} className={`stage-${state}`}>
                    <span className="stage-icon"><StageIcon state={state} /></span>
                    <span>{stage.label}</span>
                  </li>
                )
              })}
            </ol>
          )}
        </div>
      </article>

      {handoffEvents.map((event) => (
        <article className="conversation-message handoff-message" key={event.eventId}>
          <span className="message-avatar agent-avatar"><Bot aria-hidden="true" size={17} /></span>
          <div className="message-body">
            <div className="event-heading">
              <span className="message-author">子 Agent</span>
              <span className="handoff-lifecycle">{event.lifecycle}</span>
            </div>
            <p className="handoff-route">{event.fromAgent} → {event.toAgent}</p>
            <code className="handoff-child-run">{event.childRunId}</code>
          </div>
        </article>
      ))}

      {toolRequest === undefined && toolResponse === undefined && toolResult === undefined && toolError === undefined ? null : (
        <article className={`conversation-message event-message ${toolError === undefined ? '' : 'is-failed'}`}>
          <span className="message-avatar"><ImageIcon aria-hidden="true" size={16} /></span>
          <div className="message-body">
            <div className="event-heading">
              <span className="message-author">工具调用</span>
              {toolModel === undefined ? null : <code>{toolModel}</code>}
            </div>
            {activeSkills === undefined || activeSkills.length === 0 ? null : (
              <p className="tool-skill-label">已激活 Skill：<code>{activeSkills}</code></p>
            )}
            {imageArtifact === null ? null : (
              <figure className="tool-image-artifact">
                <img src={imageArtifact.dataUrl} alt="Agent 生成图片" />
                <figcaption>
                  <code>{imageArtifact.model}</code>
                  {imageArtifact.revisedPrompt.length === 0 ? null : <span>{imageArtifact.revisedPrompt}</span>}
                </figcaption>
              </figure>
            )}
            {toolResult === undefined || imageArtifact !== null ? null : <pre>{toolResult}</pre>}
            {toolRequest === undefined ? null : <details className="evidence-details"><summary>工具模型请求</summary><pre>{toolRequest}</pre></details>}
            {toolResponse === undefined ? null : <details className="evidence-details"><summary>工具模型响应</summary><pre>{toolResponse}</pre></details>}
            {toolError === undefined ? null : <pre className="run-error-detail">{toolError}</pre>}
          </div>
        </article>
      )}

      {updatedFiles === undefined ? null : (
        <article className="conversation-message event-message">
          <span className="message-avatar"><Code2 aria-hidden="true" size={16} /></span>
          <div className="message-body">
            <span className="message-author">代码变更</span>
            <pre className="artifact-list">{updatedFiles}</pre>
          </div>
        </article>
      )}

      {coderRequest === undefined && coderResponse === undefined && coderError === undefined ? null : (
        <article className="conversation-message event-message">
          <span className="message-avatar"><Code2 aria-hidden="true" size={16} /></span>
          <div className="message-body">
            <div className="event-heading">
              <span className="message-author">Coder 模型与工具</span>
              {coderModel === undefined ? null : <code>{coderModel}</code>}
            </div>
            {coderSummary === undefined ? null : <MarkdownMessage markdown={coderSummary} />}
            {coderRequest === undefined ? null : <details className="evidence-details"><summary>模型请求</summary><pre>{coderRequest}</pre></details>}
            {coderResponse === undefined ? null : <details className="evidence-details"><summary>模型响应</summary><pre>{coderResponse}</pre></details>}
            {coderError === undefined ? null : <pre className="run-error-detail">{coderError}</pre>}
          </div>
        </article>
      )}

      {command === undefined ? null : (
        <article className="conversation-message event-message">
          <span className="message-avatar"><Terminal aria-hidden="true" size={16} /></span>
          <div className="message-body">
            <div className="event-heading">
              <span className="message-author">测试执行</span>
              {exitCode === undefined ? null : <code>exit {exitCode}</code>}
            </div>
            <code className="command-line">{command}</code>
            {stdout === undefined || stdout.length === 0 ? null : <pre className="output-preview">{stdout}</pre>}
            {stderr === undefined || stderr.length === 0 ? null : (
              <div className="command-error-block"><strong>stderr</strong><pre className="output-preview">{stderr}</pre></div>
            )}
            {timedOut === 'true' ? <p className="timeout-warning">命令超时</p> : null}
            {opsError === undefined ? null : <pre className="run-error-detail">{opsError}</pre>}
            {opsLogError === undefined ? null : <pre className="run-error-detail">{opsLogError}</pre>}
          </div>
        </article>
      )}

      {reviewSummary === undefined && !hasFailureEvidence && reviewFeedback === undefined ? null : (
        <article className={`conversation-message result-message ${hasFailureEvidence ? 'is-failed' : ''}`}>
          <span className="message-avatar">
            {hasFailureEvidence
              ? <CircleAlert aria-hidden="true" size={17} />
              : <Check aria-hidden="true" size={17} />}
          </span>
          <div className="message-body">
            <div className="event-heading">
              <span className="message-author">执行结果</span>
              {approved === undefined || hasFailureEvidence ? null : <span>{approved === 'true' ? '审查通过' : '需要修复'}</span>}
            </div>
            {reviewSummary === undefined ? null : <strong className="result-title">{reviewSummary}</strong>}
            {reviewFeedback === undefined ? null : <MarkdownMessage markdown={reviewFeedback} />}
            {reviewerError === undefined ? null : <pre className="run-error-detail">{reviewerError}</pre>}
            {run.error === null ? null : <pre className="run-error-detail">{run.error}</pre>}
          </div>
        </article>
      )}
    </section>
  )
}
