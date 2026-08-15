import type { WorkspaceFileContent, WorkspaceFileEntry } from './contracts'

type Fetcher = typeof fetch

async function requestJson<T>(url: string, init: RequestInit, fetcher: Fetcher): Promise<T> {
  const response = await fetcher(url, init)
  const value: unknown = await response.json().catch(() => null)
  if (!response.ok) {
    const detail = value && typeof value === 'object' && 'detail' in value && typeof value.detail === 'string'
      ? value.detail : `请求失败: HTTP ${response.status}`
    throw new Error(detail)
  }
  return value as T
}

function objectAt(value: unknown, path: string): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError(`${path} 必须是对象`)
  return value as Record<string, unknown>
}

function textAt(value: Record<string, unknown>, key: string, path: string): string {
  if (typeof value[key] !== 'string' || value[key].trim().length === 0) throw new TypeError(`${path}.${key} 必须是非空字符串`)
  return value[key] as string
}

function decodeEntry(value: unknown, index: number): WorkspaceFileEntry {
  const path = `files[${index}]`
  const object = objectAt(value, path)
  const kind = textAt(object, 'kind', path)
  if (kind !== 'DIRECTORY' && kind !== 'FILE') throw new TypeError(`${path}.kind 非法`)
  if (typeof object.size !== 'number' || !Number.isInteger(object.size) || object.size < 0) throw new TypeError(`${path}.size 非法`)
  return { name: textAt(object, 'name', path), path: textAt(object, 'path', path), kind, size: object.size, lastModified: textAt(object, 'lastModified', path) }
}

function decodeContent(value: unknown): WorkspaceFileContent {
  const object = objectAt(value, 'file')
  return { path: textAt(object, 'path', 'file'), content: typeof object.content === 'string' ? object.content : (() => { throw new TypeError('file.content 必须是字符串') })(), sha256: textAt(object, 'sha256', 'file'), lastModified: textAt(object, 'lastModified', 'file') }
}

function workspaceUrl(workspaceId: string, suffix: string): string {
  if (workspaceId.trim().length === 0) throw new TypeError('workspaceId 不能为空')
  return `/api/workspaces/${encodeURIComponent(workspaceId)}${suffix}`
}

export async function listWorkspaceFiles(workspaceId: string, path = '', fetcher: Fetcher = globalThis.fetch): Promise<WorkspaceFileEntry[]> {
  const query = path.length === 0 ? '' : `?path=${encodeURIComponent(path)}`
  const value = await requestJson<unknown>(workspaceUrl(workspaceId, `/files${query}`), { method: 'GET' }, fetcher)
  if (!Array.isArray(value)) throw new TypeError('files 必须是数组')
  return value.map(decodeEntry)
}

export async function readWorkspaceFile(workspaceId: string, path: string, fetcher: Fetcher = globalThis.fetch): Promise<WorkspaceFileContent> {
  const value = await requestJson<unknown>(workspaceUrl(workspaceId, `/files/content?path=${encodeURIComponent(path)}`), { method: 'GET' }, fetcher)
  return decodeContent(value)
}

export async function writeWorkspaceFile(workspaceId: string, path: string, content: string, expectedSha256: string, fetcher: Fetcher = globalThis.fetch): Promise<WorkspaceFileContent> {
  const value = await requestJson<unknown>(workspaceUrl(workspaceId, '/files/content'), { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ path, content, expectedSha256 }) }, fetcher)
  return decodeContent(value)
}
