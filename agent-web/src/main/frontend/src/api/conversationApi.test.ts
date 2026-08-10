import { describe, expect, it, vi } from 'vitest'

import {
  createConversation,
  createWorkspace,
  decodeActor,
  decodeConversation,
  decodeConversationTurn,
  decodeWorkspace,
  getIdentity,
  listConversations,
  listConversationTurns,
  listWorkspaces,
  searchConversations,
  submitConversationTurn,
} from './conversationApi'

const ACTOR = { userId: 'user-1', displayName: 'Alice' }
const WORKSPACE = {
  workspaceId: 'ws-1',
  ownerUserId: 'user-1',
  displayName: 'Agent4J',
  workspacePath: 'D:/agent4j',
  repositoryId: 'agent4j',
  permission: 'OWNER',
  createdAt: '2026-08-07T01:00:00Z',
  updatedAt: '2026-08-07T01:00:00Z',
}
const CONVERSATION = {
  conversationId: 'conv-1',
  workspaceId: 'ws-1',
  createdBy: 'user-1',
  title: '模型咨询',
  status: 'ACTIVE',
  createdAt: '2026-08-07T01:00:00Z',
  updatedAt: '2026-08-07T01:00:00Z',
}
const TURN = {
  turnId: 'turn-1',
  conversationId: 'conv-1',
  turnIndex: 0,
  userContent: '你是什么模型',
  assistantContent: '我是 OpenAI 的 AI 语言模型。',
  runId: 'run-1',
  status: 'COMPLETED',
  error: null,
  createdAt: '2026-08-07T01:00:00Z',
  completedAt: '2026-08-07T01:00:05Z',
}

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('Conversation API 协议解码', () => {
  it('解码身份、工作区、会话和轮次精确字段', () => {
    expect(decodeActor(ACTOR)).toEqual(ACTOR)
    expect(decodeWorkspace(WORKSPACE).permission).toBe('OWNER')
    expect(decodeConversation(CONVERSATION).status).toBe('ACTIVE')
    expect(decodeConversationTurn(TURN).assistantContent).toContain('OpenAI')
  })

  it('拒绝大小写变化、未知字段和非法可空字段', () => {
    expect(() => decodeActor({ ...ACTOR, userID: ACTOR.userId })).toThrow('userID')
    expect(() => decodeWorkspace({ ...WORKSPACE, permission: 'owner' })).toThrow('permission')
    expect(() => decodeConversation({ ...CONVERSATION, extra: true })).toThrow('extra')
    expect(() => decodeConversationTurn({ ...TURN, error: 7 })).toThrow('error')
  })
})

describe('Conversation API HTTP 请求', () => {
  it('加载身份和工作区', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(response(ACTOR))
      .mockResolvedValueOnce(response([WORKSPACE]))

    await expect(getIdentity(fetcher)).resolves.toEqual(ACTOR)
    await expect(listWorkspaces(fetcher)).resolves.toHaveLength(1)
    expect(fetcher).toHaveBeenNthCalledWith(1, '/api/identity', { method: 'GET' })
    expect(fetcher).toHaveBeenNthCalledWith(2, '/api/workspaces', { method: 'GET' })
  })

  it('按工作区读取、搜索、创建会话并提交轮次', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(response([CONVERSATION]))
      .mockResolvedValueOnce(response([CONVERSATION]))
      .mockResolvedValueOnce(response(CONVERSATION, 201))
      .mockResolvedValueOnce(response(TURN, 202))
      .mockResolvedValueOnce(response([TURN]))

    await expect(listConversations('ws-1', fetcher)).resolves.toHaveLength(1)
    await expect(searchConversations('ws-1', '模型', fetcher)).resolves.toHaveLength(1)
    await createConversation('ws-1', fetcher)
    await submitConversationTurn('conv-1', { content: '继续说明', reviewerUrl: 'https://test' }, fetcher)
    await expect(listConversationTurns('conv-1', fetcher)).resolves.toHaveLength(1)

    expect(fetcher).toHaveBeenNthCalledWith(1, '/api/workspaces/ws-1/conversations', { method: 'GET' })
    expect(fetcher).toHaveBeenNthCalledWith(2, '/api/workspaces/ws-1/conversations?query=%E6%A8%A1%E5%9E%8B', { method: 'GET' })
    expect(fetcher).toHaveBeenNthCalledWith(3, '/api/workspaces/ws-1/conversations', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
    })
    expect(fetcher).toHaveBeenNthCalledWith(4, '/api/conversations/conv-1/turns', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: '继续说明', reviewerUrl: 'https://test' }),
    })
  })

  it('工作区创建失败时保留 ProblemDetail 的 detail', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(response({
      type: 'about:blank',
      title: 'Bad Request',
      status: 400,
      detail: 'workspacePath 必须位于配置工作区内',
      instance: '/api/workspaces',
    }, 400))

    await expect(createWorkspace({
      displayName: 'Outside', workspacePath: '/outside', repositoryId: 'outside',
    }, fetcher)).rejects.toMatchObject({
      message: 'workspacePath 必须位于配置工作区内',
      status: 400,
    })
  })
})
