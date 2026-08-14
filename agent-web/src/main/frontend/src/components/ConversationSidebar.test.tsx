import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import type { Conversation, ModelConfigurationSnapshot, Workspace } from '../api/contracts'
import type { UseConversationWorkspaceResult } from '../hooks/useConversationWorkspace'
import { AppearanceProvider } from '../appearance/AppearanceProvider'
import { ConversationSidebar } from './ConversationSidebar'

function controller(overrides: Partial<UseConversationWorkspaceResult> = {}): UseConversationWorkspaceResult {
  const modelConfiguration: ModelConfigurationSnapshot = { providers: [], endpoints: [], groups: [] }
  const workspace: Workspace = {
    workspaceId: 'ws-1', ownerUserId: 'user-1', displayName: 'Agent4J', workspacePath: 'D:/agent4j', repositoryId: 'agent4j', permission: 'OWNER', createdAt: '2026-08-07T01:00:00Z', updatedAt: '2026-08-07T01:00:00Z',
  }
  const conversation: Conversation = {
    conversationId: 'conv-1', workspaceId: 'ws-1', createdBy: 'user-1', title: '模型咨询', status: 'ACTIVE', createdAt: '2026-08-07T01:00:00Z', updatedAt: '2026-08-07T01:00:00Z',
  }
  return {
    identity: { userId: 'user-1', displayName: 'Alice' }, workspaces: [workspace], activeWorkspace: workspace,
    conversations: [conversation], activeConversation: conversation, turns: [], searchQuery: '', includeArchived: false,
    loading: false, submitting: false, error: null, modelConfiguration,
    selectWorkspace: vi.fn(async () => undefined), selectConversation: vi.fn(async () => undefined), search: vi.fn(async () => undefined), toggleArchived: vi.fn(async () => undefined), createConversation: vi.fn(async () => undefined), createWorkspace: vi.fn(async () => undefined), browseWorkspaceDirectories: vi.fn(async (path: string) => ({ currentPath: path, parentPath: null, entries: [] })), importWorkspace: vi.fn(async () => undefined), importDesktopWorkspace: vi.fn(async () => undefined), submit: vi.fn(async () => { throw new Error('测试不提交会话') }), archive: vi.fn(async () => undefined), deleteConversation: vi.fn(async () => undefined), reload: vi.fn(async () => undefined), reloadModelConfiguration: vi.fn(async () => undefined),
    createModelProvider: vi.fn(async () => modelConfiguration), createModelEndpoint: vi.fn(async () => modelConfiguration), createModelGroup: vi.fn(async () => modelConfiguration), updateModelProvider: vi.fn(async () => modelConfiguration), updateModelEndpoint: vi.fn(async () => modelConfiguration), updateModelGroup: vi.fn(async () => modelConfiguration), deleteModelProvider: vi.fn(async () => modelConfiguration), deleteModelEndpoint: vi.fn(async () => modelConfiguration), deleteModelGroup: vi.fn(async () => modelConfiguration),
    ...overrides,
  }
}

describe('ConversationSidebar', () => {
  it('展示服务端身份、运行通道状态并转发文件夹导入回调', async () => {
    const user = userEvent.setup()
    const importWorkspace = vi.fn(async () => undefined)
    render(<AppearanceProvider><ConversationSidebar controller={controller({ importWorkspace })} connectionState={{ trace: null, terminal: null }} /></AppearanceProvider>)

    expect(screen.getByText('本地身份')).toBeVisible()
    expect(screen.getByText('Alice')).toBeVisible()
    expect(screen.getByText('user-1')).toBeVisible()
    expect(screen.getByText('运行通道未连接')).toBeVisible()

    await user.click(screen.getByRole('button', { name: '外观设置' }))
    expect(screen.getByRole('dialog', { name: '外观设置' })).toBeVisible()
    await user.click(screen.getByRole('button', { name: '关闭外观设置' }))

    await user.click(screen.getByRole('button', { name: '新建工作区' }))
    await user.click(screen.getByRole('tab', { name: '导入本地文件夹' }))
    await user.type(screen.getByLabelText('工作区名称'), 'Import')
    await user.upload(screen.getByLabelText('本地项目文件夹'), new File(['class App {}'], 'App.java', { type: 'text/plain' }))
    await user.type(screen.getByLabelText('仓库标识'), 'import-repo')
    await user.click(screen.getByRole('button', { name: '导入并创建' }))

    expect(importWorkspace).toHaveBeenCalledTimes(1)
  })
})
