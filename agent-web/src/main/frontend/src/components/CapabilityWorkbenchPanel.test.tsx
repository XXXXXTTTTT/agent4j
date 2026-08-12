import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { CapabilityWorkbenchPanel } from './CapabilityWorkbenchPanel'

const catalog = { repository: 'modelcontextprotocol/servers', commitSha: 'a'.repeat(40), fetchedAt: '', expiresAt: '', etag: '', status: 'FRESH' as const, servers: [{ serviceId: 'everything', sourcePath: 'src/everything', sourceUrl: 'https://github.com/modelcontextprotocol/servers/tree/abc/src/everything', commitSha: 'a'.repeat(40), blobShas: {}, metadataSha256: 'b'.repeat(64), version: '1.0.0', description: '官方服务', license: 'MIT', command: 'npx', arguments: ['-y', 'server'], launchBin: 'server', environmentVariableNames: ['MCP_TOKEN'], readmeSummary: '说明' }], errors: {} }

describe('CapabilityWorkbenchPanel', () => {
  it('previews MCP provenance and confirms only after explicit approval', async () => {
    const user = userEvent.setup()
    const previewMcp = vi.fn(async () => ({ previewId: 'p1', confirmationToken: 'token', sourceUrl: 'https://github.com/modelcontextprotocol/servers/tree/abc/src/everything', commitSha: 'a'.repeat(40), metadataSha256: 'b'.repeat(64), command: 'npx', arguments: ['-y', 'server'], environmentVariableNames: ['MCP_TOKEN'], summary: '说明', scope: 'WORKSPACE' as const, workspaceId: 'ws-1', requiresConfirmation: true, sideEffectFree: true, expiresAt: '' }))
    const confirmMcp = vi.fn(async () => undefined)
    render(<CapabilityWorkbenchPanel workspaceId="ws-1" mcpCatalog={catalog} skillResults={[]} onPreviewMcp={previewMcp} onConfirmMcp={confirmMcp} onPreviewSkill={vi.fn()} onConfirmSkill={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: '预览 everything' }))
    expect(previewMcp).toHaveBeenCalledWith('everything', 'WORKSPACE')
    expect(screen.getByText('Commit')).toBeVisible()
    expect(screen.getByText('需要确认')).toBeVisible()
    expect(confirmMcp).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '确认安装 MCP' }))
    expect(confirmMcp).toHaveBeenCalledWith(expect.objectContaining({ previewId: 'p1', scope: 'WORKSPACE' }))
  })
})
