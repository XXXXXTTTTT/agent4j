import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { WorkspaceEditorPanel, type WorkspaceEditorSnapshot } from './WorkspaceEditorPanel'

const api = vi.hoisted(() => ({
  readWorkspaceFile: vi.fn(async (_workspaceId: string, path: string) => ({ path, content: 'old', sha256: `sha-${path}`, lastModified: 'now' })),
  writeWorkspaceFile: vi.fn(async (_workspaceId: string, path: string, content: string) => ({ path, content, sha256: `new-${path}`, lastModified: 'now' })),
}))
vi.mock('../api/workspaceFilesApi', () => api)
vi.mock('../monaco/MonacoEditors', () => ({ Editor: ({ value, onChange }: { value: string; onChange(value: string): void }) => <textarea aria-label="文件编辑器" value={value} onChange={(event) => onChange(event.target.value)} /> }))

function createDeferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, reject, resolve }
}

describe('WorkspaceEditorPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.readWorkspaceFile.mockReset().mockImplementation(async (_workspaceId: string, path: string) => ({ path, content: 'old', sha256: `sha-${path}`, lastModified: 'now' }))
    api.writeWorkspaceFile.mockReset().mockImplementation(async (_workspaceId: string, path: string, content: string) => ({ path, content, sha256: `new-${path}`, lastModified: 'now' }))
  })

  it('打开文件标签并在修改后显示未保存标记，保存时携带 SHA', async () => {
    const user = userEvent.setup()
    render(<WorkspaceEditorPanel workspaceId="ws-1" openPaths={['README.md']} activePath="README.md" onActivate={vi.fn()} onClose={vi.fn()} />)
    const editor = await screen.findByRole('textbox', { name: '文件编辑器' })
    expect(screen.getByRole('tab', { name: 'README.md' })).toBeVisible()
    await user.clear(editor)
    await user.type(editor, 'new')
    expect(screen.getByRole('tab', { name: /README\.md.*未保存/ })).toBeVisible()
    await user.click(screen.getByRole('button', { name: '保存文件' }))
    expect(api.writeWorkspaceFile).toHaveBeenCalledWith('ws-1', 'README.md', 'new', 'sha-README.md')
  })

  it('全部关闭遇到未保存文件时先显示确认，并可放弃关闭', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(<WorkspaceEditorPanel workspaceId="ws-1" openPaths={['README.md', 'App.java']} activePath="README.md" onActivate={vi.fn()} onClose={onClose} />)
    const editor = await screen.findByRole('textbox', { name: '文件编辑器' })
    await user.clear(editor)
    await user.type(editor, 'changed')
    await user.click(screen.getByRole('button', { name: '全部关闭' }))
    expect(screen.getByRole('dialog', { name: '未保存文件' })).toBeVisible()
    await user.click(screen.getByRole('button', { name: '放弃并关闭' }))
    expect(onClose).toHaveBeenCalledWith('README.md', true)
    expect(onClose).toHaveBeenCalledWith('App.java', true)
  })

  it('放弃并关闭会丢弃本地草稿，重新打开时重新读取文件', async () => {
    const user = userEvent.setup()
    let snapshot: WorkspaceEditorSnapshot | null = null
    const onClose = vi.fn()
    const view = render(
      <WorkspaceEditorPanel
        workspaceId="ws-1"
        openPaths={['README.md']}
        activePath="README.md"
        onActivate={vi.fn()}
        onClose={onClose}
        onSnapshotChange={(value) => { snapshot = value }}
      />,
    )
    const editor = await screen.findByRole('textbox', { name: '文件编辑器' })
    await user.clear(editor)
    await user.type(editor, 'discard me')
    await user.click(screen.getByRole('button', { name: '关闭 README.md' }))
    await user.click(screen.getByRole('button', { name: '放弃并关闭' }))

    await waitFor(() => expect(snapshot?.drafts['README.md']).toBeUndefined())
    expect(onClose).toHaveBeenCalledWith('README.md', true)

    view.unmount()
    render(
      <WorkspaceEditorPanel
        workspaceId="ws-1"
        openPaths={['README.md']}
        activePath="README.md"
        onActivate={vi.fn()}
        onClose={vi.fn()}
        snapshot={snapshot}
      />,
    )
    expect(await screen.findByDisplayValue('old')).toBeVisible()
  })

  it('工作区切换时对相同路径重新读取内容，不复用旧工作区缓存', async () => {
    api.readWorkspaceFile.mockImplementation(async (workspaceId: string, path: string) => ({ path, content: workspaceId, sha256: `sha-${workspaceId}`, lastModified: 'now' }))
    const view = render(<WorkspaceEditorPanel workspaceId="ws-1" openPaths={['README.md']} activePath="README.md" onActivate={vi.fn()} onClose={vi.fn()} />)
    expect(await screen.findByDisplayValue('ws-1')).toBeVisible()
    view.rerender(<WorkspaceEditorPanel workspaceId="ws-2" openPaths={['README.md']} activePath="README.md" onActivate={vi.fn()} onClose={vi.fn()} />)
    expect(await screen.findByDisplayValue('ws-2')).toBeVisible()
  })

  it('保存全部失败时显示错误并结束保存状态', async () => {
    const user = userEvent.setup()
    api.writeWorkspaceFile.mockRejectedValueOnce(new Error('写入失败'))
    render(<WorkspaceEditorPanel workspaceId="ws-1" openPaths={['README.md']} activePath="README.md" onActivate={vi.fn()} onClose={vi.fn()} />)
    const editor = await screen.findByRole('textbox', { name: '文件编辑器' })
    await user.clear(editor)
    await user.type(editor, 'changed')
    await user.click(screen.getByRole('button', { name: '保存全部' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('写入失败')
    expect(screen.getByRole('button', { name: '保存全部' })).toBeEnabled()
  })

  it('保存全部等待所有请求结束，并保留请求发起后的新草稿', async () => {
    const user = userEvent.setup()
    const failedSave = createDeferred<WorkspaceEditorSnapshot['files'][string]>()
    const successfulSave = createDeferred<WorkspaceEditorSnapshot['files'][string]>()
    const onFileSaved = vi.fn()
    api.writeWorkspaceFile.mockImplementation((_workspaceId: string, path: string) => path === 'README.md'
      ? failedSave.promise
      : successfulSave.promise)
    const commonProps = { workspaceId: 'ws-1', openPaths: ['README.md', 'App.java'], onActivate: vi.fn(), onClose: vi.fn(), onFileSaved }
    const view = render(<WorkspaceEditorPanel {...commonProps} activePath="README.md" />)
    const readmeEditor = await screen.findByRole('textbox', { name: '文件编辑器' })
    await user.clear(readmeEditor)
    await user.type(readmeEditor, 'README draft')
    view.rerender(<WorkspaceEditorPanel {...commonProps} activePath="App.java" />)
    const appEditor = await screen.findByRole('textbox', { name: '文件编辑器' })
    await user.clear(appEditor)
    await user.type(appEditor, 'App draft')

    await user.click(screen.getByRole('button', { name: '保存全部' }))
    await waitFor(() => expect(api.writeWorkspaceFile).toHaveBeenCalledTimes(2))
    await act(async () => {
      failedSave.reject(new Error('README 写入失败'))
      await Promise.resolve()
    })

    expect(screen.getByRole('button', { name: '保存全部' })).toBeDisabled()
    await user.clear(appEditor)
    await user.type(appEditor, 'App newer draft')
    await act(async () => {
      successfulSave.resolve({ path: 'App.java', content: 'App draft', sha256: 'new-App.java', lastModified: 'now' })
      await Promise.resolve()
    })

    expect(await screen.findByRole('alert')).toHaveTextContent('README 写入失败')
    expect(screen.getByRole('button', { name: '保存全部' })).toBeEnabled()
    expect(screen.getByDisplayValue('App newer draft')).toBeVisible()
    expect(screen.getByRole('tab', { name: /App\.java.*未保存/ })).toBeVisible()
    expect(api.writeWorkspaceFile).toHaveBeenCalledWith('ws-1', 'README.md', 'README draft', 'sha-README.md')
    expect(api.writeWorkspaceFile).toHaveBeenCalledWith('ws-1', 'App.java', 'App draft', 'sha-App.java')
    expect(onFileSaved).toHaveBeenCalledWith(
      'ws-1',
      expect.objectContaining({ path: 'App.java', sha256: 'new-App.java' }),
      'App draft',
      'App newer draft',
      0,
    )
  })

  it('重新挂载编辑器面板时从工作台快照恢复未保存草稿', async () => {
    const user = userEvent.setup()
    let snapshot: WorkspaceEditorSnapshot | null = null
    const view = render(<WorkspaceEditorPanel workspaceId="ws-1" openPaths={['README.md']} activePath="README.md" onActivate={vi.fn()} onClose={vi.fn()} onSnapshotChange={(value) => { snapshot = value }} />)
    const editor = await screen.findByRole('textbox', { name: '文件编辑器' })
    await user.clear(editor)
    await user.type(editor, 'draft after panel close')
    view.unmount()
    render(<WorkspaceEditorPanel workspaceId="ws-1" openPaths={['README.md']} activePath="README.md" onActivate={vi.fn()} onClose={vi.fn()} snapshot={snapshot} />)
    expect(await screen.findByDisplayValue('draft after panel close')).toBeVisible()
  })

  it('真实卸载并往返工作区后重新读取相同路径，同时保留未保存草稿', async () => {
    const user = userEvent.setup()
    const snapshots: Record<string, WorkspaceEditorSnapshot> = {}
    let ws1Reads = 0
    api.readWorkspaceFile.mockImplementation(async (workspaceId: string, path: string) => {
      if (workspaceId === 'ws-1') {
        ws1Reads += 1
        return { path, content: `ws-1 server ${ws1Reads}`, sha256: `sha-ws-1-${ws1Reads}`, lastModified: 'now' }
      }
      return { path, content: 'ws-2 server', sha256: 'sha-ws-2', lastModified: 'now' }
    })
    const firstWorkspace = render(<WorkspaceEditorPanel {...({
      workspaceId: 'ws-1', openPaths: ['README.md'], activePath: 'README.md', activationRevision: 1,
      onActivate: vi.fn(), onClose: vi.fn(), onSnapshotChange: (snapshot: WorkspaceEditorSnapshot) => { snapshots['ws-1'] = snapshot },
    } as React.ComponentProps<typeof WorkspaceEditorPanel>)} />)
    const editor = await screen.findByDisplayValue('ws-1 server 1')
    await user.clear(editor)
    await user.type(editor, 'ws-1 local draft')
    await waitFor(() => expect(snapshots['ws-1'].drafts['README.md']).toBe('ws-1 local draft'))
    firstWorkspace.unmount()

    const secondWorkspace = render(<WorkspaceEditorPanel {...({
      workspaceId: 'ws-2', openPaths: ['README.md'], activePath: 'README.md', activationRevision: 1,
      onActivate: vi.fn(), onClose: vi.fn(), onSnapshotChange: (snapshot: WorkspaceEditorSnapshot) => { snapshots['ws-2'] = snapshot },
    } as React.ComponentProps<typeof WorkspaceEditorPanel>)} />)
    expect(await screen.findByDisplayValue('ws-2 server')).toBeVisible()
    secondWorkspace.unmount()

    render(<WorkspaceEditorPanel {...({
      workspaceId: 'ws-1', openPaths: ['README.md'], activePath: 'README.md', activationRevision: 2,
      onActivate: vi.fn(), onClose: vi.fn(), snapshot: snapshots['ws-1'],
      onSnapshotChange: (snapshot: WorkspaceEditorSnapshot) => { snapshots['ws-1'] = snapshot },
    } as React.ComponentProps<typeof WorkspaceEditorPanel>)} />)

    await waitFor(() => expect(ws1Reads).toBe(2))
    expect(screen.getByDisplayValue('ws-1 local draft')).toBeVisible()
    expect(screen.getByRole('tab', { name: /README\.md.*未保存/ })).toBeVisible()
  })

  it('保存中卸载后直接更新工作台快照，重新保存使用新 SHA', async () => {
    const user = userEvent.setup()
    const pendingSave = createDeferred<WorkspaceEditorSnapshot['files'][string]>()
    let snapshot: WorkspaceEditorSnapshot | null = null
    const mergeSavedFile = (workspaceId: string, value: WorkspaceEditorSnapshot['files'][string], savedDraft: string) => {
      expect(workspaceId).toBe('ws-1')
      const current = snapshot
      const currentDraft = current?.drafts[value.path]
      snapshot = {
        files: { ...(current?.files ?? {}), [value.path]: value },
        drafts: { ...(current?.drafts ?? {}), [value.path]: currentDraft === undefined || currentDraft === savedDraft ? value.content : currentDraft },
        loadedRevisions: { ...(current?.loadedRevisions ?? {}), [value.path]: 1 },
      }
    }
    api.writeWorkspaceFile.mockImplementationOnce(() => pendingSave.promise)
      .mockImplementationOnce(async (_workspaceId: string, path: string, content: string) => ({ path, content, sha256: 'sha-third', lastModified: 'now' }))
    const firstMount = render(<WorkspaceEditorPanel {...({
      workspaceId: 'ws-1', openPaths: ['README.md'], activePath: 'README.md', activationRevision: 1,
      onActivate: vi.fn(), onClose: vi.fn(), onSnapshotChange: (value: WorkspaceEditorSnapshot) => { snapshot = value },
      onFileSaved: mergeSavedFile,
    } as React.ComponentProps<typeof WorkspaceEditorPanel>)} />)
    const editor = await screen.findByRole('textbox', { name: '文件编辑器' })
    await user.clear(editor)
    await user.type(editor, 'first saved draft')
    await waitFor(() => expect(snapshot?.drafts['README.md']).toBe('first saved draft'))
    await user.click(screen.getByRole('button', { name: '保存文件' }))
    firstMount.unmount()

    await act(async () => {
      pendingSave.resolve({ path: 'README.md', content: 'first saved draft', sha256: 'sha-second', lastModified: 'now' })
      await Promise.resolve()
    })

    const secondMount = render(<WorkspaceEditorPanel {...({
      workspaceId: 'ws-1', openPaths: ['README.md'], activePath: 'README.md', activationRevision: 1,
      onActivate: vi.fn(), onClose: vi.fn(), snapshot, onSnapshotChange: (value: WorkspaceEditorSnapshot) => { snapshot = value },
      onFileSaved: mergeSavedFile,
    } as React.ComponentProps<typeof WorkspaceEditorPanel>)} />)
    const restoredEditor = await screen.findByDisplayValue('first saved draft')
    await user.clear(restoredEditor)
    await user.type(restoredEditor, 'second saved draft')
    await user.click(screen.getByRole('button', { name: '保存文件' }))

    expect(api.writeWorkspaceFile).toHaveBeenLastCalledWith('ws-1', 'README.md', 'second saved draft', 'sha-second')
    secondMount.unmount()
  })

  it('工作区切换后忽略旧读取结果和快照回传', async () => {
    const oldRead = createDeferred<WorkspaceEditorSnapshot['files'][string]>()
    const snapshots: WorkspaceEditorSnapshot[] = []
    api.readWorkspaceFile.mockImplementation((workspaceId: string, path: string) => workspaceId === 'ws-1'
      ? oldRead.promise
      : Promise.resolve({ path, content: 'ws-2', sha256: 'sha-ws-2', lastModified: 'now' }))
    const view = render(<WorkspaceEditorPanel workspaceId="ws-1" openPaths={['README.md']} activePath="README.md" onActivate={vi.fn()} onClose={vi.fn()} onSnapshotChange={(snapshot) => snapshots.push(snapshot)} />)

    expect(await screen.findByText('正在读取 README.md')).toBeVisible()
    view.rerender(<WorkspaceEditorPanel workspaceId="ws-2" openPaths={['README.md']} activePath="README.md" onActivate={vi.fn()} onClose={vi.fn()} onSnapshotChange={(snapshot) => snapshots.push(snapshot)} />)
    expect(await screen.findByDisplayValue('ws-2')).toBeVisible()
    const snapshotCount = snapshots.length

    await act(async () => {
      oldRead.resolve({ path: 'README.md', content: 'stale ws-1', sha256: 'sha-ws-1', lastModified: 'now' })
      await Promise.resolve()
    })

    expect(screen.getByDisplayValue('ws-2')).toBeVisible()
    expect(snapshots).toHaveLength(snapshotCount)
  })

  it('工作区切换后忽略旧读取失败', async () => {
    const oldRead = createDeferred<WorkspaceEditorSnapshot['files'][string]>()
    api.readWorkspaceFile.mockImplementation((workspaceId: string, path: string) => workspaceId === 'ws-1'
      ? oldRead.promise
      : Promise.resolve({ path, content: 'ws-2', sha256: 'sha-ws-2', lastModified: 'now' }))
    const view = render(<WorkspaceEditorPanel workspaceId="ws-1" openPaths={['README.md']} activePath="README.md" onActivate={vi.fn()} onClose={vi.fn()} />)
    expect(await screen.findByText('正在读取 README.md')).toBeVisible()
    view.rerender(<WorkspaceEditorPanel workspaceId="ws-2" openPaths={['README.md']} activePath="README.md" onActivate={vi.fn()} onClose={vi.fn()} />)
    expect(await screen.findByDisplayValue('ws-2')).toBeVisible()

    await act(async () => {
      oldRead.reject(new Error('旧工作区读取失败'))
      await Promise.resolve()
    })

    expect(screen.queryByText('旧工作区读取失败')).not.toBeInTheDocument()
    expect(screen.getByDisplayValue('ws-2')).toBeVisible()
  })

  it('工作区切换后忽略旧保存结果，且不结束当前保存状态', async () => {
    const user = userEvent.setup()
    const oldSave = createDeferred<WorkspaceEditorSnapshot['files'][string]>()
    const currentSave = createDeferred<WorkspaceEditorSnapshot['files'][string]>()
    const snapshots: WorkspaceEditorSnapshot[] = []
    api.readWorkspaceFile.mockImplementation(async (workspaceId: string, path: string) => ({ path, content: workspaceId, sha256: `sha-${workspaceId}`, lastModified: 'now' }))
    api.writeWorkspaceFile.mockImplementationOnce(() => oldSave.promise).mockImplementationOnce(() => currentSave.promise)
    const view = render(<WorkspaceEditorPanel workspaceId="ws-1" openPaths={['README.md']} activePath="README.md" onActivate={vi.fn()} onClose={vi.fn()} onSnapshotChange={(snapshot) => snapshots.push(snapshot)} />)
    const ws1Editor = await screen.findByRole('textbox', { name: '文件编辑器' })
    await user.clear(ws1Editor)
    await user.type(ws1Editor, 'ws-1 draft')
    await user.click(screen.getByRole('button', { name: '保存文件' }))

    view.rerender(<WorkspaceEditorPanel workspaceId="ws-2" openPaths={['README.md']} activePath="README.md" onActivate={vi.fn()} onClose={vi.fn()} onSnapshotChange={(snapshot) => snapshots.push(snapshot)} />)
    const ws2Editor = await screen.findByDisplayValue('ws-2')
    await user.clear(ws2Editor)
    await user.type(ws2Editor, 'ws-2 draft')
    await user.click(screen.getByRole('button', { name: '保存文件' }))
    expect(screen.getByRole('button', { name: '保存文件' })).toBeDisabled()
    const snapshotCount = snapshots.length

    await act(async () => {
      oldSave.resolve({ path: 'README.md', content: 'saved ws-1', sha256: 'sha-saved-ws-1', lastModified: 'now' })
      await Promise.resolve()
    })

    expect(screen.getByDisplayValue('ws-2 draft')).toBeVisible()
    expect(screen.getByRole('button', { name: '保存文件' })).toBeDisabled()
    expect(snapshots).toHaveLength(snapshotCount)

    await act(async () => {
      currentSave.resolve({ path: 'README.md', content: 'ws-2 draft', sha256: 'sha-saved-ws-2', lastModified: 'now' })
      await Promise.resolve()
    })
  })

  it('工作区切换后忽略旧保存失败，且不结束当前保存状态', async () => {
    const user = userEvent.setup()
    const oldSave = createDeferred<WorkspaceEditorSnapshot['files'][string]>()
    const currentSave = createDeferred<WorkspaceEditorSnapshot['files'][string]>()
    api.readWorkspaceFile.mockImplementation(async (workspaceId: string, path: string) => ({ path, content: workspaceId, sha256: `sha-${workspaceId}`, lastModified: 'now' }))
    api.writeWorkspaceFile.mockImplementationOnce(() => oldSave.promise).mockImplementationOnce(() => currentSave.promise)
    const view = render(<WorkspaceEditorPanel workspaceId="ws-1" openPaths={['README.md']} activePath="README.md" onActivate={vi.fn()} onClose={vi.fn()} />)
    const ws1Editor = await screen.findByDisplayValue('ws-1')
    await user.clear(ws1Editor)
    await user.type(ws1Editor, 'ws-1 draft')
    await user.click(screen.getByRole('button', { name: '保存文件' }))

    view.rerender(<WorkspaceEditorPanel workspaceId="ws-2" openPaths={['README.md']} activePath="README.md" onActivate={vi.fn()} onClose={vi.fn()} />)
    const ws2Editor = await screen.findByDisplayValue('ws-2')
    await user.clear(ws2Editor)
    await user.type(ws2Editor, 'ws-2 draft')
    await user.click(screen.getByRole('button', { name: '保存文件' }))
    expect(screen.getByRole('button', { name: '保存文件' })).toBeDisabled()

    await act(async () => {
      oldSave.reject(new Error('旧工作区保存失败'))
      await Promise.resolve()
    })

    expect(screen.queryByText('旧工作区保存失败')).not.toBeInTheDocument()
    expect(screen.getByDisplayValue('ws-2 draft')).toBeVisible()
    expect(screen.getByRole('button', { name: '保存文件' })).toBeDisabled()

    await act(async () => {
      currentSave.resolve({ path: 'README.md', content: 'ws-2 draft', sha256: 'sha-saved-ws-2', lastModified: 'now' })
      await Promise.resolve()
    })
  })

  it('工作区往返后重新打开相同路径时重新读取，并保留未保存草稿', async () => {
    const user = userEvent.setup()
    const snapshots: Record<string, WorkspaceEditorSnapshot> = {}
    let ws1Reads = 0
    api.readWorkspaceFile.mockImplementation(async (workspaceId: string, path: string) => {
      if (workspaceId === 'ws-1') {
        ws1Reads += 1
        return { path, content: ws1Reads === 1 ? 'ws-1 initial' : 'ws-1 refreshed', sha256: `sha-ws-1-${ws1Reads}`, lastModified: 'now' }
      }
      return { path, content: 'ws-2', sha256: 'sha-ws-2', lastModified: 'now' }
    })
    const onSnapshotChange = (workspaceId: string) => (snapshot: WorkspaceEditorSnapshot) => { snapshots[workspaceId] = snapshot }
    const view = render(<WorkspaceEditorPanel workspaceId="ws-1" openPaths={['README.md']} activePath="README.md" onActivate={vi.fn()} onClose={vi.fn()} onSnapshotChange={onSnapshotChange('ws-1')} />)
    const editor = await screen.findByDisplayValue('ws-1 initial')
    await user.clear(editor)
    await user.type(editor, 'ws-1 local draft')
    await waitFor(() => expect(snapshots['ws-1'].drafts['README.md']).toBe('ws-1 local draft'))

    view.rerender(<WorkspaceEditorPanel workspaceId="ws-2" openPaths={[]} activePath={null} onActivate={vi.fn()} onClose={vi.fn()} onSnapshotChange={onSnapshotChange('ws-2')} />)
    await screen.findByText('从项目文件中打开一个文本文件')
    view.rerender(<WorkspaceEditorPanel workspaceId="ws-1" openPaths={[]} activePath={null} onActivate={vi.fn()} onClose={vi.fn()} snapshot={snapshots['ws-1']} onSnapshotChange={onSnapshotChange('ws-1')} />)
    await screen.findByText('从项目文件中打开一个文本文件')
    view.rerender(<WorkspaceEditorPanel workspaceId="ws-1" openPaths={['README.md']} activePath="README.md" onActivate={vi.fn()} onClose={vi.fn()} snapshot={snapshots['ws-1']} onSnapshotChange={onSnapshotChange('ws-1')} />)

    expect(await screen.findByDisplayValue('ws-1 local draft')).toBeVisible()
    await waitFor(() => expect(api.readWorkspaceFile).toHaveBeenCalledWith('ws-1', 'README.md'))
    expect(ws1Reads).toBe(2)
    expect(screen.getByRole('tab', { name: /README\.md.*未保存/ })).toBeVisible()
  })
})
