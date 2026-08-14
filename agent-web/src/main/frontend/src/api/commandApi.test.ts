import { describe, expect, it, vi } from 'vitest'

import { dispatchSlashCommand, listSlashCommands } from './commandApi'

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const CATALOG = {
  revision: 3,
  commands: [{
    name: 'plan', displayName: '计划', description: '制定计划', aliases: ['roadmap'],
    parameters: [{ name: 'request', description: '请求', required: true }],
    channel: 'WORKFLOW_SKILL', source: 'GLOBAL', permission: 'OPERATOR',
  }],
}

describe('Slash Command API', () => {
  it('decodes the live registry with exact fields', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(response(CATALOG))

    await expect(listSlashCommands('ws-1', fetcher)).resolves.toEqual(CATALOG)
    expect(fetcher).toHaveBeenCalledWith('/api/workspaces/ws-1/commands', { method: 'GET' })
  })

  it('sends conversation context and optional model group exactly once', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(response({
      status: 'FORWARDED', commandName: 'plan', message: '已提交', data: {},
    }, 202))

    await expect(dispatchSlashCommand('ws-1', {
      input: '/plan fix login', conversationId: 'conv-1', modelGroupId: 'group-terra',
    }, fetcher)).resolves.toMatchObject({ status: 'FORWARDED' })
    expect(fetcher).toHaveBeenCalledWith('/api/workspaces/ws-1/commands', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ input: '/plan fix login', conversationId: 'conv-1', modelGroupId: 'group-terra' }),
    })
  })

  it('rejects unknown response fields and invalid command IDs', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(response({ ...CATALOG, extra: true }))
    await expect(listSlashCommands('ws-1', fetcher)).rejects.toThrow('extra')
    await expect(listSlashCommands(' ', fetcher)).rejects.toThrow('不能为空')
  })
})
