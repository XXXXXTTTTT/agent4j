import { act, render, screen, waitFor } from '@testing-library/react'
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

function deferred<T>() {
  let resolve: (value: T) => void = () => undefined
  let reject: (reason?: unknown) => void = () => undefined
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, reject, resolve }
}

describe('WorkspaceExplorerPanel', () => {
  beforeEach(() => vi.clearAllMocks())

  it('loads a project tree, requests center editor opening and navigates it with ArrowDown', async () => {
    const user = userEvent.setup()
    const onOpenFile = vi.fn()
    api.listWorkspaceFiles.mockImplementation(async (_workspaceId: string, path = '') => path === ''
      ? [{ name: 'src', path: 'src', kind: 'DIRECTORY' as const, size: 0, lastModified: 'now' }, { name: 'README.md', path: 'README.md', kind: 'FILE' as const, size: 4, lastModified: 'now' }]
      : [])
    render(<WorkspaceExplorerPanel workspaceId="ws-1" onOpenFile={onOpenFile} />)
    const readme = await screen.findByRole('treeitem', { name: /README\.md/ })
    expect(screen.getByRole('tree', { name: '项目文件' })).toBeVisible()
    await user.click(readme)
    expect(onOpenFile).toHaveBeenCalledWith('README.md')
    expect(screen.queryByLabelText('文件编辑器')).not.toBeInTheDocument()
    await user.click(screen.getByRole('treeitem', { name: /src/ }))
    await user.keyboard('{ArrowDown}')
    expect(document.activeElement).toHaveAttribute('data-path', 'README.md')
  })

  it('按目录路径懒加载并展开折叠目录，维护 aria-expanded', async () => {
    const user = userEvent.setup()
    api.listWorkspaceFiles.mockImplementation(async (_workspaceId: string, path = '') => path === ''
      ? [{ name: 'src', path: 'src', kind: 'DIRECTORY' as const, size: 0, lastModified: 'now' }]
      : [{ name: 'main.java', path: 'src/main.java', kind: 'FILE' as const, size: 12, lastModified: 'now' }])
    render(<WorkspaceExplorerPanel workspaceId="ws-1" onOpenFile={vi.fn()} />)

    const src = await screen.findByRole('treeitem', { name: /src/ })
    expect(src).toHaveAttribute('aria-expanded', 'false')
    await user.click(src)
    expect(await screen.findByRole('treeitem', { name: /main\.java/ })).toBeVisible()
    expect(src).toHaveAttribute('aria-expanded', 'true')
    expect(api.listWorkspaceFiles).toHaveBeenCalledWith('ws-1', 'src')

    await user.click(src)
    expect(screen.queryByRole('treeitem', { name: /main\.java/ })).not.toBeInTheDocument()
    expect(src).toHaveAttribute('aria-expanded', 'false')
  })

  it('使用键盘右箭头展开、左箭头折叠目录并将焦点移入子项', async () => {
    const user = userEvent.setup()
    api.listWorkspaceFiles.mockImplementation(async (_workspaceId: string, path = '') => path === ''
      ? [{ name: 'src', path: 'src', kind: 'DIRECTORY' as const, size: 0, lastModified: 'now' }]
      : [{ name: 'main.java', path: 'src/main.java', kind: 'FILE' as const, size: 12, lastModified: 'now' }])
    render(<WorkspaceExplorerPanel workspaceId="ws-1" onOpenFile={vi.fn()} />)
    const src = await screen.findByRole('treeitem', { name: /src/ })
    src.focus()
    await user.keyboard('{ArrowRight}')
    const child = await screen.findByRole('treeitem', { name: /main\.java/ })
    expect(src).toHaveAttribute('aria-expanded', 'true')
    await vi.waitFor(() => expect(child).toHaveFocus())
    await user.keyboard('{ArrowLeft}')
    expect(src).toHaveAttribute('aria-expanded', 'false')
    expect(src).toHaveFocus()
  })

  it('切换工作区后忽略旧工作区的延迟目录响应', async () => {
    let resolveFirstWorkspace: ((entries: Array<{ name: string; path: string; kind: 'FILE'; size: number; lastModified: string }>) => void) | undefined
    const firstWorkspace = new Promise<Array<{ name: string; path: string; kind: 'FILE'; size: number; lastModified: string }>>((resolve) => {
      resolveFirstWorkspace = resolve
    })
    api.listWorkspaceFiles.mockImplementation(async (workspaceId: string) => {
      if (workspaceId === 'ws-1') return firstWorkspace
      return [{ name: 'current.md', path: 'current.md', kind: 'FILE' as const, size: 8, lastModified: 'now' }]
    })
    const { rerender } = render(<WorkspaceExplorerPanel workspaceId="ws-1" onOpenFile={vi.fn()} />)

    await waitFor(() => expect(api.listWorkspaceFiles).toHaveBeenCalledWith('ws-1', ''))
    rerender(<WorkspaceExplorerPanel workspaceId="ws-2" onOpenFile={vi.fn()} />)
    expect(await screen.findByRole('treeitem', { name: /current\.md/ })).toBeVisible()

    await act(async () => resolveFirstWorkspace?.([{ name: 'stale.md', path: 'stale.md', kind: 'FILE', size: 5, lastModified: 'now' }]))
    expect(screen.queryByRole('treeitem', { name: /stale\.md/ })).not.toBeInTheDocument()
    expect(screen.getByRole('treeitem', { name: /current\.md/ })).toBeVisible()
  })

  it('切换工作区后忽略旧工作区延迟请求的错误', async () => {
    const firstWorkspace = deferred<Array<{ name: string; path: string; kind: 'FILE'; size: number; lastModified: string }>>()
    api.listWorkspaceFiles.mockImplementation(async (workspaceId: string) => {
      if (workspaceId === 'ws-1') return firstWorkspace.promise
      return [{ name: 'current.md', path: 'current.md', kind: 'FILE' as const, size: 8, lastModified: 'now' }]
    })
    const { rerender } = render(<WorkspaceExplorerPanel workspaceId="ws-1" onOpenFile={vi.fn()} />)

    await waitFor(() => expect(api.listWorkspaceFiles).toHaveBeenCalledWith('ws-1', ''))
    rerender(<WorkspaceExplorerPanel workspaceId="ws-2" onOpenFile={vi.fn()} />)
    expect(await screen.findByRole('treeitem', { name: /current\.md/ })).toBeVisible()

    await act(async () => firstWorkspace.reject(new Error('旧工作区读取失败')))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('treeitem', { name: /current\.md/ })).toBeVisible()
  })

  it('旧工作区请求结束时保持新工作区的加载提示', async () => {
    const firstWorkspace = deferred<Array<{ name: string; path: string; kind: 'FILE'; size: number; lastModified: string }>>()
    const secondWorkspace = deferred<Array<{ name: string; path: string; kind: 'FILE'; size: number; lastModified: string }>>()
    api.listWorkspaceFiles.mockImplementation(async (workspaceId: string) => workspaceId === 'ws-1'
      ? firstWorkspace.promise
      : secondWorkspace.promise)
    const { rerender } = render(<WorkspaceExplorerPanel workspaceId="ws-1" onOpenFile={vi.fn()} />)

    await waitFor(() => expect(api.listWorkspaceFiles).toHaveBeenCalledWith('ws-1', ''))
    rerender(<WorkspaceExplorerPanel workspaceId="ws-2" onOpenFile={vi.fn()} />)
    await waitFor(() => expect(api.listWorkspaceFiles).toHaveBeenCalledWith('ws-2', ''))
    expect(screen.getByText('正在读取项目文件')).toBeVisible()

    await act(async () => firstWorkspace.resolve([{ name: 'stale.md', path: 'stale.md', kind: 'FILE', size: 5, lastModified: 'now' }]))
    expect(screen.getByText('正在读取项目文件')).toBeVisible()

    await act(async () => secondWorkspace.resolve([{ name: 'current.md', path: 'current.md', kind: 'FILE', size: 8, lastModified: 'now' }]))
    expect(await screen.findByRole('treeitem', { name: /current\.md/ })).toBeVisible()
    expect(screen.queryByText('正在读取项目文件')).not.toBeInTheDocument()
  })
})
