import type {
  AgentState,
  ApprovalCommand,
  ApprovalDecision,
  ChatContentPart,
  ChatMessage,
  ChatRole,
  ImageDetail,
  InterruptRequest,
  RunLogEvent,
  RunLogStream,
  RunStatus,
  RunView,
  TerminalFrame,
  TerminalSnapshot,
  ToolCall,
  TraceEvent,
  TraceFrame,
} from './contracts'

type JsonObject = Record<string, unknown>

const RUN_STATUSES = new Set<RunStatus>([
  'RUNNING',
  'WAITING_APPROVAL',
  'COMPLETED',
  'REJECTED',
  'FAILED',
])
const APPROVAL_DECISIONS = new Set<ApprovalDecision>(['APPROVE', 'REJECT'])
const CHAT_ROLES = new Set<ChatRole>(['system', 'user', 'assistant', 'tool'])
const IMAGE_DETAILS = new Set<ImageDetail>(['auto', 'low', 'high'])
const RUN_LOG_STREAMS = new Set<RunLogStream>(['STDOUT', 'STDERR', 'PTY'])
const TRACE_TYPES = new Set<TraceEvent['type']>([
  'NODE_STARTED',
  'NODE_COMPLETED',
  'INTERRUPTED',
  'APPROVED',
  'REJECTED',
  'FAILED',
  'COMPLETED',
])

export class RunApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly responseBody: string,
  ) {
    super(message)
    this.name = 'RunApiError'
  }
}

function objectAt(value: unknown, path: string): JsonObject {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new TypeError(`${path} 必须是对象`)
  }
  return value as JsonObject
}

function exactKeys(value: JsonObject, expected: readonly string[], path: string): void {
  const expectedSet = new Set(expected)
  const unknown = Object.keys(value).filter((key) => !expectedSet.has(key))
  if (unknown.length > 0) {
    throw new TypeError(`${path} 包含未知字段: ${unknown.join(', ')}`)
  }
}

function stringAt(value: unknown, path: string): string {
  if (typeof value !== 'string') {
    throw new TypeError(`${path} 必须是字符串`)
  }
  return value
}

function nonBlankStringAt(value: unknown, path: string): string {
  const result = stringAt(value, path)
  if (result.trim().length === 0) {
    throw new TypeError(`${path} 不能为空`)
  }
  return result
}

function nonNegativeIntegerAt(value: unknown, path: string): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 0) {
    throw new TypeError(`${path} 必须是非负整数`)
  }
  return value
}

function nullableStringAt(value: unknown, path: string): string | null {
  return value === null ? null : stringAt(value, path)
}

function enumAt<T extends string>(
  value: unknown,
  values: ReadonlySet<T>,
  path: string,
): T {
  const result = stringAt(value, path)
  if (!values.has(result as T)) {
    throw new TypeError(`${path} 包含未知值: ${result}`)
  }
  return result as T
}

function stringMapAt(value: unknown, path: string): Record<string, string> {
  const object = objectAt(value, path)
  return Object.fromEntries(
    Object.entries(object).map(([key, item]) => [key, stringAt(item, `${path}.${key}`)]),
  )
}

function stringArrayAt(value: unknown, path: string): string[] {
  if (!Array.isArray(value)) {
    throw new TypeError(`${path} 必须是数组`)
  }
  return value.map((item, index) => stringAt(item, `${path}[${index}]`))
}

function decodeToolCall(value: unknown, path: string): ToolCall {
  const object = objectAt(value, path)
  exactKeys(object, ['id', 'type', 'function'], path)
  const fn = objectAt(object.function, `${path}.function`)
  exactKeys(fn, ['name', 'arguments'], `${path}.function`)
  return {
    id: nonBlankStringAt(object.id, `${path}.id`),
    type: nonBlankStringAt(object.type, `${path}.type`),
    function: {
      name: nonBlankStringAt(fn.name, `${path}.function.name`),
      arguments: stringAt(fn.arguments, `${path}.function.arguments`),
    },
  }
}

function decodeContentPart(value: unknown, path: string): ChatContentPart {
  const object = objectAt(value, path)
  const type = stringAt(object.type, `${path}.type`)
  if (type === 'text') {
    exactKeys(object, ['type', 'text'], path)
    return { type, text: stringAt(object.text, `${path}.text`) }
  }
  if (type === 'image_url') {
    exactKeys(object, ['type', 'image_url'], path)
    const imageUrl = objectAt(object.image_url, `${path}.image_url`)
    exactKeys(imageUrl, ['url', 'detail'], `${path}.image_url`)
    return {
      type,
      image_url: {
        url: nonBlankStringAt(imageUrl.url, `${path}.image_url.url`),
        detail: enumAt(imageUrl.detail, IMAGE_DETAILS, `${path}.image_url.detail`),
      },
    }
  }
  throw new TypeError(`${path}.type 包含未知值: ${type}`)
}

function decodeChatMessage(value: unknown, path: string): ChatMessage {
  const object = objectAt(value, path)
  exactKeys(object, ['role', 'content', 'name', 'tool_call_id', 'tool_calls'], path)
  const message: ChatMessage = {
    role: enumAt(object.role, CHAT_ROLES, `${path}.role`),
  }
  if ('content' in object) {
    if (object.content === null || typeof object.content === 'string') {
      message.content = object.content
    } else if (Array.isArray(object.content) && object.content.length > 0) {
      message.content = object.content.map((part, index) =>
        decodeContentPart(part, `${path}.content[${index}]`),
      )
    } else {
      throw new TypeError(`${path}.content 必须是字符串、非空数组或 null`)
    }
  }
  if ('name' in object) message.name = nullableStringAt(object.name, `${path}.name`)
  if ('tool_call_id' in object) {
    message.tool_call_id = nullableStringAt(object.tool_call_id, `${path}.tool_call_id`)
  }
  if ('tool_calls' in object) {
    if (!Array.isArray(object.tool_calls)) {
      throw new TypeError(`${path}.tool_calls 必须是数组`)
    }
    message.tool_calls = object.tool_calls.map((toolCall, index) =>
      decodeToolCall(toolCall, `${path}.tool_calls[${index}]`),
    )
  }
  return message
}

export function decodeAgentState(value: unknown, path = 'state'): AgentState {
  const object = objectAt(value, path)
  exactKeys(object, ['messages', 'variables', 'trace'], path)
  if (!Array.isArray(object.messages)) {
    throw new TypeError(`${path}.messages 必须是数组`)
  }
  return {
    messages: object.messages.map((message, index) =>
      decodeChatMessage(message, `${path}.messages[${index}]`),
    ),
    variables: stringMapAt(object.variables, `${path}.variables`),
    trace: stringArrayAt(object.trace, `${path}.trace`),
  }
}

function decodeInterruptRequest(value: unknown, path: string): InterruptRequest {
  const object = objectAt(value, path)
  exactKeys(object, ['interruptId', 'nodeName', 'reason', 'details'], path)
  return {
    interruptId: nonBlankStringAt(object.interruptId, `${path}.interruptId`),
    nodeName: nonBlankStringAt(object.nodeName, `${path}.nodeName`),
    reason: nonBlankStringAt(object.reason, `${path}.reason`),
    details: stringMapAt(object.details, `${path}.details`),
  }
}

function nullableInterruptAt(value: unknown, path: string): InterruptRequest | null {
  return value === null ? null : decodeInterruptRequest(value, path)
}

function nullableDecisionAt(value: unknown, path: string): ApprovalDecision | null {
  return value === null ? null : enumAt(value, APPROVAL_DECISIONS, path)
}

function validateRunCombination(run: RunView): void {
  const reject = (message: string): never => {
    throw new TypeError(`run 状态组合非法: ${message}`)
  }
  switch (run.status) {
    case 'RUNNING':
      if (run.nextNode === null || run.interruptRequest !== null || run.error !== null) {
        reject('RUNNING')
      }
      if (
        (run.approvalDecision === null && run.approvalReason !== null) ||
        (run.approvalDecision !== null &&
          (run.approvalDecision !== 'APPROVE' || run.approvalReason === null))
      ) {
        reject('RUNNING 审批字段')
      }
      break
    case 'WAITING_APPROVAL':
      if (
        run.nextNode === null ||
        run.interruptRequest === null ||
        run.nextNode !== run.interruptRequest.nodeName ||
        run.approvalDecision !== null ||
        run.approvalReason !== null ||
        run.error !== null
      ) {
        reject('WAITING_APPROVAL')
      }
      break
    case 'COMPLETED':
      if (
        run.nextNode !== null ||
        run.interruptRequest !== null ||
        run.approvalDecision !== null ||
        run.approvalReason !== null ||
        run.error !== null
      ) {
        reject('COMPLETED')
      }
      break
    case 'REJECTED':
      if (
        run.nextNode !== null ||
        run.interruptRequest === null ||
        run.approvalDecision !== 'REJECT' ||
        run.approvalReason === null ||
        run.error !== null
      ) {
        reject('REJECTED')
      }
      break
    case 'FAILED':
      if (
        run.nextNode !== null ||
        run.interruptRequest !== null ||
        run.approvalDecision !== null ||
        run.approvalReason !== null ||
        run.error === null
      ) {
        reject('FAILED')
      }
  }
}

export function decodeRunView(value: unknown): RunView {
  const object = objectAt(value, 'run')
  exactKeys(
    object,
    [
      'runId',
      'version',
      'graphId',
      'status',
      'state',
      'nextNode',
      'interruptRequest',
      'approvalDecision',
      'approvalReason',
      'error',
      'createdAt',
    ],
    'run',
  )
  const run: RunView = {
    runId: nonBlankStringAt(object.runId, 'run.runId'),
    version: nonNegativeIntegerAt(object.version, 'run.version'),
    graphId: nonBlankStringAt(object.graphId, 'run.graphId'),
    status: enumAt(object.status, RUN_STATUSES, 'run.status'),
    state: decodeAgentState(object.state),
    nextNode: nullableStringAt(object.nextNode, 'run.nextNode'),
    interruptRequest: nullableInterruptAt(object.interruptRequest, 'run.interruptRequest'),
    approvalDecision: nullableDecisionAt(object.approvalDecision, 'run.approvalDecision'),
    approvalReason: nullableStringAt(object.approvalReason, 'run.approvalReason'),
    error: nullableStringAt(object.error, 'run.error'),
    createdAt: nonBlankStringAt(object.createdAt, 'run.createdAt'),
  }
  validateRunCombination(run)
  return run
}

function decodeRunLogEvent(value: unknown, path: string): RunLogEvent {
  const object = objectAt(value, path)
  exactKeys(
    object,
    ['eventId', 'runId', 'nodeName', 'sequence', 'stream', 'text', 'occurredAt'],
    path,
  )
  return {
    eventId: nonBlankStringAt(object.eventId, `${path}.eventId`),
    runId: nonBlankStringAt(object.runId, `${path}.runId`),
    nodeName: nonBlankStringAt(object.nodeName, `${path}.nodeName`),
    sequence: nonNegativeIntegerAt(object.sequence, `${path}.sequence`),
    stream: enumAt(object.stream, RUN_LOG_STREAMS, `${path}.stream`),
    text: stringAt(object.text, `${path}.text`),
    occurredAt: nonBlankStringAt(object.occurredAt, `${path}.occurredAt`),
  }
}

function decodeTerminalSnapshot(value: unknown): TerminalSnapshot {
  const object = objectAt(value, 'terminal')
  exactKeys(
    object,
    ['runId', 'checkpointVersion', 'stdout', 'stderr', 'exitCode', 'timedOut', 'error'],
    'terminal',
  )
  const exitCode = object.exitCode
  if (exitCode !== null && (typeof exitCode !== 'number' || !Number.isInteger(exitCode))) {
    throw new TypeError('terminal.exitCode 必须是整数或 null')
  }
  const timedOut = object.timedOut
  if (timedOut !== null && typeof timedOut !== 'boolean') {
    throw new TypeError('terminal.timedOut 必须是布尔值或 null')
  }
  return {
    runId: nonBlankStringAt(object.runId, 'terminal.runId'),
    checkpointVersion: nonNegativeIntegerAt(
      object.checkpointVersion,
      'terminal.checkpointVersion',
    ),
    stdout: stringAt(object.stdout, 'terminal.stdout'),
    stderr: stringAt(object.stderr, 'terminal.stderr'),
    exitCode: exitCode as number | null,
    timedOut: timedOut as boolean | null,
    error: nullableStringAt(object.error, 'terminal.error'),
  }
}

export function decodeTerminalFrame(value: unknown): TerminalFrame {
  const object = objectAt(value, 'terminalFrame')
  const kind = stringAt(object.kind, 'terminalFrame.kind')
  if (kind === 'SNAPSHOT') {
    exactKeys(object, ['kind', 'terminal'], 'terminalFrame')
    return { kind, terminal: decodeTerminalSnapshot(object.terminal) }
  }
  if (kind === 'LOG') {
    exactKeys(object, ['kind', 'event'], 'terminalFrame')
    return { kind, event: decodeRunLogEvent(object.event, 'terminalFrame.event') }
  }
  throw new TypeError(`terminalFrame.kind 包含未知值: ${kind}`)
}

function traceCommon(object: JsonObject, path: string) {
  return {
    eventId: nonBlankStringAt(object.eventId, `${path}.eventId`),
    runId: nonBlankStringAt(object.runId, `${path}.runId`),
    checkpointVersion: nonNegativeIntegerAt(
      object.checkpointVersion,
      `${path}.checkpointVersion`,
    ),
    occurredAt: nonBlankStringAt(object.occurredAt, `${path}.occurredAt`),
  }
}

function decodeTraceEvent(value: unknown): TraceEvent {
  const object = objectAt(value, 'traceFrame.event')
  const type = enumAt(object.type, TRACE_TYPES, 'traceFrame.event.type')
  const commonKeys = ['type', 'eventId', 'runId', 'checkpointVersion', 'occurredAt']
  const common = traceCommon(object, 'traceFrame.event')
  switch (type) {
    case 'NODE_STARTED':
      exactKeys(object, [...commonKeys, 'nodeName'], 'traceFrame.event')
      return {
        type,
        ...common,
        nodeName: nonBlankStringAt(object.nodeName, 'traceFrame.event.nodeName'),
      }
    case 'NODE_COMPLETED':
      exactKeys(object, [...commonKeys, 'nodeName', 'nextNode'], 'traceFrame.event')
      return {
        type,
        ...common,
        nodeName: nonBlankStringAt(object.nodeName, 'traceFrame.event.nodeName'),
        nextNode: nonBlankStringAt(object.nextNode, 'traceFrame.event.nextNode'),
      }
    case 'INTERRUPTED':
      exactKeys(object, [...commonKeys, 'nodeName', 'request'], 'traceFrame.event')
      return {
        type,
        ...common,
        nodeName: nonBlankStringAt(object.nodeName, 'traceFrame.event.nodeName'),
        request: decodeInterruptRequest(object.request, 'traceFrame.event.request'),
      }
    case 'APPROVED':
    case 'REJECTED':
      exactKeys(object, [...commonKeys, 'nodeName', 'reason'], 'traceFrame.event')
      return {
        type,
        ...common,
        nodeName: nonBlankStringAt(object.nodeName, 'traceFrame.event.nodeName'),
        reason: nonBlankStringAt(object.reason, 'traceFrame.event.reason'),
      }
    case 'FAILED':
      exactKeys(object, [...commonKeys, 'error'], 'traceFrame.event')
      return {
        type,
        ...common,
        error: nonBlankStringAt(object.error, 'traceFrame.event.error'),
      }
    case 'COMPLETED':
      exactKeys(object, commonKeys, 'traceFrame.event')
      return { type, ...common }
  }
}

export function decodeTraceFrame(value: unknown): TraceFrame {
  const object = objectAt(value, 'traceFrame')
  const kind = stringAt(object.kind, 'traceFrame.kind')
  if (kind === 'SNAPSHOT') {
    exactKeys(object, ['kind', 'run'], 'traceFrame')
    return { kind, run: decodeRunView(object.run) }
  }
  if (kind === 'EVENT') {
    exactKeys(object, ['kind', 'event'], 'traceFrame')
    return { kind, event: decodeTraceEvent(object.event) }
  }
  throw new TypeError(`traceFrame.kind 包含未知值: ${kind}`)
}

function validateApprovalCommand(command: ApprovalCommand): ApprovalCommand {
  const decision = enumAt(command.decision, APPROVAL_DECISIONS, 'approval.decision')
  const expectedVersion = nonNegativeIntegerAt(
    command.expectedVersion,
    'approval.expectedVersion',
  )
  const reason = nonBlankStringAt(command.reason, 'approval.reason')
  const variableUpdates = stringMapAt(command.variableUpdates, 'approval.variableUpdates')
  if (decision === 'REJECT' && Object.keys(variableUpdates).length > 0) {
    throw new TypeError('REJECT 不允许 variableUpdates')
  }
  return { decision, expectedVersion, reason, variableUpdates }
}

async function requestJson(
  url: string,
  init: RequestInit,
  fetcher: typeof fetch,
): Promise<unknown> {
  const response = await fetcher(url, init)
  const text = await response.text()
  if (!response.ok) {
    throw new RunApiError(`Run API 请求失败: HTTP ${response.status}`, response.status, text)
  }
  try {
    return JSON.parse(text) as unknown
  } catch (error) {
    throw new TypeError(`Run API 返回了非法 JSON: ${(error as Error).message}`)
  }
}

export async function createRun(
  graphId: string,
  initialState: AgentState,
  fetcher: typeof fetch = globalThis.fetch,
): Promise<RunView> {
  const command = {
    graphId: nonBlankStringAt(graphId, 'graphId'),
    initialState: decodeAgentState(initialState, 'initialState'),
  }
  return decodeRunView(
    await requestJson(
      '/api/runs',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(command),
      },
      fetcher,
    ),
  )
}

export async function getRun(
  runId: string,
  fetcher: typeof fetch = globalThis.fetch,
): Promise<RunView> {
  const exactRunId = nonBlankStringAt(runId, 'runId')
  return decodeRunView(
    await requestJson(`/api/runs/${exactRunId}`, { method: 'GET' }, fetcher),
  )
}

export async function getRunHistory(
  runId: string,
  fetcher: typeof fetch = globalThis.fetch,
): Promise<RunView[]> {
  const exactRunId = nonBlankStringAt(runId, 'runId')
  const body = await requestJson(
    `/api/runs/${exactRunId}/history`,
    { method: 'GET' },
    fetcher,
  )
  if (!Array.isArray(body)) {
    throw new TypeError('history 必须是数组')
  }
  const history = body.map(decodeRunView)
  history.forEach((run, index) => {
    if (index > 0 && history[index - 1].version >= run.version) {
      throw new TypeError('history 必须按 version 严格升序')
    }
  })
  return history
}

export async function decideRun(
  runId: string,
  command: ApprovalCommand,
  fetcher: typeof fetch = globalThis.fetch,
): Promise<RunView> {
  const exactRunId = nonBlankStringAt(runId, 'runId')
  const checked = validateApprovalCommand(command)
  return decodeRunView(
    await requestJson(
      `/api/runs/${exactRunId}/approval`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(checked),
      },
      fetcher,
    ),
  )
}
