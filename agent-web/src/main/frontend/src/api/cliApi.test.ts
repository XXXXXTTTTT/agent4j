import { describe, expect, it, vi } from 'vitest'

import { createGovernedCliRun, listGovernedCliCommands } from './cliApi'

const RUN_ID = '3ba24ffc-e536-48ab-9bb4-19442c609ebc'

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function runView() {
  return {
    runId: RUN_ID,
    version: 0,
    graphId: 'governed-cli',
    status: 'RUNNING',
    state: { messages: [], variables: {}, trace: [] },
    nextNode: 'ops',
    interruptRequest: null,
    approvalDecision: null,
    approvalReason: null,
    error: null,
    createdAt: '2026-08-12T03:00:00Z',
  }
}

describe('受治理 CLI API', () => {
  it('读取精确命令目录字段并拒绝额外字段', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response([{
      name: 'test.maven',
      executable: 'mvn',
      fixedArguments: ['test'],
      riskLevel: 'READ_ONLY',
      requiredCapabilities: ['TERMINAL'],
      maxArguments: 64,
    }]))

    const commands = await listGovernedCliCommands('ws-1', fetcher)

    expect(commands).toEqual([{
      name: 'test.maven',
      executable: 'mvn',
      fixedArguments: ['test'],
      riskLevel: 'READ_ONLY',
      requiredCapabilities: ['TERMINAL'],
      maxArguments: 64,
    }])
    expect(fetcher).toHaveBeenCalledWith('/api/workspaces/ws-1/cli/commands', { method: 'GET' })
  })

  it('以结构化参数提交 CLI Run', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response(runView(), 202))

    const run = await createGovernedCliRun('ws-1', {
      commandName: 'test.maven',
      arguments: ['-q', '-DskipTests'],
      timeoutSeconds: 30,
    }, fetcher)

    expect(run.runId).toBe(RUN_ID)
    expect(fetcher).toHaveBeenCalledWith('/api/workspaces/ws-1/cli/runs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        commandName: 'test.maven',
        arguments: ['-q', '-DskipTests'],
        timeoutSeconds: 30,
      }),
    })
  })
})
