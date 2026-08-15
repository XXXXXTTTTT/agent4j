import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { WorkspaceDialog } from './WorkspaceDialog'

describe('WorkspaceDialog', () => {
  it('创建空项目调用项目接口并在成功后关闭', async () => {
    const user = userEvent.setup()
    const createProject = vi.fn(async () => undefined)
    const onClose = vi.fn()
    render(<WorkspaceDialog createWorkspace={async () => undefined} createProject={createProject} onClose={onClose} />)

    await user.click(screen.getByRole('tab', { name: '新建空项目' }))
    await user.type(screen.getByLabelText('工作区名称'), 'Blank')
    await user.type(screen.getByLabelText('项目目录名'), 'blank-project')
    await user.type(screen.getByLabelText('仓库标识'), 'blank')
    await user.click(screen.getByRole('button', { name: '创建空项目' }))

    expect(createProject).toHaveBeenCalledWith({ displayName: 'Blank', directoryName: 'blank-project', repositoryId: 'blank' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('提交精确工作区字段并关闭对话框', async () => {
    const user = userEvent.setup()
    const createWorkspace = vi.fn(async () => undefined)
    const onClose = vi.fn()
    render(<WorkspaceDialog createWorkspace={createWorkspace} onClose={onClose} />)

    await user.type(screen.getByLabelText('工作区名称'), 'Sandbox')
    await user.type(screen.getByLabelText('工作区路径'), '/agent-workspace/sandbox')
    await user.type(screen.getByLabelText('仓库标识'), 'sandbox')
    await user.click(screen.getByRole('button', { name: '创建工作区' }))

    expect(createWorkspace).toHaveBeenCalledWith({
      displayName: 'Sandbox',
      workspacePath: '/agent-workspace/sandbox',
      repositoryId: 'sandbox',
    })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('将服务端错误留在对话框内', async () => {
    const user = userEvent.setup()
    const createWorkspace = vi.fn(async () => {
      throw new Error('workspacePath 必须位于配置工作区内')
    })
    render(<WorkspaceDialog createWorkspace={createWorkspace} onClose={() => undefined} />)

    await user.type(screen.getByLabelText('工作区名称'), 'Sandbox')
    await user.type(screen.getByLabelText('工作区路径'), '/outside')
    await user.type(screen.getByLabelText('仓库标识'), 'sandbox')
    await user.click(screen.getByRole('button', { name: '创建工作区' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('workspacePath 必须位于配置工作区内')
    expect(screen.getByRole('dialog', { name: '新建工作区' })).toBeVisible()
  })

  it('浏览挂载目录并将选中目录用于创建工作区', async () => {
    const user = userEvent.setup()
    const createWorkspace = vi.fn(async () => undefined)
    const browse = vi.fn(async (path: string) => path === '/agent-workspace'
      ? { currentPath: path, parentPath: null, entries: [{ name: 'demo', path: '/agent-workspace/demo' }] }
      : { currentPath: path, parentPath: '/agent-workspace', entries: [] })
    render(<WorkspaceDialog createWorkspace={createWorkspace} browseWorkspaceDirectories={browse} onClose={() => undefined} />)

    await user.click(await screen.findByRole('button', { name: /demo/ }))
    expect(screen.getByLabelText('工作区路径')).toHaveValue('/agent-workspace/demo')
  })

  it('展示本地文件数量和字节数并提交导入', async () => {
    const user = userEvent.setup()
    const importWorkspace = vi.fn(async () => undefined)
    const file = new File(['1234'], 'App.java', { type: 'text/plain' })
    Object.defineProperty(file, 'webkitRelativePath', { value: 'demo/App.java' })
    render(<WorkspaceDialog createWorkspace={async () => undefined} importWorkspace={importWorkspace} onClose={() => undefined} />)

    await user.click(screen.getByRole('tab', { name: '导入本地文件夹' }))
    await user.type(screen.getByLabelText('工作区名称'), 'Demo')
    await user.upload(screen.getByLabelText('本地项目文件夹'), file)
    await user.type(screen.getByLabelText('仓库标识'), 'demo')
    expect(screen.getByText('已选择 1 个文件，共 4 字节')).toBeVisible()
    await user.click(screen.getByRole('button', { name: '导入并创建' }))

    expect(importWorkspace).toHaveBeenCalledWith({ displayName: 'Demo', repositoryId: 'demo', files: [file] })
  })

  it('在桌面桥存在时选择归档并通过桌面导入链提交', async () => {
    const user = userEvent.setup()
    const selectProjectArchive = vi.fn(async () => ({
      archive: new Uint8Array([80, 75, 3, 4]), fileCount: 2, totalBytes: 42, suggestedDisplayName: 'demo',
    }))
    const importDesktopWorkspace = vi.fn(async () => undefined)
    window.agent4jDesktop = { selectProjectArchive }
    render(<WorkspaceDialog
      createWorkspace={async () => undefined}
      importDesktopWorkspace={importDesktopWorkspace}
      onClose={() => undefined}
    />)

    await user.click(screen.getByRole('tab', { name: '导入本地文件夹' }))
    await user.type(screen.getByLabelText('工作区名称'), 'Demo')
    await user.click(screen.getByRole('button', { name: '选择本地项目文件夹' }))
    await user.type(screen.getByLabelText('仓库标识'), 'demo')
    expect(screen.getByText('已选择 2 个文件，共 42 字节')).toBeVisible()
    await user.click(screen.getByRole('button', { name: '导入并创建' }))

    expect(importDesktopWorkspace).toHaveBeenCalledWith({
      displayName: 'Demo', repositoryId: 'demo', archive: new Uint8Array([80, 75, 3, 4]),
    })
    expect(JSON.stringify(importDesktopWorkspace.mock.calls)).not.toContain('C:\\')
    delete window.agent4jDesktop
  })

  it('桌面目录选择取消时不提交导入', async () => {
    const user = userEvent.setup()
    const selectProjectArchive = vi.fn(async () => null)
    const importDesktopWorkspace = vi.fn(async () => undefined)
    window.agent4jDesktop = { selectProjectArchive }
    render(<WorkspaceDialog createWorkspace={async () => undefined} importDesktopWorkspace={importDesktopWorkspace} onClose={() => undefined} />)

    await user.click(screen.getByRole('tab', { name: '导入本地文件夹' }))
    await user.click(screen.getByRole('button', { name: '选择本地项目文件夹' }))
    expect(importDesktopWorkspace).not.toHaveBeenCalled()
    delete window.agent4jDesktop
  })
})
