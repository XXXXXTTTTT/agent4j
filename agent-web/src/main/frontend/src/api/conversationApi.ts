import type {
  Actor,
  Conversation,
  ConversationStatus,
  ConversationTurn,
  ConversationTurnStatus,
  Workspace,
  WorkspaceDirectoryEntry,
  WorkspaceDirectoryListing,
  WorkspacePermission,
  ModelConfigurationSnapshot,
} from './contracts'

type JsonObject = Record<string, unknown>

const WORKSPACE_PERMISSIONS = new Set<WorkspacePermission>(['VIEWER', 'OPERATOR', 'OWNER'])
const CONVERSATION_STATUSES = new Set<ConversationStatus>(['ACTIVE', 'ARCHIVED'])
const TURN_STATUSES = new Set<ConversationTurnStatus>(['PENDING', 'RUNNING', 'COMPLETED', 'FAILED'])
const MODEL_TASK_TYPES = new Set(['CODE', 'VISION', 'QUICK_CLASSIFICATION'])
const MODEL_CAPABILITIES = new Set(['CHAT_COMPLETIONS', 'STREAMING', 'TOOL_CALLING', 'VISION_INPUT'])

export class ConversationApiError extends Error {
  constructor(message: string, readonly status: number, readonly responseBody: string) {
    super(message)
    this.name = 'ConversationApiError'
  }
}

function objectAt(value: unknown, path: string): JsonObject {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError(`${path} 必须是对象`)
  return value as JsonObject
}

function exactKeys(value: JsonObject, expected: readonly string[], path: string): void {
  const expectedSet = new Set(expected)
  const unknown = Object.keys(value).filter((key) => !expectedSet.has(key))
  if (unknown.length > 0) throw new TypeError(`${path} 包含未知字段: ${unknown.join(', ')}`)
}

function stringAt(value: unknown, path: string): string {
  if (typeof value !== 'string') throw new TypeError(`${path} 必须是字符串`)
  return value
}

function nonBlankStringAt(value: unknown, path: string): string {
  const result = stringAt(value, path)
  if (result.trim().length === 0) throw new TypeError(`${path} 不能为空`)
  return result
}

function nullableStringAt(value: unknown, path: string): string | null {
  return value === null ? null : stringAt(value, path)
}

function nonNegativeIntegerAt(value: unknown, path: string): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 0) throw new TypeError(`${path} 必须是非负整数`)
  return value
}

function enumAt<T extends string>(value: unknown, values: ReadonlySet<T>, path: string): T {
  const result = stringAt(value, path)
  if (!values.has(result as T)) throw new TypeError(`${path} 包含未知值: ${result}`)
  return result as T
}

export function decodeActor(value: unknown, path = 'actor'): Actor {
  const object = objectAt(value, path)
  exactKeys(object, ['userId', 'displayName'], path)
  return { userId: nonBlankStringAt(object.userId, `${path}.userId`), displayName: nonBlankStringAt(object.displayName, `${path}.displayName`) }
}

export function decodeWorkspace(value: unknown, path = 'workspace'): Workspace {
  const object = objectAt(value, path)
  exactKeys(object, ['workspaceId', 'ownerUserId', 'displayName', 'workspacePath', 'repositoryId', 'permission', 'createdAt', 'updatedAt'], path)
  return {
    workspaceId: nonBlankStringAt(object.workspaceId, `${path}.workspaceId`),
    ownerUserId: nonBlankStringAt(object.ownerUserId, `${path}.ownerUserId`),
    displayName: nonBlankStringAt(object.displayName, `${path}.displayName`),
    workspacePath: nonBlankStringAt(object.workspacePath, `${path}.workspacePath`),
    repositoryId: nonBlankStringAt(object.repositoryId, `${path}.repositoryId`),
    permission: enumAt(object.permission, WORKSPACE_PERMISSIONS, `${path}.permission`),
    createdAt: stringAt(object.createdAt, `${path}.createdAt`),
    updatedAt: stringAt(object.updatedAt, `${path}.updatedAt`),
  }
}

export function decodeWorkspaceDirectoryEntry(value: unknown, path = 'entry'): WorkspaceDirectoryEntry {
  const object = objectAt(value, path)
  exactKeys(object, ['name', 'path'], path)
  return {
    name: nonBlankStringAt(object.name, `${path}.name`),
    path: nonBlankStringAt(object.path, `${path}.path`),
  }
}

export function decodeWorkspaceDirectoryListing(value: unknown, path = 'workspaceDirectories'): WorkspaceDirectoryListing {
  const object = objectAt(value, path)
  exactKeys(object, ['currentPath', 'parentPath', 'entries'], path)
  return {
    currentPath: nonBlankStringAt(object.currentPath, `${path}.currentPath`),
    parentPath: nullableStringAt(object.parentPath, `${path}.parentPath`),
    entries: decodeArray(object.entries, decodeWorkspaceDirectoryEntry, `${path}.entries`),
  }
}

export function decodeConversation(value: unknown, path = 'conversation'): Conversation {
  const object = objectAt(value, path)
  exactKeys(object, ['conversationId', 'workspaceId', 'createdBy', 'title', 'status', 'createdAt', 'updatedAt'], path)
  return {
    conversationId: nonBlankStringAt(object.conversationId, `${path}.conversationId`),
    workspaceId: nonBlankStringAt(object.workspaceId, `${path}.workspaceId`),
    createdBy: nonBlankStringAt(object.createdBy, `${path}.createdBy`),
    title: nonBlankStringAt(object.title, `${path}.title`),
    status: enumAt(object.status, CONVERSATION_STATUSES, `${path}.status`),
    createdAt: stringAt(object.createdAt, `${path}.createdAt`),
    updatedAt: stringAt(object.updatedAt, `${path}.updatedAt`),
  }
}

export function decodeConversationTurn(value: unknown, path = 'turn'): ConversationTurn {
  const object = objectAt(value, path)
  exactKeys(object, ['turnId', 'conversationId', 'turnIndex', 'userContent', 'assistantContent', 'runId', 'status', 'error', 'createdAt', 'completedAt'], path)
  return {
    turnId: nonBlankStringAt(object.turnId, `${path}.turnId`),
    conversationId: nonBlankStringAt(object.conversationId, `${path}.conversationId`),
    turnIndex: nonNegativeIntegerAt(object.turnIndex, `${path}.turnIndex`),
    userContent: nonBlankStringAt(object.userContent, `${path}.userContent`),
    assistantContent: nullableStringAt(object.assistantContent, `${path}.assistantContent`),
    runId: nullableStringAt(object.runId, `${path}.runId`),
    status: enumAt(object.status, TURN_STATUSES, `${path}.status`),
    error: nullableStringAt(object.error, `${path}.error`),
    createdAt: stringAt(object.createdAt, `${path}.createdAt`),
    completedAt: nullableStringAt(object.completedAt, `${path}.completedAt`),
  }
}

async function requestJson(url: string, init: RequestInit, fetcher: typeof fetch): Promise<unknown> {
  const response = await fetcher(url, init)
  const text = await response.text()
  if (!response.ok) throw new ConversationApiError(problemDetailMessage(text, response.status), response.status, text)
  try {
    return JSON.parse(text) as unknown
  } catch (error) {
    throw new TypeError(`Conversation API 返回了非法 JSON: ${(error as Error).message}`)
  }
}

function problemDetailMessage(text: string, status: number): string {
  try {
    const problem = objectAt(JSON.parse(text) as unknown, 'problemDetail')
    if (typeof problem.detail === 'string' && problem.detail.trim().length > 0) return problem.detail
  } catch {
    // 非 ProblemDetail 响应保留稳定的 HTTP 状态错误。
  }
  return `Conversation API 请求失败: HTTP ${status}`
}

function decodeArray<T>(value: unknown, decoder: (item: unknown, path: string) => T, path: string): T[] {
  if (!Array.isArray(value)) throw new TypeError(`${path} 必须是数组`)
  return value.map((item, index) => decoder(item, `${path}[${index}]`))
}

export async function getIdentity(fetcher: typeof fetch = globalThis.fetch): Promise<Actor> {
  return decodeActor(await requestJson('/api/identity', { method: 'GET' }, fetcher))
}

export async function listWorkspaces(fetcher: typeof fetch = globalThis.fetch): Promise<Workspace[]> {
  return decodeArray(await requestJson('/api/workspaces', { method: 'GET' }, fetcher), decodeWorkspace, 'workspaces')
}

export interface CreateWorkspaceCommand { displayName: string; workspacePath: string; repositoryId: string }

export async function createWorkspace(command: CreateWorkspaceCommand, fetcher: typeof fetch = globalThis.fetch): Promise<Workspace> {
  const body = {
    displayName: nonBlankStringAt(command.displayName, 'displayName'),
    workspacePath: nonBlankStringAt(command.workspacePath, 'workspacePath'),
    repositoryId: nonBlankStringAt(command.repositoryId, 'repositoryId'),
  }
  return decodeWorkspace(await requestJson('/api/workspaces', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }, fetcher))
}

export async function browseWorkspaceDirectories(path = '/agent-workspace', fetcher: typeof fetch = globalThis.fetch): Promise<WorkspaceDirectoryListing> {
  const exactPath = nonBlankStringAt(path, 'path')
  return decodeWorkspaceDirectoryListing(await requestJson(`/api/workspace-directories?path=${encodeURIComponent(exactPath)}`, { method: 'GET' }, fetcher))
}

export interface ImportWorkspaceCommand {
  displayName: string
  repositoryId: string
  files: File[]
}

export async function importWorkspace(command: ImportWorkspaceCommand, fetcher: typeof fetch = globalThis.fetch): Promise<Workspace> {
  const displayName = nonBlankStringAt(command.displayName, 'displayName')
  const repositoryId = nonBlankStringAt(command.repositoryId, 'repositoryId')
  if (!Array.isArray(command.files) || command.files.length === 0) throw new TypeError('files 必须至少包含一个文件')
  const { zipSync } = await import('fflate')
  const files: Record<string, Uint8Array> = {}
  for (const file of command.files) {
    const relativePath = file.webkitRelativePath.trim()
    if (relativePath.length === 0) throw new TypeError('files 中的文件必须包含 webkitRelativePath')
    files[relativePath] = new Uint8Array(await file.arrayBuffer())
  }
  const archive = zipSync(files)
  const form = new FormData()
  form.append('displayName', displayName)
  form.append('repositoryId', repositoryId)
  form.append('archive', new Blob([archive], { type: 'application/zip' }), 'project.zip')
  return decodeWorkspace(await requestJson('/api/workspace-imports', { method: 'POST', body: form }, fetcher))
}

export async function listConversations(workspaceId: string, includeArchivedOrFetcher: boolean | typeof fetch = false, fetcher: typeof fetch = globalThis.fetch): Promise<Conversation[]> {
  const includeArchived = typeof includeArchivedOrFetcher === 'boolean' ? includeArchivedOrFetcher : false
  if (typeof includeArchivedOrFetcher === 'function') fetcher = includeArchivedOrFetcher
  const id = nonBlankStringAt(workspaceId, 'workspaceId')
  return decodeArray(await requestJson(`/api/workspaces/${encodeURIComponent(id)}/conversations${includeArchived ? '?includeArchived=true' : ''}`, { method: 'GET' }, fetcher), decodeConversation, 'conversations')
}

export async function listModelConfiguration(fetcher: typeof fetch = globalThis.fetch): Promise<ModelConfigurationSnapshot> {
  const value = objectAt(await requestJson('/api/model-config', { method: 'GET' }, fetcher), 'modelConfiguration')
  exactKeys(value, ['providers', 'endpoints', 'groups'], 'modelConfiguration')
  const providers = decodeArray(value.providers, (item, path) => {
    const object = objectAt(item, path); exactKeys(object, ['providerId', 'ownerUserId', 'displayName', 'baseUrl', 'chatCompletionsPath', 'apiKeyMasked', 'createdAt', 'updatedAt'], path)
    return { providerId: nonBlankStringAt(object.providerId, `${path}.providerId`), ownerUserId: nonBlankStringAt(object.ownerUserId, `${path}.ownerUserId`), displayName: nonBlankStringAt(object.displayName, `${path}.displayName`), baseUrl: nonBlankStringAt(object.baseUrl, `${path}.baseUrl`), chatCompletionsPath: nonBlankStringAt(object.chatCompletionsPath, `${path}.chatCompletionsPath`), apiKeyMasked: nonBlankStringAt(object.apiKeyMasked, `${path}.apiKeyMasked`), createdAt: stringAt(object.createdAt, `${path}.createdAt`), updatedAt: stringAt(object.updatedAt, `${path}.updatedAt`) }
  }, 'providers')
  const endpoints = decodeArray(value.endpoints, (item, path) => {
    const object = objectAt(item, path); exactKeys(object, ['endpointId', 'providerId', 'displayName', 'modelId', 'capabilities', 'priority', 'weight', 'enabled', 'createdAt', 'updatedAt'], path)
    if (!Array.isArray(object.capabilities) || object.capabilities.some((entry) => typeof entry !== 'string' || !MODEL_CAPABILITIES.has(entry))) throw new TypeError(`${path}.capabilities 非法`)
    return { endpointId: nonBlankStringAt(object.endpointId, `${path}.endpointId`), providerId: nonBlankStringAt(object.providerId, `${path}.providerId`), displayName: nonBlankStringAt(object.displayName, `${path}.displayName`), modelId: nonBlankStringAt(object.modelId, `${path}.modelId`), capabilities: object.capabilities as any[], priority: nonNegativeIntegerAt(object.priority, `${path}.priority`), weight: nonNegativeIntegerAt(object.weight, `${path}.weight`), enabled: object.enabled === true, createdAt: stringAt(object.createdAt, `${path}.createdAt`), updatedAt: stringAt(object.updatedAt, `${path}.updatedAt`) }
  }, 'endpoints')
  const groups = decodeArray(value.groups, (item, path) => {
    const object = objectAt(item, path); exactKeys(object, ['groupId', 'ownerUserId', 'displayName', 'taskType', 'endpointIds', 'createdAt', 'updatedAt'], path)
    if (!Array.isArray(object.endpointIds) || object.endpointIds.some((entry) => typeof entry !== 'string')) throw new TypeError(`${path}.endpointIds 非法`)
    return { groupId: nonBlankStringAt(object.groupId, `${path}.groupId`), ownerUserId: nonBlankStringAt(object.ownerUserId, `${path}.ownerUserId`), displayName: nonBlankStringAt(object.displayName, `${path}.displayName`), taskType: enumAt(object.taskType, MODEL_TASK_TYPES, `${path}.taskType`) as any, endpointIds: object.endpointIds as string[], createdAt: stringAt(object.createdAt, `${path}.createdAt`), updatedAt: stringAt(object.updatedAt, `${path}.updatedAt`) }
  }, 'groups')
  return { providers, endpoints, groups }
}

export interface CreateModelProviderCommand { displayName: string; baseUrl: string; chatCompletionsPath?: string; apiKey: string }
export async function createModelProvider(command: CreateModelProviderCommand, fetcher: typeof fetch = globalThis.fetch): Promise<ModelConfigurationSnapshot> {
  await requestJson('/api/model-config/providers', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(command) }, fetcher)
  return listModelConfiguration(fetcher)
}

export interface CreateModelGroupCommand { displayName: string; taskType: string; endpointIds: string[] }
export async function createModelGroup(command: CreateModelGroupCommand, fetcher: typeof fetch = globalThis.fetch): Promise<ModelConfigurationSnapshot> {
  await requestJson('/api/model-config/groups', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(command) }, fetcher)
  return listModelConfiguration(fetcher)
}

export interface CreateModelEndpointCommand { providerId: string; displayName: string; modelId: string; capabilities: string[]; priority: number; weight: number; enabled: boolean }
export async function createModelEndpoint(command: CreateModelEndpointCommand, fetcher: typeof fetch = globalThis.fetch): Promise<ModelConfigurationSnapshot> {
  await requestJson('/api/model-config/endpoints', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(command) }, fetcher)
  return listModelConfiguration(fetcher)
}

export async function searchConversations(workspaceId: string, query: string, includeArchivedOrFetcher: boolean | typeof fetch = false, fetcher: typeof fetch = globalThis.fetch): Promise<Conversation[]> {
  const includeArchived = typeof includeArchivedOrFetcher === 'boolean' ? includeArchivedOrFetcher : false
  if (typeof includeArchivedOrFetcher === 'function') fetcher = includeArchivedOrFetcher
  const id = nonBlankStringAt(workspaceId, 'workspaceId')
  const params = new URLSearchParams({ query }); if (includeArchived) params.set('includeArchived', 'true')
  return decodeArray(await requestJson(`/api/workspaces/${encodeURIComponent(id)}/conversations?${params.toString()}`, { method: 'GET' }, fetcher), decodeConversation, 'conversations')
}

export async function createConversation(workspaceId: string, fetcher: typeof fetch = globalThis.fetch): Promise<Conversation> {
  const id = nonBlankStringAt(workspaceId, 'workspaceId')
  return decodeConversation(await requestJson(`/api/workspaces/${encodeURIComponent(id)}/conversations`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' }, fetcher))
}

export interface SubmitConversationTurnCommand { content: string; reviewerUrl?: string; modelGroupId?: string }

export async function submitConversationTurn(conversationId: string, command: SubmitConversationTurnCommand, fetcher: typeof fetch = globalThis.fetch): Promise<ConversationTurn> {
  const id = nonBlankStringAt(conversationId, 'conversationId')
  const body: Record<string, string> = { content: nonBlankStringAt(command.content, 'content') }
  if (command.reviewerUrl !== undefined && command.reviewerUrl.trim().length > 0) body.reviewerUrl = command.reviewerUrl.trim()
  if (command.modelGroupId !== undefined && command.modelGroupId.trim().length > 0) body.modelGroupId = command.modelGroupId.trim()
  return decodeConversationTurn(await requestJson(`/api/conversations/${encodeURIComponent(id)}/turns`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }, fetcher))
}

export async function deleteConversation(conversationId: string, fetcher: typeof fetch = globalThis.fetch): Promise<Conversation> {
  const id = nonBlankStringAt(conversationId, 'conversationId')
  return decodeConversation(await requestJson(`/api/conversations/${encodeURIComponent(id)}`, { method: 'DELETE' }, fetcher))
}

export async function listConversationTurns(conversationId: string, fetcher: typeof fetch = globalThis.fetch): Promise<ConversationTurn[]> {
  const id = nonBlankStringAt(conversationId, 'conversationId')
  return decodeArray(await requestJson(`/api/conversations/${encodeURIComponent(id)}/turns`, { method: 'GET' }, fetcher), decodeConversationTurn, 'turns')
}

export async function archiveConversation(conversationId: string, fetcher: typeof fetch = globalThis.fetch): Promise<Conversation> {
  const id = nonBlankStringAt(conversationId, 'conversationId')
  return decodeConversation(await requestJson(`/api/conversations/${encodeURIComponent(id)}/archive`, { method: 'POST' }, fetcher))
}
