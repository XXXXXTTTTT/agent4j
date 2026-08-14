import { describe, expect, it, vi } from 'vitest'

import {
  createRun,
  createCodeAgentRun,
  decodeRunView,
  decodeTerminalFrame,
  decodeTraceFrame,
  decideRun,
  getRunHistory,
} from './runApi'

const RUN_ID = '3ba24ffc-e536-48ab-9bb4-19442c609ebc'
const EVENT_ID = 'c890db6f-322d-42ec-960b-62d5782a6b75'

function waitingRun() {
  return {
    runId: RUN_ID,
    version: 2,
    graphId: 'coder-ops',
    status: 'WAITING_APPROVAL',
    state: {
      messages: [],
      variables: {
        'coder.unifiedDiff': 'diff --git a/src/App.java b/src/App.java\n',
        'ops.command': 'mvn test',
      },
      trace: ['coder'],
    },
    nextNode: 'ops',
    interruptRequest: {
      interruptId: '1a51de42-e150-40cc-93b1-f4c09d58ece4',
      nodeName: 'ops',
      reason: '危险操作需要审批',
      details: { 'ops.command': 'mvn test' },
    },
    approvalDecision: null,
    approvalReason: null,
    error: null,
    createdAt: '2026-08-02T10:15:30Z',
  }
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('Run API 协议解码', () => {
  it('解码精确的 WAITING_APPROVAL RunView', () => {
    const run = decodeRunView(waitingRun())

    expect(run.status).toBe('WAITING_APPROVAL')
    expect(run.interruptRequest?.details).toEqual({ 'ops.command': 'mvn test' })
    expect(run.state.variables['coder.unifiedDiff']).toContain('diff --git')
  })

  it('拒绝状态大小写变化、未知字段和错误嵌套类型', () => {
    expect(() => decodeRunView({ ...waitingRun(), status: 'waiting_approval' })).toThrow(
      'status',
    )
    expect(() => decodeRunView({ ...waitingRun(), runID: RUN_ID })).toThrow('runID')
    expect(() =>
      decodeRunView({
        ...waitingRun(),
        state: { ...waitingRun().state, variables: { 'ops.command': 7 } },
      }),
    ).toThrow('state.variables.ops.command')
  })

  it('解码终端快照、ANSI 日志与 Trace 事件帧', () => {
    const snapshot = decodeTerminalFrame({
      kind: 'SNAPSHOT',
      terminal: {
        runId: RUN_ID,
        checkpointVersion: 2,
        stdout: '',
        stderr: '',
        exitCode: null,
        timedOut: null,
        error: null,
      },
    })
    const log = decodeTerminalFrame({
      kind: 'LOG',
      event: {
        eventId: EVENT_ID,
        runId: RUN_ID,
        nodeName: 'ops',
        sequence: 0,
        stream: 'PTY',
        text: '\u001b[32mok\u001b[0m',
        occurredAt: '2026-08-02T10:15:31Z',
      },
    })
    const trace = decodeTraceFrame({
      kind: 'EVENT',
      event: {
        type: 'NODE_COMPLETED',
        eventId: EVENT_ID,
        runId: RUN_ID,
        checkpointVersion: 3,
        occurredAt: '2026-08-02T10:15:32Z',
        nodeName: 'ops',
        nextNode: '__END__',
      },
    })

    expect(snapshot.kind).toBe('SNAPSHOT')
    expect(log.kind === 'LOG' && log.event.text).toBe('\u001b[32mok\u001b[0m')
    expect(trace.kind === 'EVENT' && trace.event.type).toBe('NODE_COMPLETED')
    expect(() =>
      decodeTerminalFrame({
        kind: 'LOG',
        event: { ...(log.kind === 'LOG' ? log.event : {}), stream: 'pty' },
      }),
    ).toThrow('stream')
  })

  it('解码节点执行中的进度摘要', () => {
    const frame = decodeTraceFrame({
      kind: 'EVENT',
      event: {
        type: 'NODE_PROGRESS',
        eventId: EVENT_ID,
        runId: RUN_ID,
        checkpointVersion: 2,
        occurredAt: '2026-08-06T10:15:31Z',
        nodeName: 'planner',
        summary: '正在识别任务意图',
      },
    })

    expect(frame.kind === 'EVENT' && frame.event.type === 'NODE_PROGRESS'
      ? frame.event.summary
      : null).toBe('正在识别任务意图')
  })

  it('解码父 Run 中的受治理 handoff 元数据', () => {
    const frame = decodeTraceFrame({
      kind: 'EVENT',
      event: {
        type: 'HANDOFF',
        eventId: EVENT_ID,
        runId: RUN_ID,
        checkpointVersion: 4,
        occurredAt: '2026-08-14T08:00:00Z',
        taskId: '8e3ec65b-dcd4-422d-b80c-7c7fc7667b81',
        parentRunId: RUN_ID,
        childRunId: '1786a4d3-dc79-4e59-a688-7e6b7db5848d',
        fromAgent: 'coordinator',
        toAgent: 'researcher',
        lifecycle: 'NODE_PROGRESS',
      },
    })

    expect(frame.kind).toBe('EVENT')
    if (frame.kind === 'EVENT') {
      expect(frame.event.type).toBe('HANDOFF')
      expect(frame.event.type === 'HANDOFF' ? frame.event.childRunId : null).toBe('1786a4d3-dc79-4e59-a688-7e6b7db5848d')
    }
  })
})

describe('Run API HTTP 请求', () => {
  it('通过任务优先路径创建真实 code-agent Run', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({
      ...waitingRun(),
      graphId: 'code-agent',
    }, 202))

    const run = await createCodeAgentRun({
      task: '修复登录超时并运行测试',
      repositoryId: 'repo-1',
      reviewerUrl: 'https://application.test',
    }, fetcher)

    expect(run.graphId).toBe('code-agent')
    expect(fetcher).toHaveBeenCalledWith('/api/runs/code-agent', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        task: '修复登录超时并运行测试',
        repositoryId: 'repo-1',
        reviewerUrl: 'https://application.test',
      }),
    })
  })

  it('使用精确路径和 JSON 创建 Run', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(waitingRun(), 202))

    const run = await createRun(
      'coder-ops',
      { messages: [], variables: {}, trace: [] },
      fetcher,
    )

    expect(run.runId).toBe(RUN_ID)
    expect(fetcher).toHaveBeenCalledOnce()
    expect(fetcher).toHaveBeenCalledWith('/api/runs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        graphId: 'coder-ops',
        initialState: { messages: [], variables: {}, trace: [] },
      }),
    })
  })

  it('读取升序历史并发送精确审批 payload', async () => {
    const completed = {
      ...waitingRun(),
      version: 3,
      status: 'COMPLETED',
      nextNode: null,
      interruptRequest: null,
    }
    const historyFetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse([waitingRun(), completed]))
    const approvalFetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        jsonResponse(
          {
            ...waitingRun(),
            status: 'RUNNING',
            interruptRequest: null,
            approvalDecision: 'APPROVE',
            approvalReason: '已检查',
          },
          202,
        ),
      )

    const history = await getRunHistory(RUN_ID, historyFetcher)
    await decideRun(
      RUN_ID,
      {
        decision: 'APPROVE',
        expectedVersion: 2,
        reason: '已检查',
        variableUpdates: { 'ops.command': 'mvn verify' },
      },
      approvalFetcher,
    )

    expect(history.map((run) => run.version)).toEqual([2, 3])
    expect(historyFetcher).toHaveBeenCalledWith(`/api/runs/${RUN_ID}/history`, {
      method: 'GET',
    })
    expect(approvalFetcher).toHaveBeenCalledWith(`/api/runs/${RUN_ID}/approval`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        decision: 'APPROVE',
        expectedVersion: 2,
        reason: '已检查',
        variableUpdates: { 'ops.command': 'mvn verify' },
      }),
    })
  })
})
