import { describe, expect, it, vi } from 'vitest'

import { decodeMcpCatalog, decodeSkillRepository, listMcpCatalog } from './capabilityApi'

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

  it('decodes GitHub skill repository metadata', () => {
    expect(decodeSkillRepository({ repository: 'octo/review', repositoryUrl: 'https://github.com/octo/review', defaultBranch: 'main', description: '审查', license: 'MIT' })).toEqual({ repository: 'octo/review', repositoryUrl: 'https://github.com/octo/review', defaultBranch: 'main', description: '审查', license: 'MIT' })
  })
})
