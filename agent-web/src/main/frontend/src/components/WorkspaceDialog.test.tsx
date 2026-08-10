import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { WorkspaceDialog } from './WorkspaceDialog'

describe('WorkspaceDialog', () => {
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
})
