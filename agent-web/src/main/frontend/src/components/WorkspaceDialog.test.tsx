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
})
