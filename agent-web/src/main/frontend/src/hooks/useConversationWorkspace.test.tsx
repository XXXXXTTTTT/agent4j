import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import type { Actor, Conversation, ConversationTurn, Workspace } from '../api/contracts'
import { useConversationWorkspace } from './useConversationWorkspace'

const actor: Actor = { userId: 'user-1', displayName: 'Alice' }
const workspace: Workspace = {
  workspaceId: 'ws-1', ownerUserId: 'user-1', displayName: 'Agent4J', workspacePath: 'D:/agent4j',
  repositoryId: 'agent4j', permission: 'OWNER', createdAt: '2026-08-07T01:00:00Z', updatedAt: '2026-08-07T01:00:00Z',
}
const conversation: Conversation = {
  conversationId: 'conv-1', workspaceId: 'ws-1', createdBy: 'user-1', title: '模型咨询', status: 'ACTIVE',
  createdAt: '2026-08-07T01:00:00Z', updatedAt: '2026-08-07T01:00:00Z',
}
const turn: ConversationTurn = {
  turnId: 'turn-1', conversationId: 'conv-1', turnIndex: 0, userContent: '你是什么模型',
  assistantContent: '我是 AI。', runId: 'run-1', status: 'COMPLETED', error: null,
  createdAt: '2026-08-07T01:00:00Z', completedAt: '2026-08-07T01:00:05Z',
}

describe('useConversationWorkspace', () => {
  it('初始化身份、工作区和 URL 指定会话的服务端轮次', async () => {
    const api = {
      getIdentity: vi.fn(async () => actor),
      listWorkspaces: vi.fn(async () => [workspace]),
      listConversations: vi.fn(async () => [conversation]),
      listConversationTurns: vi.fn(async () => [turn]),
      searchConversations: vi.fn(async () => [conversation]),
      createConversation: vi.fn(async () => conversation),
      submitConversationTurn: vi.fn(),
      archiveConversation: vi.fn(),
    }
    window.history.replaceState({}, '', '/?conversationId=conv-1')
    const { result } = renderHook(() => useConversationWorkspace({ api }))

    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.identity).toEqual(actor)
    expect(result.current.activeWorkspace?.workspaceId).toBe('ws-1')
    expect(result.current.activeConversation?.conversationId).toBe('conv-1')
    expect(result.current.turns).toEqual([turn])
    expect(api.listConversationTurns).toHaveBeenCalledWith('conv-1')
  })

  it('创建会话、提交轮次并把服务端返回的 turn 设为权威状态', async () => {
    const pending: ConversationTurn = { ...turn, status: 'PENDING', assistantContent: null, completedAt: null }
    const api = {
      getIdentity: vi.fn(async () => actor), listWorkspaces: vi.fn(async () => [workspace]),
      listConversations: vi.fn(async () => []), listConversationTurns: vi.fn(async () => []),
      searchConversations: vi.fn(async () => []), createConversation: vi.fn(async () => conversation),
      submitConversationTurn: vi.fn(async () => pending), archiveConversation: vi.fn(),
    }
    const { result } = renderHook(() => useConversationWorkspace({ api }))
    await waitFor(() => expect(result.current.loading).toBe(false))

    await act(() => result.current.createConversation())
    expect(result.current.activeConversation).toEqual(conversation)
    await act(() => result.current.submit('继续说明'))
    expect(api.submitConversationTurn).toHaveBeenCalledWith('conv-1', { content: '继续说明' })
    expect(result.current.turns).toEqual([pending])
  })
})
