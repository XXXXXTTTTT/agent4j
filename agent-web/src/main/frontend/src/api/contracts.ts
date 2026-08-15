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
      type: 'HANDOFF'
      taskId: string
      parentRunId: string
      childRunId: string
      fromAgent: string
      toAgent: string
      lifecycle: string
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

export interface Actor {
  userId: string
  displayName: string
}

export type WorkspacePermission = 'VIEWER' | 'OPERATOR' | 'OWNER'

export interface Workspace {
  workspaceId: string
  ownerUserId: string
  displayName: string
  workspacePath: string
  repositoryId: string
  permission: WorkspacePermission
  createdAt: string
  updatedAt: string
}

export interface WorkspaceDirectoryEntry {
  name: string
  path: string
}

export interface WorkspaceDirectoryListing {
  currentPath: string
  parentPath: string | null
  entries: WorkspaceDirectoryEntry[]
}

export type WorkspaceFileKind = 'DIRECTORY' | 'FILE'
export interface WorkspaceFileEntry {
  name: string
  path: string
  kind: WorkspaceFileKind
  size: number
  lastModified: string
}
export interface WorkspaceFileContent {
  path: string
  content: string
  sha256: string
  lastModified: string
}

export type CliRiskLevel = 'READ_ONLY' | 'MUTATING' | 'DESTRUCTIVE'

/** 当前工作区中允许执行的受治理 CLI 命令。 */
export interface GovernedCliCommand {
  name: string
  executable: string
  fixedArguments: string[]
  riskLevel: CliRiskLevel
  requiredCapabilities: string[]
  maxArguments: number
}

/** 创建受治理 CLI Run 的精确请求体。 */
export interface GovernedCliRunCommand {
  commandName: string
  arguments: string[]
  timeoutSeconds: number
}

export type ConversationStatus = 'ACTIVE' | 'ARCHIVED'

export interface Conversation {
  conversationId: string
  workspaceId: string
  createdBy: string
  title: string
  status: ConversationStatus
  createdAt: string
  updatedAt: string
}

export type ConversationTurnStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'

export interface ConversationTurn {
  turnId: string
  conversationId: string
  turnIndex: number
  userContent: string
  assistantContent: string | null
  runId: string | null
  status: ConversationTurnStatus
  error: string | null
  createdAt: string
  completedAt: string | null
}

export type ModelTaskType = 'CODE' | 'VISION' | 'QUICK_CLASSIFICATION'
export type InferenceCapability = 'CHAT_COMPLETIONS' | 'STREAMING' | 'TOOL_CALLING' | 'VISION_INPUT'
export interface ModelProvider { providerId: string; ownerUserId: string; displayName: string; baseUrl: string; chatCompletionsPath: string; apiKeyMasked: string; createdAt: string; updatedAt: string }
export interface ModelEndpoint { endpointId: string; providerId: string; displayName: string; modelId: string; capabilities: InferenceCapability[]; priority: number; weight: number; enabled: boolean; createdAt: string; updatedAt: string }
export interface ModelGroup { groupId: string; ownerUserId: string; displayName: string; taskType: ModelTaskType; endpointIds: string[]; createdAt: string; updatedAt: string }
export interface ModelConfigurationSnapshot { providers: ModelProvider[]; endpoints: ModelEndpoint[]; groups: ModelGroup[] }
