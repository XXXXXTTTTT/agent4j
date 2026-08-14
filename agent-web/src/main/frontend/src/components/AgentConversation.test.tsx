import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import type { RunView, TraceEvent } from '../api/contracts'
import { AgentConversation } from './AgentConversation'

const run: RunView = {
  runId: 'parent-run', version: 4, graphId: 'code-agent', status: 'RUNNING',
  state: { messages: [], variables: { 'planner.task': '调查项目结构' }, trace: ['planner'] },
  nextNode: 'coder', interruptRequest: null, approvalDecision: null, approvalReason: null,
  error: null, createdAt: '2026-08-14T08:00:00Z',
}

const handoff: TraceEvent = {
  type: 'HANDOFF', eventId: 'event-1', runId: 'parent-run', checkpointVersion: 0,
  occurredAt: '2026-08-14T08:00:01Z', taskId: 'task-1', parentRunId: 'parent-run',
  childRunId: 'child-run', fromAgent: 'coordinator', toAgent: 'researcher', lifecycle: 'STARTED',
}

describe('AgentConversation handoff cards', () => {
  it('shows governed child Agent metadata without hidden reasoning', () => {
    render(<AgentConversation run={run} currentNode="planner" traceEvents={[handoff]} />)

    expect(screen.getByText('子 Agent')).toBeVisible()
    expect(screen.getByText('coordinator → researcher')).toBeVisible()
    expect(screen.getByText('STARTED')).toBeVisible()
    expect(screen.queryByText(/调查项目结构/)).toBeVisible()
    expect(screen.queryByText(/思考|推理|reasoning/i)).not.toBeInTheDocument()
  })
})
