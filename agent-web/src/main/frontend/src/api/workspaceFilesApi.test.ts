import { describe, expect, it, vi } from 'vitest'

import { listWorkspaceFiles, readWorkspaceFile, writeWorkspaceFile } from './workspaceFilesApi'

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('workspaceFilesApi', () => {
  it('uses exact relative-path endpoints and decodes entries', async () => {
    const fetcher = vi.fn(async () => response([{ name: 'src', path: 'src', kind: 'DIRECTORY', size: 0, lastModified: '2026-08-16T00:00:00Z' }]))
    await expect(listWorkspaceFiles('ws 1', '', fetcher)).resolves.toEqual([{ name: 'src', path: 'src', kind: 'DIRECTORY', size: 0, lastModified: '2026-08-16T00:00:00Z' }])
    expect(fetcher).toHaveBeenCalledWith('/api/workspaces/ws%201/files', { method: 'GET' })
  })

  it('reads and writes content with the optimistic SHA', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(response({ path: 'Main.java', content: 'old', sha256: 'sha-1', lastModified: 'now' }))
      .mockResolvedValueOnce(response({ path: 'Main.java', content: 'new', sha256: 'sha-2', lastModified: 'now' }))
    await expect(readWorkspaceFile('ws-1', 'Main.java', fetcher)).resolves.toMatchObject({ content: 'old', sha256: 'sha-1' })
    await expect(writeWorkspaceFile('ws-1', 'Main.java', 'new', 'sha-1', fetcher)).resolves.toMatchObject({ content: 'new', sha256: 'sha-2' })
    expect(fetcher).toHaveBeenNthCalledWith(2, '/api/workspaces/ws-1/files/content', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ path: 'Main.java', content: 'new', expectedSha256: 'sha-1' }) }))
  })
})
