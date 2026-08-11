import { describe, expect, it, vi } from 'vitest'

import {
  createConversation,
  createWorkspace,
  browseWorkspaceDirectories,
  decodeActor,
  decodeConversation,
  decodeConversationTurn,
  decodeWorkspace,
  getIdentity,
  importWorkspace,
  listConversations,
  listConversationTurns,
  listWorkspaces,
  searchConversations,
  submitConversationTurn,
  updateModelProvider,
  updateModelEndpoint,
  updateModelGroup,
  deleteModelProvider,
  deleteModelEndpoint,
  deleteModelGroup,
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

function noContentResponse(): Response {
  return new Response(null, { status: 204 })
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

  it('按精确路径浏览挂载目录', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(response({
      currentPath: '/agent-workspace',
      parentPath: null,
      entries: [{ name: 'demo', path: '/agent-workspace/demo' }],
    }))

    await expect(browseWorkspaceDirectories('/agent-workspace', fetcher)).resolves.toEqual({
      currentPath: '/agent-workspace',
      parentPath: null,
      entries: [{ name: 'demo', path: '/agent-workspace/demo' }],
    })
    expect(fetcher).toHaveBeenCalledWith('/api/workspace-directories?path=%2Fagent-workspace', { method: 'GET' })
  })

  it('将本地文件夹打包为精确 multipart 字段', async () => {
    const file = new File(['class App {}'], 'App.java', { type: 'text/plain' })
    Object.defineProperty(file, 'webkitRelativePath', { value: 'demo/src/App.java' })
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async (_url, init) => {
      const form = init?.body as FormData
      expect(form.get('displayName')).toBe('Demo')
      expect(form.get('repositoryId')).toBe('demo')
      expect(form.get('archive')).toBeInstanceOf(Blob)
      return response(WORKSPACE, 201)
    })

    await expect(importWorkspace({ displayName: 'Demo', repositoryId: 'demo', files: [file] }, fetcher)).resolves.toEqual(WORKSPACE)
    expect(fetcher).toHaveBeenCalledWith('/api/workspace-imports', expect.objectContaining({ method: 'POST' }))
  })
})

describe('模型配置命令', () => {
  it('精确更新 Provider，并根据 apiKey 是否非空决定请求字段', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(response({}))
      .mockResolvedValueOnce(response({ providers: [], endpoints: [], groups: [] }))
      .mockResolvedValueOnce(response({}))
      .mockResolvedValueOnce(response({ providers: [], endpoints: [], groups: [] }))

    await updateModelProvider('provider/a', {
      displayName: '网关', baseUrl: 'https://example.com', chatCompletionsPath: '/chat', apiKey: '  ',
    }, fetcher)
    await updateModelProvider('provider/a', {
      displayName: '网关', baseUrl: 'https://example.com', chatCompletionsPath: '/chat', apiKey: ' sk-new ',
    }, fetcher)

    expect(fetcher).toHaveBeenNthCalledWith(1, '/api/model-config/providers/provider%2Fa', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ displayName: '网关', baseUrl: 'https://example.com', chatCompletionsPath: '/chat' }),
    })
    expect(fetcher).toHaveBeenNthCalledWith(3, '/api/model-config/providers/provider%2Fa', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ displayName: '网关', baseUrl: 'https://example.com', chatCompletionsPath: '/chat', apiKey: 'sk-new' }),
    })
  })

  it('精确更新端点和模型组，并在成功后读取权威快照', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(response({}))
      .mockResolvedValueOnce(response({ providers: [], endpoints: [], groups: [] }))
      .mockResolvedValueOnce(response({}))
      .mockResolvedValueOnce(response({ providers: [], endpoints: [], groups: [] }))

    await updateModelEndpoint('endpoint/1', { displayName: '端点', modelId: 'model', capabilities: ['CHAT_COMPLETIONS'], priority: 0, weight: 1, enabled: true }, fetcher)
    await updateModelGroup('group/1', { displayName: '模型组', taskType: 'CODE', endpointIds: ['endpoint-1'] }, fetcher)

    expect(fetcher).toHaveBeenNthCalledWith(1, '/api/model-config/endpoints/endpoint%2F1', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ displayName: '端点', modelId: 'model', capabilities: ['CHAT_COMPLETIONS'], priority: 0, weight: 1, enabled: true }),
    })
    expect(fetcher).toHaveBeenNthCalledWith(3, '/api/model-config/groups/group%2F1', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ displayName: '模型组', taskType: 'CODE', endpointIds: ['endpoint-1'] }),
    })
  })

  it('删除 204 后不读取响应正文，并读取权威快照', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(noContentResponse())
      .mockResolvedValueOnce(response({ providers: [], endpoints: [], groups: [] }))
      .mockResolvedValueOnce(noContentResponse())
      .mockResolvedValueOnce(response({ providers: [], endpoints: [], groups: [] }))
      .mockResolvedValueOnce(noContentResponse())
      .mockResolvedValueOnce(response({ providers: [], endpoints: [], groups: [] }))

    await deleteModelProvider('provider-1', fetcher)
    await deleteModelEndpoint('endpoint-1', fetcher)
    await deleteModelGroup('group-1', fetcher)

    expect(fetcher).toHaveBeenNthCalledWith(1, '/api/model-config/providers/provider-1', { method: 'DELETE' })
    expect(fetcher).toHaveBeenNthCalledWith(3, '/api/model-config/endpoints/endpoint-1', { method: 'DELETE' })
    expect(fetcher).toHaveBeenNthCalledWith(5, '/api/model-config/groups/group-1', { method: 'DELETE' })
  })

  it('保留 409 ProblemDetail 原文并拒绝空 ID', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(response({ detail: '端点仍被模型组引用' }, 409))

    await expect(deleteModelEndpoint('endpoint-1', fetcher)).rejects.toMatchObject({ message: '端点仍被模型组引用', status: 409 })
    await expect(updateModelProvider(' ', { displayName: '网关', baseUrl: 'https://example.com', chatCompletionsPath: '/chat' }, fetcher)).rejects.toThrow('不能为空')
    await expect(updateModelEndpoint('', { displayName: '端点', modelId: 'model', capabilities: [], priority: 0, weight: 1, enabled: true }, fetcher)).rejects.toThrow('不能为空')
    await expect(updateModelGroup('\t', { displayName: '组', taskType: 'CODE', endpointIds: [] }, fetcher)).rejects.toThrow('不能为空')
  })
})
