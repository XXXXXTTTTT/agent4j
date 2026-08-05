import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import type { UseRunWorkbenchResult } from '../hooks/useRunWorkbench'
import { RunLauncher } from './RunLauncher'

function controller(startTask = vi.fn(async () => undefined)): UseRunWorkbenchResult {
  return {
    run: null,
    history: [],
    traceEvents: [],
    connectionState: { trace: null, terminal: null },
    error: null,
    start: vi.fn(async () => undefined),
    startTask,
    reload: vi.fn(async () => undefined),
    decide: vi.fn(async () => undefined),
  }
}

describe('RunLauncher', () => {
  it('submits a natural-language task to the production code agent without raw JSON', async () => {
    const user = userEvent.setup()
    const startTask = vi.fn(async () => undefined)
    render(<RunLauncher controller={controller(startTask)} />)

    const task = screen.getByLabelText('任务描述')
    await user.clear(task)
    await user.type(task, '修复登录超时')
    await user.click(screen.getByRole('button', { name: '运行 Agent' }))

    expect(startTask).toHaveBeenCalledWith('修复登录超时')
    expect(screen.queryByLabelText('初始状态 JSON')).not.toBeInTheDocument()
  })
})
