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
  const modelConfiguration: ModelConfigurationSnapshot = {
    providers: [], endpoints: [], groups: [
      { groupId: 'group-terra', ownerUserId: 'user-1', displayName: 'Terra', taskType: 'CODE', endpointIds: [], createdAt: '2026-08-12T00:00:00Z', updatedAt: '2026-08-12T00:00:00Z' },
      { groupId: 'group-sol', ownerUserId: 'user-1', displayName: 'Sol', taskType: 'CODE', endpointIds: [], createdAt: '2026-08-12T00:00:00Z', updatedAt: '2026-08-12T00:00:00Z' },
    ],
  }
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

  it('输入斜杠后优先展示实时 Slash Command，并走受治理分发接口', async () => {
    const user = userEvent.setup()
    const state = conversation()
    const listSlashCommands = vi.fn(async () => ({
      revision: 3,
      commands: [{
        name: 'plan', displayName: '计划', description: '制定计划', aliases: [],
        parameters: [{ name: 'request', description: '请求', required: true }],
        channel: 'WORKFLOW_SKILL' as const, source: 'BUILT_IN' as const, permission: 'OPERATOR' as const,
      }],
    }))
    const dispatchSlashCommand = vi.fn(async () => ({
      status: 'FORWARDED' as const, commandName: 'plan', message: '已提交', data: {},
    }))
    render(<ConversationComposer conversation={{ ...state, listSlashCommands, dispatchSlashCommand }} runController={runController()} />)

    await user.type(screen.getByRole('textbox', { name: '发送消息' }), '/')
    await screen.findByRole('option', { name: /\/plan/ })
    await user.keyboard('{Enter}')
    await user.type(screen.getByRole('textbox', { name: 'Slash Command 参数' }), '修复登录')
    await user.click(screen.getByRole('button', { name: '执行 Slash Command' }))

    await waitFor(() => expect(dispatchSlashCommand).toHaveBeenCalledWith('/plan 修复登录', undefined))
    expect(screen.getByRole('status')).toHaveTextContent('已提交')
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

  it('串行开发模式不渲染角色模型组选择', () => {
    render(<ConversationComposer conversation={conversation()} runController={runController()} />)

    expect(screen.getByRole('combobox', { name: '编排模式' })).toHaveValue('SERIAL_DEVELOPMENT')
    expect(screen.queryByRole('combobox', { name: '协调者模型组' })).not.toBeInTheDocument()
  })

  it('选择并行研究模式和角色模型组后提交精确编排字段', async () => {
    const user = userEvent.setup()
    const state = conversation()
    const runs = runController()
    render(<ConversationComposer conversation={state} runController={runs} />)

    await user.selectOptions(screen.getByRole('combobox', { name: '编排模式' }), 'PARALLEL_RESEARCH')
    for (const name of ['协调者模型组', '研究者模型组', '实施者模型组', '验证者模型组']) {
      expect(screen.getByRole('combobox', { name })).toBeInTheDocument()
      expect(screen.getByRole('combobox', { name })).toHaveTextContent('Terra')
      expect(screen.getByRole('combobox', { name })).toHaveTextContent('Sol')
    }
    expect(screen.getByRole('combobox', { name: '模型组' })).toHaveValue('group-terra')
    await user.selectOptions(screen.getByRole('combobox', { name: '协调者模型组' }), 'group-sol')
    await user.selectOptions(screen.getByRole('combobox', { name: '研究者模型组' }), 'group-terra')
    await user.type(screen.getByRole('textbox', { name: '发送消息' }), '调查项目结构')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(state.submit).toHaveBeenCalledWith('调查项目结构', undefined, 'group-terra', 'PARALLEL_RESEARCH', {
      COORDINATOR: 'group-sol', RESEARCHER: 'group-terra',
    }))
  })

  it('评审闭环自动使用主模型组并提交验证者覆盖', async () => {
    const user = userEvent.setup()
    const state = conversation()
    render(<ConversationComposer conversation={state} runController={runController()} />)

    await user.selectOptions(screen.getByRole('combobox', { name: '编排模式' }), 'REVIEW_LOOP')
    expect(screen.getByRole('combobox', { name: '模型组' })).toHaveValue('group-terra')
    await user.selectOptions(screen.getByRole('combobox', { name: '验证者模型组' }), 'group-sol')
    await user.type(screen.getByRole('textbox', { name: '发送消息' }), '修复校验失败')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(state.submit).toHaveBeenCalledWith('修复校验失败', undefined, 'group-terra', 'REVIEW_LOOP', {
      VERIFIER: 'group-sol',
    }))
  })

  it('没有已加载模型组时禁止提交非串行编排', async () => {
    const user = userEvent.setup()
    const state = conversation()
    render(<ConversationComposer conversation={{ ...state, modelConfiguration: { providers: [], endpoints: [], groups: [] } }} runController={runController()} />)

    await user.selectOptions(screen.getByRole('combobox', { name: '编排模式' }), 'PARALLEL_RESEARCH')
    await user.type(screen.getByRole('textbox', { name: '发送消息' }), '调查项目结构')

    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()
    expect(state.submit).not.toHaveBeenCalled()
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
