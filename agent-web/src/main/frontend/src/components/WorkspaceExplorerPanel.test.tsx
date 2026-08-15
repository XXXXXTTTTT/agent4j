import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { WorkspaceExplorerPanel } from './WorkspaceExplorerPanel'

const api = vi.hoisted(() => ({
  listWorkspaceFiles: vi.fn(async () => [{ name: 'src', path: 'src', kind: 'DIRECTORY' as const, size: 0, lastModified: 'now' }, { name: 'README.md', path: 'README.md', kind: 'FILE' as const, size: 4, lastModified: 'now' }]),
  readWorkspaceFile: vi.fn(async () => ({ path: 'README.md', content: 'old', sha256: 'sha-1', lastModified: 'now' })),
  writeWorkspaceFile: vi.fn(async () => ({ path: 'README.md', content: 'new', sha256: 'sha-2', lastModified: 'now' })),
}))
vi.mock('../api/workspaceFilesApi', () => api)
vi.mock('../monaco/MonacoEditors', () => ({ Editor: ({ value, onChange }: { value: string; onChange(value: string): void }) => <textarea aria-label="文件编辑器" value={value} onChange={(event) => onChange(event.target.value)} /> }))

describe('WorkspaceExplorerPanel', () => {
  beforeEach(() => vi.clearAllMocks())

  it('loads a project tree, opens a file and navigates it with ArrowDown', async () => {
    const user = userEvent.setup()
    render(<WorkspaceExplorerPanel workspaceId="ws-1" />)
    const readme = await screen.findByRole('treeitem', { name: /README\.md/ })
    expect(screen.getByRole('tree', { name: '项目文件' })).toBeVisible()
    await user.click(readme)
    expect(await screen.findByDisplayValue('old')).toBeVisible()
    await user.click(screen.getByRole('treeitem', { name: /src/ }))
    await user.keyboard('{ArrowDown}')
    expect(document.activeElement).toHaveAttribute('data-path', 'README.md')
  })

  it('saves edited content with the loaded SHA', async () => {
    const user = userEvent.setup()
    render(<WorkspaceExplorerPanel workspaceId="ws-1" />)
    await user.click(await screen.findByRole('treeitem', { name: /README\.md/ }))
    const editor = await screen.findByLabelText('文件编辑器')
    await user.clear(editor)
    await user.type(editor, 'new')
    await user.click(screen.getByRole('button', { name: '保存文件' }))
    expect(api.writeWorkspaceFile).toHaveBeenCalledWith('ws-1', 'README.md', 'new', 'sha-1')
  })
})
