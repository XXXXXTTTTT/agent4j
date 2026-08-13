import { act, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

const capabilityApi = vi.hoisted(() => ({
  confirmMcp: vi.fn(), confirmSkill: vi.fn(), listMcpCatalog: vi.fn(), listMcpInstallations: vi.fn(),
  listSkillInstallations: vi.fn(), prepareMcpMaterial: vi.fn(), previewMcp: vi.fn(), previewSkill: vi.fn(),
  refreshMcpCatalog: vi.fn(), searchSkills: vi.fn(), startMcpInstallation: vi.fn(),
  stopMcpInstallation: vi.fn(), uninstallMcpInstallation: vi.fn(), uninstallSkillInstallation: vi.fn(),
}))

vi.mock('../api/capabilityApi', () => capabilityApi)
vi.mock('./CapabilityWorkbenchPanel', () => ({
  CapabilityWorkbenchPanel: ({ mcpCatalog, mcpInstallations, skillResults, onRefreshMcp, onSearchSkills, onStartMcp }: { mcpCatalog: { commitSha: string } | null; mcpInstallations: Array<{ version: number }>; skillResults: Array<{ repository: string }>; onRefreshMcp(): Promise<void>; onSearchSkills(query: string): Promise<void>; onStartMcp?(installation: ReturnType<typeof installation>, environment: Record<string, string>): Promise<void> }) => {
    const [searchError, setSearchError] = useState('')
    return <section>
      <output aria-label="安装版本">{mcpInstallations[0]?.version ?? 'empty'}</output>
      <output aria-label="目录版本">{mcpCatalog?.commitSha ?? 'empty'}</output>
      <output aria-label="Skills">{skillResults.map((skill) => skill.repository).join(',') || 'empty'}</output>
      <output aria-label="操作错误">{searchError}</output>
      <button type="button" onClick={() => void onRefreshMcp().catch((error) => setSearchError(String(error)))}>刷新</button>
      <button type="button" onClick={() => void onSearchSkills('first').catch((error) => setSearchError(String(error)))}>搜索 first</button>
      <button type="button" onClick={() => void onSearchSkills('second').catch((error) => setSearchError(String(error)))}>搜索 second</button>
      <button type="button" onClick={() => onStartMcp && void onStartMcp(mcpInstallations[0], { MCP_TOKEN: 'runtime-value' })}>启动</button>
    </section>
  },
}))

import { CapabilityWorkbenchRuntime } from './CapabilityWorkbenchRuntime'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

const catalog = { repository: 'modelcontextprotocol/servers', commitSha: 'a'.repeat(40), fetchedAt: '', expiresAt: '', etag: '', status: 'FRESH', servers: [], errors: {} }
const installation = (version: number) => ({ installationId: 'i-1', snapshotId: 's-1', scope: 'WORKSPACE', workspaceId: 'ws-1', actorUserId: 'u-1', status: 'STOPPED', createdAt: '', confirmedAt: '', updatedAt: '', riskLevel: 'HIGH', requiredCapabilities: ['TOOL'], workspaceMountMode: 'NONE', networkMode: 'NONE', environmentNames: [], runtimeWorkspaceId: null, runtimeState: 'STOPPED', runtimeError: '', version })
const skill = (repository: string) => ({ repository, repositoryUrl: `https://github.com/${repository}`, defaultBranch: 'main', description: '', license: '' })

describe('CapabilityWorkbenchRuntime', () => {
  it('does not let an older installation response overwrite a newer refresh', async () => {
    const user = userEvent.setup()
    const initial = deferred<ReturnType<typeof installation>[]>()
    capabilityApi.listMcpCatalog.mockResolvedValue(catalog)
    capabilityApi.refreshMcpCatalog.mockResolvedValue(catalog)
    capabilityApi.listMcpInstallations.mockReturnValueOnce(initial.promise).mockResolvedValueOnce([installation(2)])
    capabilityApi.listSkillInstallations.mockResolvedValue([])

    render(<CapabilityWorkbenchRuntime workspaceId="ws-1" />)
    await user.click(screen.getByRole('button', { name: '刷新' }))
    expect(await screen.findByText('2')).toBeVisible()

    await act(async () => { initial.resolve([installation(1)]); await Promise.resolve() })

    expect(screen.getByText('2')).toBeVisible()
  })

  it('does not let the initial catalog response overwrite a manual refresh', async () => {
    const user = userEvent.setup()
    const initial = deferred<typeof catalog>()
    const refreshed = { ...catalog, commitSha: 'b'.repeat(40) }
    capabilityApi.listMcpCatalog.mockReturnValue(initial.promise)
    capabilityApi.refreshMcpCatalog.mockResolvedValue(refreshed)
    capabilityApi.listMcpInstallations.mockResolvedValue([])
    capabilityApi.listSkillInstallations.mockResolvedValue([])

    render(<CapabilityWorkbenchRuntime workspaceId="ws-1" />)
    await user.click(screen.getByRole('button', { name: '刷新' }))
    expect(await screen.findByLabelText('目录版本')).toHaveTextContent(refreshed.commitSha)

    await act(async () => { initial.resolve(catalog); await Promise.resolve() })

    expect(screen.getByLabelText('目录版本')).toHaveTextContent(refreshed.commitSha)
  })

  it('does not let an older skill search overwrite a newer result', async () => {
    const user = userEvent.setup()
    const first = deferred<ReturnType<typeof skill>[]>()
    capabilityApi.listMcpCatalog.mockResolvedValue(catalog)
    capabilityApi.listMcpInstallations.mockResolvedValue([])
    capabilityApi.listSkillInstallations.mockResolvedValue([])
    capabilityApi.searchSkills.mockReturnValueOnce(first.promise).mockResolvedValueOnce([skill('second/skill')])

    render(<CapabilityWorkbenchRuntime workspaceId="ws-1" />)
    await user.click(screen.getByRole('button', { name: '搜索 first' }))
    await user.click(screen.getByRole('button', { name: '搜索 second' }))
    expect(await screen.findByLabelText('Skills')).toHaveTextContent('second/skill')

    await act(async () => { first.resolve([skill('first/skill')]); await Promise.resolve() })

    expect(screen.getByLabelText('Skills')).toHaveTextContent('second/skill')
  })

  it('keeps the current catalog when an explicit refresh fails', async () => {
    const user = userEvent.setup()
    const failure = new Error('刷新失败')
    capabilityApi.listMcpCatalog.mockResolvedValue(catalog)
    capabilityApi.refreshMcpCatalog.mockRejectedValue(failure)
    capabilityApi.listMcpInstallations.mockResolvedValue([])
    capabilityApi.listSkillInstallations.mockResolvedValue([])

    render(<CapabilityWorkbenchRuntime workspaceId="ws-1" />)
    expect(await screen.findByLabelText('目录版本')).toHaveTextContent(catalog.commitSha)
    await user.click(screen.getByRole('button', { name: '刷新' }))

    expect(screen.getByLabelText('目录版本')).toHaveTextContent(catalog.commitSha)
    expect(screen.getByLabelText('操作错误')).toHaveTextContent('Error: 刷新失败')
  })

  it('ignores an older skill search failure after a newer search completes', async () => {
    const user = userEvent.setup()
    const first = deferred<ReturnType<typeof skill>[]>()
    capabilityApi.listMcpCatalog.mockResolvedValue(catalog)
    capabilityApi.listMcpInstallations.mockResolvedValue([])
    capabilityApi.listSkillInstallations.mockResolvedValue([])
    capabilityApi.searchSkills.mockReturnValueOnce(first.promise).mockResolvedValueOnce([skill('second/skill')])

    render(<CapabilityWorkbenchRuntime workspaceId="ws-1" />)
    await user.click(screen.getByRole('button', { name: '搜索 first' }))
    await user.click(screen.getByRole('button', { name: '搜索 second' }))
    expect(await screen.findByLabelText('Skills')).toHaveTextContent('second/skill')

    await act(async () => { first.reject(new Error('旧搜索失败')); await Promise.resolve() })

    expect(screen.getByLabelText('操作错误')).toBeEmptyDOMElement()
  })

  it('refreshes the active workspace after an operation started in an older workspace completes', async () => {
    const user = userEvent.setup()
    const started = deferred<void>()
    capabilityApi.listMcpCatalog.mockResolvedValue(catalog)
    capabilityApi.listSkillInstallations.mockResolvedValue([])
    capabilityApi.listMcpInstallations.mockImplementation((workspace: string) => Promise.resolve([installation(workspace === 'ws-1' ? 1 : 2)]))
    capabilityApi.startMcpInstallation.mockReturnValue(started.promise)

    const view = render(<CapabilityWorkbenchRuntime workspaceId="ws-1" />)
    await waitFor(() => expect(screen.getByLabelText('安装版本')).toHaveTextContent('1'))
    await user.click(screen.getByRole('button', { name: '启动' }))
    view.rerender(<CapabilityWorkbenchRuntime workspaceId="ws-2" />)
    await waitFor(() => expect(screen.getByLabelText('安装版本')).toHaveTextContent('2'))

    await act(async () => { started.resolve(); await Promise.resolve() })

    expect(screen.getByLabelText('安装版本')).toHaveTextContent('2')
    expect(capabilityApi.startMcpInstallation).toHaveBeenCalledWith('ws-1', expect.any(Object), { MCP_TOKEN: 'runtime-value' })
  })

  it('does not refresh an older workspace after a catalog refresh completes', async () => {
    const user = userEvent.setup()
    const refreshed = deferred<typeof catalog>()
    capabilityApi.listMcpCatalog.mockResolvedValue(catalog)
    capabilityApi.refreshMcpCatalog.mockReturnValue(refreshed.promise)
    capabilityApi.listSkillInstallations.mockResolvedValue([])
    capabilityApi.listMcpInstallations.mockImplementation((workspace: string) => Promise.resolve([installation(workspace === 'ws-1' ? 1 : 2)]))

    const view = render(<CapabilityWorkbenchRuntime workspaceId="ws-1" />)
    await waitFor(() => expect(screen.getByLabelText('安装版本')).toHaveTextContent('1'))
    capabilityApi.listMcpInstallations.mockClear()
    await user.click(screen.getByRole('button', { name: '刷新' }))
    view.rerender(<CapabilityWorkbenchRuntime workspaceId="ws-2" />)
    await waitFor(() => expect(screen.getByLabelText('安装版本')).toHaveTextContent('2'))

    await act(async () => { refreshed.resolve(catalog); await Promise.resolve() })

    expect(screen.getByLabelText('安装版本')).toHaveTextContent('2')
    expect(capabilityApi.listMcpInstallations).toHaveBeenCalledTimes(1)
  })
})
