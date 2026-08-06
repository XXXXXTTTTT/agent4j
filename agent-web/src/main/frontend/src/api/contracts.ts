export type RunStatus =
  | 'RUNNING'
  | 'WAITING_APPROVAL'
  | 'COMPLETED'
  | 'REJECTED'
  | 'FAILED'

export type ApprovalDecision = 'APPROVE' | 'REJECT'

export type ChatRole = 'system' | 'user' | 'assistant' | 'tool'
export type ImageDetail = 'auto' | 'low' | 'high'

export type ChatContentPart =
  | { type: 'text'; text: string }
  | { type: 'image_url'; image_url: { url: string; detail: ImageDetail } }

export interface ToolCall {
  id: string
  type: string
  function: { name: string; arguments: string }
}

export interface ChatMessage {
  role: ChatRole
  content?: string | ChatContentPart[] | null
  name?: string | null
  tool_call_id?: string | null
  tool_calls?: ToolCall[]
}

export interface AgentState {
  messages: ChatMessage[]
  variables: Record<string, string>
  trace: string[]
}

export interface InterruptRequest {
  interruptId: string
  nodeName: string
  reason: string
  details: Record<string, string>
}

export interface RunView {
  runId: string
  version: number
  graphId: string
  status: RunStatus
  state: AgentState
  nextNode: string | null
  interruptRequest: InterruptRequest | null
  approvalDecision: ApprovalDecision | null
  approvalReason: string | null
  error: string | null
  createdAt: string
}

export interface StartRunCommand {
  graphId: string
  initialState: AgentState
}

export interface ApprovalCommand {
  decision: ApprovalDecision
  expectedVersion: number
  reason: string
  variableUpdates: Record<string, string>
}

export type RunLogStream = 'STDOUT' | 'STDERR' | 'PTY'

export interface RunLogEvent {
  eventId: string
  runId: string
  nodeName: string
  sequence: number
  stream: RunLogStream
  text: string
  occurredAt: string
}

export interface TerminalSnapshot {
  runId: string
  checkpointVersion: number
  stdout: string
  stderr: string
  exitCode: number | null
  timedOut: boolean | null
  error: string | null
}

export type TerminalFrame =
  | { kind: 'SNAPSHOT'; terminal: TerminalSnapshot }
  | { kind: 'LOG'; event: RunLogEvent }

interface TraceCommon {
  eventId: string
  runId: string
  checkpointVersion: number
  occurredAt: string
}

export type TraceEvent =
  | (TraceCommon & { type: 'NODE_STARTED'; nodeName: string })
  | (TraceCommon & { type: 'NODE_PROGRESS'; nodeName: string; summary: string })
  | (TraceCommon & {
      type: 'NODE_COMPLETED'
      nodeName: string
      nextNode: string
    })
  | (TraceCommon & {
      type: 'INTERRUPTED'
      nodeName: string
      request: InterruptRequest
    })
  | (TraceCommon & { type: 'APPROVED'; nodeName: string; reason: string })
  | (TraceCommon & { type: 'REJECTED'; nodeName: string; reason: string })
  | (TraceCommon & { type: 'FAILED'; error: string })
  | (TraceCommon & { type: 'COMPLETED' })

export type TraceFrame =
  | { kind: 'SNAPSHOT'; run: RunView }
  | { kind: 'EVENT'; event: TraceEvent }
