import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import type { UseRunWorkbenchResult } from '../hooks/useRunWorkbench'
import { RunLauncher } from './RunLauncher'

function controller(start = vi.fn(async () => undefined)): UseRunWorkbenchResult {
  return {
    run: null,
    history: [],
    traceEvents: [],
    connectionState: { trace: null, terminal: null },
    error: null,
    start,
    reload: vi.fn(async () => undefined),
    decide: vi.fn(async () => undefined),
  }
}

describe('RunLauncher', () => {
  it('submits a natural-language task to the demo agent without raw JSON', async () => {
    const user = userEvent.setup()
    const start = vi.fn(async () => undefined)
    render(<RunLauncher controller={controller(start)} />)

    const task = screen.getByLabelText('任务描述')
    await user.clear(task)
    await user.type(task, '修复登录超时')
    await user.click(screen.getByRole('button', { name: '运行 Agent' }))

    expect(start).toHaveBeenCalledWith('demo-agent', {
      messages: [],
      variables: {
        'demo.task': '修复登录超时',
        'demo.workspace': '当前工作区',
      },
      trace: [],
    })
    expect(screen.queryByLabelText('初始状态 JSON')).not.toBeInTheDocument()
  })
})
