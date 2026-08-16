import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { ChatTimelineMinimap, type ChatTimelineEntry } from './ChatTimelineMinimap'

const entries: ChatTimelineEntry[] = [
  { id: 'turn-1', label: '第 1 轮', summary: '创建项目', role: 'user', status: 'success' },
  { id: 'turn-2', label: '第 2 轮', summary: '运行测试失败', role: 'agent', status: 'failed' },
]

describe('ChatTimelineMinimap', () => {
  it('渲染角色和执行状态刻度，并显示摘要提示', () => {
    render(<ChatTimelineMinimap messages={entries} scrollProgress={0.25} activeMessageId="turn-2" onSelectTurn={vi.fn()} />)

    expect(screen.getByRole('complementary', { name: '对话时间轴' })).toBeVisible()
    expect(screen.getByRole('button', { name: /第 1 轮.*创建项目/ })).toHaveAttribute('data-role', 'user')
    expect(screen.getByRole('button', { name: /第 2 轮.*运行测试失败/ })).toHaveAttribute('data-status', 'failed')
    expect(screen.getByRole('button', { name: /第 2 轮/ })).toHaveClass('is-active')
    expect(screen.getByRole('button', { name: /第 2 轮/ })).toHaveAttribute('title', '第 2 轮：运行测试失败')
    expect(screen.getByTestId('timeline-viewport-cursor')).toHaveStyle({ top: 'calc(25% - 7px)' })
  })

  it('点击刻度选择对应消息，轨道指针拖拽更新进度', () => {
    const onSelectTurn = vi.fn()
    const onProgressChange = vi.fn()
    render(<ChatTimelineMinimap messages={entries} scrollProgress={0} onSelectTurn={onSelectTurn} onProgressChange={onProgressChange} />)

    fireEvent.click(screen.getByRole('button', { name: /第 1 轮/ }))
    expect(onSelectTurn).toHaveBeenCalledWith('turn-1')
    const rail = screen.getByTestId('timeline-tick-rail')
    Object.defineProperty(rail, 'getBoundingClientRect', { value: () => ({ top: 100, height: 200, bottom: 300 }) })
    fireEvent.pointerDown(rail, { clientY: 200, pointerId: 1 })
    fireEvent.pointerMove(window, { clientY: 250, pointerId: 1 })
    fireEvent.pointerUp(window, { clientY: 250, pointerId: 1 })
    expect(onProgressChange).toHaveBeenCalledWith(0.75)
  })
})
