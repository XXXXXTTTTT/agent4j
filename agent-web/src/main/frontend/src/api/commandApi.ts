export type CommandChannel = 'SYSTEM_DIRECTIVE' | 'WORKFLOW_SKILL'
export type CommandSource = 'BUILT_IN' | 'GLOBAL' | 'WORKSPACE'
export type CommandPermission = 'VIEWER' | 'OPERATOR' | 'ADMIN'
export type CommandResultStatus = 'COMPLETED' | 'FORWARDED' | 'INVALID' | 'NOT_FOUND' | 'DENIED' | 'FAILED'

export interface SlashCommandParameter {
  name: string
  description: string
  required: boolean
}

export interface SlashCommandDefinition {
  name: string
  displayName: string
  description: string
  aliases: string[]
  parameters: SlashCommandParameter[]
  channel: CommandChannel
  source: CommandSource
  permission: CommandPermission
}

export interface SlashCommandCatalog {
  revision: number
  commands: SlashCommandDefinition[]
}

export interface SlashCommandInvocation {
  input: string
  conversationId: string
  modelGroupId?: string
}

export interface SlashCommandResult {
  status: CommandResultStatus
  commandName: string | null
  message: string
  data: Record<string, unknown>
}

type JsonObject = Record<string, unknown>

const CHANNELS = new Set<CommandChannel>(['SYSTEM_DIRECTIVE', 'WORKFLOW_SKILL'])
const SOURCES = new Set<CommandSource>(['BUILT_IN', 'GLOBAL', 'WORKSPACE'])
const PERMISSIONS = new Set<CommandPermission>(['VIEWER', 'OPERATOR', 'ADMIN'])
const STATUSES = new Set<CommandResultStatus>(['COMPLETED', 'FORWARDED', 'INVALID', 'NOT_FOUND', 'DENIED', 'FAILED'])

function objectAt(value: unknown, path: string): JsonObject {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError(`${path} 必须是对象`)
  return value as JsonObject
}

function exactKeys(value: JsonObject, expected: readonly string[], path: string): void {
  const allowed = new Set(expected)
  const unknown = Object.keys(value).filter((key) => !allowed.has(key))
  if (unknown.length > 0) throw new TypeError(`${path} 包含未知字段: ${unknown.join(', ')}`)
}

function nonBlankString(value: unknown, path: string): string {
  if (typeof value !== 'string' || value.trim().length === 0) throw new TypeError(`${path} 不能为空`)
  return value
}

function stringArray(value: unknown, path: string): string[] {
  if (!Array.isArray(value)) throw new TypeError(`${path} 必须是数组`)
  return value.map((item, index) => nonBlankString(item, `${path}[${index}]`))
}

function booleanAt(value: unknown, path: string): boolean {
  if (typeof value !== 'boolean') throw new TypeError(`${path} 必须是布尔值`)
  return value
}

function positiveInteger(value: unknown, path: string): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 0) throw new TypeError(`${path} 必须是非负整数`)
  return value
}

function decodeParameter(value: unknown, path: string): SlashCommandParameter {
  const object = objectAt(value, path)
  exactKeys(object, ['name', 'description', 'required'], path)
  return {
    name: nonBlankString(object.name, `${path}.name`),
    description: nonBlankString(object.description, `${path}.description`),
    required: booleanAt(object.required, `${path}.required`),
  }
}

function decodeCommand(value: unknown, path: string): SlashCommandDefinition {
  const object = objectAt(value, path)
  exactKeys(object, ['name', 'displayName', 'description', 'aliases', 'parameters', 'channel', 'source', 'permission'], path)
  const channel = nonBlankString(object.channel, `${path}.channel`)
  const source = nonBlankString(object.source, `${path}.source`)
  const permission = nonBlankString(object.permission, `${path}.permission`)
  if (!CHANNELS.has(channel as CommandChannel)) throw new TypeError(`${path}.channel 包含未知值: ${channel}`)
  if (!SOURCES.has(source as CommandSource)) throw new TypeError(`${path}.source 包含未知值: ${source}`)
  if (!PERMISSIONS.has(permission as CommandPermission)) throw new TypeError(`${path}.permission 包含未知值: ${permission}`)
  if (!Array.isArray(object.parameters)) throw new TypeError(`${path}.parameters 必须是数组`)
  return {
    name: nonBlankString(object.name, `${path}.name`),
    displayName: nonBlankString(object.displayName, `${path}.displayName`),
    description: nonBlankString(object.description, `${path}.description`),
    aliases: stringArray(object.aliases, `${path}.aliases`),
    parameters: object.parameters.map((item, index) => decodeParameter(item, `${path}.parameters[${index}]`)),
    channel: channel as CommandChannel,
    source: source as CommandSource,
    permission: permission as CommandPermission,
  }
}

function decodeCatalog(value: unknown): SlashCommandCatalog {
  const object = objectAt(value, 'catalog')
  exactKeys(object, ['revision', 'commands'], 'catalog')
  if (!Array.isArray(object.commands)) throw new TypeError('catalog.commands 必须是数组')
  return {
    revision: positiveInteger(object.revision, 'catalog.revision'),
    commands: object.commands.map((item, index) => decodeCommand(item, `catalog.commands[${index}]`)),
  }
}

function decodeResult(value: unknown): SlashCommandResult {
  const object = objectAt(value, 'result')
  exactKeys(object, ['status', 'commandName', 'message', 'data'], 'result')
  const status = nonBlankString(object.status, 'result.status')
  if (!STATUSES.has(status as CommandResultStatus)) throw new TypeError(`result.status 包含未知值: ${status}`)
  if (object.commandName !== null && typeof object.commandName !== 'string') throw new TypeError('result.commandName 必须是字符串或 null')
  const data = objectAt(object.data, 'result.data')
  return { status: status as CommandResultStatus, commandName: object.commandName as string | null, message: nonBlankString(object.message, 'result.message'), data }
}

async function requestJson(url: string, init: RequestInit, fetcher: typeof fetch): Promise<unknown> {
  const response = await fetcher(url, init)
  const text = await response.text()
  if (!response.ok) {
    let detail = text
    try {
      const parsed = JSON.parse(text) as JsonObject
      if (typeof parsed.detail === 'string') detail = parsed.detail
    } catch { /* 非 JSON 错误正文保留原文 */ }
    throw new Error(`Slash Command API 请求失败: HTTP ${response.status} ${detail}`)
  }
  try {
    return JSON.parse(text) as unknown
  } catch (error) {
    throw new TypeError(`Slash Command API 返回了非法 JSON: ${(error as Error).message}`)
  }
}

/** 读取当前工作区实时 Slash Command Registry。 */
export async function listSlashCommands(workspaceId: string, fetcher: typeof fetch = globalThis.fetch): Promise<SlashCommandCatalog> {
  if (typeof workspaceId !== 'string' || workspaceId.trim().length === 0) throw new TypeError('workspaceId 不能为空')
  return decodeCatalog(await requestJson(`/api/workspaces/${encodeURIComponent(workspaceId)}/commands`, { method: 'GET' }, fetcher))
}

/** 提交一条 Slash Command，不绕过服务端权限和审计。 */
export async function dispatchSlashCommand(workspaceId: string, invocation: SlashCommandInvocation, fetcher: typeof fetch = globalThis.fetch): Promise<SlashCommandResult> {
  if (typeof workspaceId !== 'string' || workspaceId.trim().length === 0) throw new TypeError('workspaceId 不能为空')
  const input = nonBlankString(invocation.input, 'input')
  const conversationId = nonBlankString(invocation.conversationId, 'conversationId')
  const body: Record<string, string> = { input, conversationId }
  if (invocation.modelGroupId !== undefined && invocation.modelGroupId.trim().length > 0) body.modelGroupId = invocation.modelGroupId.trim()
  return decodeResult(await requestJson(`/api/workspaces/${encodeURIComponent(workspaceId)}/commands`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  }, fetcher))
}
