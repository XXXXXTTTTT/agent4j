import { decodeRunView, RunApiError } from './runApi'
import type {
  CliRiskLevel,
  GovernedCliCommand,
  GovernedCliRunCommand,
  RunView,
} from './contracts'

export type { CliRiskLevel, GovernedCliCommand, GovernedCliRunCommand }

type JsonObject = Record<string, unknown>

const RISK_LEVELS = new Set<CliRiskLevel>(['READ_ONLY', 'MUTATING', 'DESTRUCTIVE'])

function objectAt(value: unknown, path: string): JsonObject {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new TypeError(`${path} 必须是对象`)
  }
  return value as JsonObject
}

function exactKeys(value: JsonObject, expected: readonly string[], path: string): void {
  const expectedSet = new Set(expected)
  const unknown = Object.keys(value).filter((key) => !expectedSet.has(key))
  if (unknown.length > 0) throw new TypeError(`${path} 包含未知字段: ${unknown.join(', ')}`)
}

function nonBlankStringAt(value: unknown, path: string): string {
  if (typeof value !== 'string') throw new TypeError(`${path} 必须是字符串`)
  if (value.trim().length === 0) throw new TypeError(`${path} 不能为空`)
  return value
}

function stringArrayAt(value: unknown, path: string): string[] {
  if (!Array.isArray(value)) throw new TypeError(`${path} 必须是数组`)
  return value.map((item, index) => nonBlankStringAt(item, `${path}[${index}]`))
}

function positiveIntegerAt(value: unknown, path: string): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value <= 0) {
    throw new TypeError(`${path} 必须是正整数`)
  }
  return value
}

function workspaceId(value: string): string {
  return nonBlankStringAt(value, 'workspaceId')
}

function decodeCommand(value: unknown, path: string): GovernedCliCommand {
  const object = objectAt(value, path)
  exactKeys(object, [
    'name', 'executable', 'fixedArguments', 'riskLevel', 'requiredCapabilities', 'maxArguments',
  ], path)
  const riskLevel = nonBlankStringAt(object.riskLevel, `${path}.riskLevel`)
  if (!RISK_LEVELS.has(riskLevel as CliRiskLevel)) {
    throw new TypeError(`${path}.riskLevel 包含未知值: ${riskLevel}`)
  }
  return {
    name: nonBlankStringAt(object.name, `${path}.name`),
    executable: nonBlankStringAt(object.executable, `${path}.executable`),
    fixedArguments: stringArrayAt(object.fixedArguments, `${path}.fixedArguments`),
    riskLevel: riskLevel as CliRiskLevel,
    requiredCapabilities: stringArrayAt(object.requiredCapabilities, `${path}.requiredCapabilities`),
    maxArguments: positiveIntegerAt(object.maxArguments, `${path}.maxArguments`),
  }
}

function validateRunCommand(command: GovernedCliRunCommand): GovernedCliRunCommand {
  const commandName = nonBlankStringAt(command.commandName, 'commandName')
  if (!Array.isArray(command.arguments) || command.arguments.length > 64) {
    throw new TypeError('arguments 必须是最多 64 项的数组')
  }
  const argumentsList = command.arguments.map((argument, index) =>
    nonBlankStringAt(argument, `arguments[${index}]`),
  )
  const timeoutSeconds = positiveIntegerAt(command.timeoutSeconds, 'timeoutSeconds')
  if (timeoutSeconds > 600) throw new TypeError('timeoutSeconds 不能大于 600')
  return { commandName, arguments: argumentsList, timeoutSeconds }
}

async function requestJson(url: string, init: RequestInit, fetcher: typeof fetch): Promise<unknown> {
  const response = await fetcher(url, init)
  const text = await response.text()
  if (!response.ok) throw new RunApiError(`CLI API 请求失败: HTTP ${response.status}`, response.status, text)
  try {
    return JSON.parse(text) as unknown
  } catch (error) {
    throw new TypeError(`CLI API 返回了非法 JSON: ${(error as Error).message}`)
  }
}

/** 读取当前工作区的受治理命令目录。 */
export async function listGovernedCliCommands(
  currentWorkspaceId: string,
  fetcher: typeof fetch = globalThis.fetch,
): Promise<GovernedCliCommand[]> {
  const id = workspaceId(currentWorkspaceId)
  const body = await requestJson(
    `/api/workspaces/${encodeURIComponent(id)}/cli/commands`,
    { method: 'GET' },
    fetcher,
  )
  if (!Array.isArray(body)) throw new TypeError('commands 必须是数组')
  return body.map((item, index) => decodeCommand(item, `commands[${index}]`))
}

/** 创建结构化受治理 CLI Run，不发送 Shell 文本或审批覆盖字段。 */
export async function createGovernedCliRun(
  currentWorkspaceId: string,
  command: GovernedCliRunCommand,
  fetcher: typeof fetch = globalThis.fetch,
): Promise<RunView> {
  const id = workspaceId(currentWorkspaceId)
  const checked = validateRunCommand(command)
  return decodeRunView(await requestJson(
    `/api/workspaces/${encodeURIComponent(id)}/cli/runs`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(checked),
    },
    fetcher,
  ))
}
