import { describe, expect, it, vi } from 'vitest'

import { decodeMcpCatalog, decodeSkillRepository, listMcpCatalog, listMcpInstallations, previewMcp, refreshMcpCatalog, startMcpInstallation } from './capabilityApi'

const server = {
  serviceId: 'everything', sourcePath: 'src/everything', sourceUrl: 'https://github.com/modelcontextprotocol/servers/tree/abc/src/everything',
  commitSha: 'a'.repeat(40), blobShas: { 'package.json': 'b'.repeat(40) }, metadataSha256: 'c'.repeat(64), version: '1.0.0',
  description: '官方服务', license: 'MIT', command: 'npx', arguments: ['-y', 'server'], launchBin: 'server',
  environmentVariableNames: ['MCP_TOKEN'], readmeSummary: '说明',
}

describe('capabilityApi', () => {
  it('decodes official MCP catalog metadata without dropping provenance', () => {
    const value = decodeMcpCatalog({ repository: 'modelcontextprotocol/servers', commitSha: 'a'.repeat(40), fetchedAt: '2026-08-12T00:00:00Z', expiresAt: '2026-08-12T00:05:00Z', etag: 'etag', status: 'FRESH', servers: [server], errors: {} })
    expect(value.servers[0]).toMatchObject({ serviceId: 'everything', commitSha: 'a'.repeat(40), license: 'MIT', environmentVariableNames: ['MCP_TOKEN'] })
  })

  it('rejects unknown catalog fields instead of guessing a schema', () => {
    expect(() => decodeMcpCatalog({ repository: 'x', commitSha: 'a'.repeat(40), fetchedAt: '', expiresAt: '', etag: '', status: 'FRESH', servers: [], errors: {}, extra: true })).toThrow('未知字段')
  })

  it('loads catalog from the exact backend endpoint', async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify({ repository: 'modelcontextprotocol/servers', commitSha: 'a'.repeat(40), fetchedAt: '', expiresAt: '', etag: '', status: 'FRESH', servers: [], errors: {} }), { status: 200 }))
    await listMcpCatalog(fetcher)
    expect(fetcher).toHaveBeenCalledWith('/api/mcp/catalog', { method: 'GET' })
  })

  it('refreshes the official MCP catalog through the exact backend endpoint', async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify({ repository: 'modelcontextprotocol/servers', commitSha: 'a'.repeat(40), fetchedAt: '', expiresAt: '', etag: '', status: 'FRESH', servers: [], errors: {} }), { status: 200 }))

    await refreshMcpCatalog(fetcher)

    expect(fetcher).toHaveBeenCalledWith('/api/mcp/catalog/refresh', { method: 'POST' })
  })

  it('decodes GitHub skill repository metadata', () => {
    expect(decodeSkillRepository({ repository: 'octo/review', repositoryUrl: 'https://github.com/octo/review', defaultBranch: 'main', description: '审查', license: 'MIT' })).toEqual({ repository: 'octo/review', repositoryUrl: 'https://github.com/octo/review', defaultBranch: 'main', description: '审查', license: 'MIT' })
  })

  it('sends the governed MCP preview contract and decodes its runtime controls', async () => {
    const response = {
      previewId: 'preview-1', confirmationToken: 'token', sourceUrl: 'https://github.com/modelcontextprotocol/servers/tree/abc/src/everything',
      commitSha: 'a'.repeat(40), metadataSha256: 'c'.repeat(64), command: 'npx', arguments: ['-y', 'server'],
      environmentNames: ['MCP_TOKEN'], summary: '说明', scope: 'WORKSPACE', workspaceId: 'ws-1', riskLevel: 'HIGH',
      requiredCapabilities: ['TOOL'], workspaceMountMode: 'NONE', networkMode: 'NONE', runtimeImage: 'node:22-alpine',
      requiresConfirmation: true, sideEffectFree: true, expiresAt: '2026-08-12T00:05:00Z',
    }
    const fetcher = vi.fn(async () => new Response(JSON.stringify(response), { status: 200 }))

    const preview = await previewMcp('ws-1', 'everything', 'WORKSPACE', fetcher)

    expect(preview).toMatchObject({ riskLevel: 'HIGH', workspaceMountMode: 'NONE', networkMode: 'NONE' })
    expect(JSON.parse(String(fetcher.mock.calls[0][1]?.body))).toEqual({
      serverKey: 'everything', scope: 'WORKSPACE', targetWorkspaceId: 'ws-1',
      riskLevel: 'HIGH', requiredCapabilities: ['TOOL'], workspaceMountMode: 'NONE',
    })
  })

  it('decodes installed MCP lifecycle state without accepting unknown fields', async () => {
    const installation = {
      installationId: '00000000-0000-0000-0000-000000000001', snapshotId: '00000000-0000-0000-0000-000000000002',
      scope: 'WORKSPACE', workspaceId: 'ws-1', actorUserId: 'user-1', status: 'STOPPED',
      createdAt: '2026-08-12T00:00:00Z', confirmedAt: '2026-08-12T00:00:00Z', updatedAt: '2026-08-12T00:00:00Z',
      riskLevel: 'HIGH', requiredCapabilities: ['TOOL'], workspaceMountMode: 'NONE', networkMode: 'NONE',
      environmentNames: ['MCP_TOKEN'],
      environmentNames: ['MCP_TOKEN'], runtimeWorkspaceId: null, runtimeState: 'STOPPED', runtimeError: '', version: 3,
    }
    const fetcher = vi.fn(async () => new Response(JSON.stringify([installation]), { status: 200 }))

    const result = await listMcpInstallations('ws-1', fetcher)

    expect(result).toEqual([expect.objectContaining({ installationId: installation.installationId, version: 3 })])
    expect(fetcher).toHaveBeenCalledWith('/api/workspaces/ws-1/mcp/installations', { method: 'GET' })
  })

  it('submits only the current password values for snapshot-declared MCP environment names', async () => {
    const installation = {
      installationId: '00000000-0000-0000-0000-000000000001', snapshotId: '00000000-0000-0000-0000-000000000002',
      scope: 'WORKSPACE' as const, workspaceId: 'ws-1', actorUserId: 'user-1', status: 'STOPPED',
      createdAt: '2026-08-12T00:00:00Z', confirmedAt: '2026-08-12T00:00:00Z', updatedAt: '2026-08-12T00:00:00Z',
      riskLevel: 'HIGH' as const, requiredCapabilities: ['TOOL'] as const, workspaceMountMode: 'NONE' as const, networkMode: 'NONE' as const,
      environmentNames: ['MCP_TOKEN'], runtimeWorkspaceId: null, runtimeState: 'STOPPED', runtimeError: '', version: 3,
    }
    const fetcher = vi.fn(async () => new Response(JSON.stringify(installation), { status: 200 }))

    await startMcpInstallation('ws-1', installation, { MCP_TOKEN: 'secret-value' }, fetcher)

    expect(JSON.parse(String(fetcher.mock.calls[0][1]?.body))).toEqual({
      expectedVersion: 3, targetWorkspaceId: 'ws-1', environment: { MCP_TOKEN: 'secret-value' },
    })
  })
})
