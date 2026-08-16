import { act, render, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import { useChatScrollSync } from './useChatScrollSync'
import type { ChatScrollSyncResult } from './useChatScrollSync'

function installRaf(): () => void {
  const callbacks: FrameRequestCallback[] = []
  const spy = vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
    callbacks.push(callback)
    return callbacks.length
  })
  return () => {
    const pending = callbacks.splice(0)
    pending.forEach((callback) => callback(0))
    spy.mockRestore()
  }
}

function Harness({ onReady }: { onReady(result: ChatScrollSyncResult): void }): ReactNode {
  const result = useChatScrollSync()
  onReady(result)
  return <div ref={result.containerRef} data-testid="scroll-container" />
}

describe('useChatScrollSync', () => {
  it('根据滚动范围计算进度并通过 rAF 节流更新', () => {
    const flush = installRaf()
    let result!: ChatScrollSyncResult
    render(<Harness onReady={(value) => { result = value }} />)
    const container = document.querySelector('[data-testid="scroll-container"]') as HTMLDivElement
    Object.defineProperties(container, {
      scrollTop: { configurable: true, writable: true, value: 250 },
      scrollHeight: { configurable: true, value: 1000 },
      clientHeight: { configurable: true, value: 500 },
    })
    act(() => {
      container.dispatchEvent(new Event('scroll'))
    })
    expect(result.scrollProgress).toBe(0)
    act(flush)
    expect(result.scrollProgress).toBe(0.5)
  })

  it('选择视口中线最近的消息，并支持按 id 平滑定位', () => {
    const flush = installRaf()
    let result!: ChatScrollSyncResult
    render(<Harness onReady={(value) => { result = value }} />)
    const container = document.querySelector('[data-testid="scroll-container"]') as HTMLDivElement
    Object.defineProperties(container, {
      scrollTop: { configurable: true, writable: true, value: 0 },
      scrollHeight: { configurable: true, value: 600 },
      clientHeight: { configurable: true, value: 300 },
    })
    const first = document.createElement('article')
    first.dataset.messageId = 'first'
    first.getBoundingClientRect = () => ({ top: 0, bottom: 100, left: 0, right: 10, width: 10, height: 100, x: 0, y: 0, toJSON: () => ({}) })
    const second = document.createElement('article')
    second.dataset.messageId = 'second'
    second.getBoundingClientRect = () => ({ top: 120, bottom: 260, left: 0, right: 10, width: 10, height: 140, x: 0, y: 120, toJSON: () => ({}) })
    container.append(first, second)
    container.getBoundingClientRect = () => ({ top: 0, bottom: 300, left: 0, right: 10, width: 10, height: 300, x: 0, y: 0, toJSON: () => ({}) })
    act(() => {
      container.dispatchEvent(new Event('scroll'))
    })
    act(flush)
    expect(result.activeMessageId).toBe('second')
    const scrollIntoView = vi.fn()
    first.scrollIntoView = scrollIntoView
    act(() => result.scrollToMessage('first'))
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' })
  })

  it('ResizeObserver 触发后重新计算流式内容的滚动进度', async () => {
    const flush = installRaf()
    const observers: ResizeObserver[] = []
    const OriginalResizeObserver = window.ResizeObserver
    window.ResizeObserver = class {
      constructor(private readonly callback: ResizeObserverCallback) { observers.push(this as unknown as ResizeObserver) }
      observe(): void { return undefined }
      disconnect(): void { return undefined }
      unobserve(): void { return undefined }
      trigger(): void { this.callback([], this as unknown as ResizeObserver) }
    } as unknown as typeof ResizeObserver
    let result!: ChatScrollSyncResult
    const { unmount } = render(<Harness onReady={(value) => { result = value }} />)
    const container = document.querySelector('[data-testid="scroll-container"]') as HTMLDivElement
    Object.defineProperties(container, {
      scrollTop: { configurable: true, writable: true, value: 100 },
      scrollHeight: { configurable: true, value: 500 },
      clientHeight: { configurable: true, value: 300 },
    })
    act(flush)
    expect(result.scrollProgress).toBe(0.5)
    expect(observers).toHaveLength(1)
    Object.defineProperty(container, 'scrollTop', { configurable: true, writable: true, value: 150 })
    act(() => {
      (observers[0] as ResizeObserver & { trigger(): void }).trigger()
      container.dispatchEvent(new Event('scroll'))
    })
    act(flush)
    await waitFor(() => expect(result.scrollProgress).toBe(0.75))
    unmount()
    window.ResizeObserver = OriginalResizeObserver
    act(flush)
  })
})
