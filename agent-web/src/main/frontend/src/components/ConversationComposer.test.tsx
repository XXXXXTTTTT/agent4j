import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import type { Conversation, ConversationTurn, ModelConfigurationSnapshot, Workspace } from '../api/contracts'
import type { UseConversationWorkspaceResult } from '../hooks/useConversationWorkspace'
import type { UseRunWorkbenchResult } from '../hooks/useRunWorkbench'
import { ConversationComposer } from './ConversationComposer'

function conversation(): UseConversationWorkspaceResult {
  const workspace: Workspace = {
    workspaceId: 'ws-1', ownerUserId: 'user-1', displayName: 'Agent4J', workspacePath: 'D:/agent4j',
    repositoryId: 'agent4j', permission: 'OWNER', createdAt: '2026-08-12T00:00:00Z', updatedAt: '2026-08-12T00:00:00Z',
  }
  const activeConversation: Conversation = {
    conversationId: 'conv-1', workspaceId: 'ws-1', createdBy: 'user-1', title: '测试', status: 'ACTIVE',
    createdAt: '2026-08-12T00:00:00Z', updatedAt: '2026-08-12T00:00:00Z',
  }
  const modelConfiguration: ModelConfigurationSnapshot = { providers: [], endpoints: [], groups: [] }
  return {
    identity: null, workspaces: [workspace], activeWorkspace: workspace, conversations: [activeConversation],
    activeConversation, turns: [], searchQuery: '', includeArchived: false, loading: false, submitting: false,
    error: null, modelConfiguration, selectWorkspace: vi.fn(async () => undefined),
    createWorkspace: vi.fn(async () => undefined), browseWorkspaceDirectories: vi.fn(async (path: string) => ({ currentPath: path, parentPath: null, entries: [] })),
    importWorkspace: vi.fn(async () => undefined), selectConversation: vi.fn(async () => undefined),
    search: vi.fn(async () => undefined), toggleArchived: vi.fn(async () => undefined),
    createConversation: vi.fn(async () => undefined), archive: vi.fn(async () => undefined),
    deleteConversation: vi.fn(async () => undefined), reload: vi.fn(async () => undefined),
    reloadModelConfiguration: vi.fn(async () => undefined),
    submit: vi.fn(async (): Promise<ConversationTurn> => ({
      turnId: 'turn-1', conversationId: 'conv-1', turnIndex: 1, userContent: '普通消息', assistantContent: null,
      runId: 'chat-run', status: 'PENDING', error: null, createdAt: '2026-08-12T00:00:00Z', completedAt: null,
    })),
    updateModelProvider: vi.fn(async () => modelConfiguration), updateModelEndpoint: vi.fn(async () => modelConfiguration),
    updateModelGroup: vi.fn(async () => modelConfiguration), deleteModelProvider: vi.fn(async () => modelConfiguration),
    deleteModelEndpoint: vi.fn(async () => modelConfiguration), deleteModelGroup: vi.fn(async () => modelConfiguration),
  }
}

function runController(): UseRunWorkbenchResult {
  return {
    run: null, history: [], traceEvents: [], connectionState: { trace: null, terminal: null }, error: null,
    start: vi.fn(async () => undefined), startTask: vi.fn(async () => undefined), followRun: vi.fn(async () => undefined),
    clearRun: vi.fn(), reload: vi.fn(async () => undefined), decide: vi.fn(async () => undefined),
  }
}

function runView() {
  return {
    runId: 'cli-run', version: 0, graphId: 'governed-cli', status: 'RUNNING',
    state: { messages: [], variables: {}, trace: [] }, nextNode: 'ops', interruptRequest: null,
    approvalDecision: null, approvalReason: null, error: null, createdAt: '2026-08-12T00:00:00Z',
  }
}

describe('ConversationComposer', () => {
  it('输入斜杠后选择当前工作区命令，展示风险并提交结构化 Run', async () => {
    const user = userEvent.setup()
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify([{
        name: 'test.maven', executable: 'mvn', fixedArguments: ['test'], riskLevel: 'MUTATING',
        requiredCapabilities: ['TERMINAL'], maxArguments: 64,
      }, {
        name: 'destroy.workspace', executable: 'rm', fixedArguments: ['-rf'], riskLevel: 'DESTRUCTIVE',
        requiredCapabilities: ['TERMINAL'], maxArguments: 64,
      }]), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(runView()), { status: 202 }))
    vi.stubGlobal('fetch', fetcher)
    const runs = runController()
    render(<ConversationComposer conversation={conversation()} runController={runs} />)

    await user.type(screen.getByRole('textbox', { name: '发送消息' }), '/')
    await screen.findByRole('option', { name: /test\.maven/ })
    expect(screen.queryByRole('option', { name: /destroy\.workspace/ })).not.toBeInTheDocument()
    await user.keyboard('{Enter}')
    expect(screen.getByText('执行前需要审批')).toBeVisible()
    await user.type(screen.getByRole('textbox', { name: '命令参数' }), '-q{enter}-DskipTests')
    await user.click(screen.getByRole('button', { name: '执行命令' }))

    await waitFor(() => expect(runs.followRun).toHaveBeenCalledWith('cli-run'))
    expect(fetcher).toHaveBeenLastCalledWith('/api/workspaces/ws-1/cli/runs', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ commandName: 'test.maven', arguments: ['-q', '-DskipTests'], timeoutSeconds: 30 }),
    })
    vi.unstubAllGlobals()
  })

  it('普通消息仍提交持久化会话', async () => {
    const user = userEvent.setup()
    const workspace = conversation()
    const runs = runController()
    render(<ConversationComposer conversation={workspace} runController={runs} />)

    await user.type(screen.getByRole('textbox', { name: '发送消息' }), '继续说明')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    expect(workspace.submit).toHaveBeenCalledWith('继续说明')
    expect(runs.followRun).toHaveBeenCalledWith('chat-run')
  })

  it('没有已选会话时仍允许从工作区执行受治理命令', async () => {
    const user = userEvent.setup()
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify([{
        name: 'test.maven', executable: 'mvn', fixedArguments: ['test'], riskLevel: 'READ_ONLY',
        requiredCapabilities: ['TERMINAL'], maxArguments: 64,
      }]), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(runView()), { status: 202 }))
    vi.stubGlobal('fetch', fetcher)
    const state = conversation()
    const runs = runController()
    render(<ConversationComposer conversation={{ ...state, activeConversation: null }} runController={runs} />)

    const input = screen.getByRole('textbox', { name: '发送消息' })
    expect(input).toBeEnabled()
    await user.type(input, '/')
    await screen.findByRole('option', { name: /test\.maven/ })
    await user.keyboard('{Enter}')
    await user.click(screen.getByRole('button', { name: '执行命令' }))

    await waitFor(() => expect(runs.followRun).toHaveBeenCalledWith('cli-run'))
    vi.unstubAllGlobals()
  })
})
