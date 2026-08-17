import { afterEach, describe, expect, it, vi } from 'vitest'

import { observeDockviewLayout } from './dockviewLayoutObserver'

class FakeResizeObserver {
  static latest: FakeResizeObserver | null = null
  constructor(private readonly callback: ResizeObserverCallback) { FakeResizeObserver.latest = this }
  observe = vi.fn()
  disconnect = vi.fn()
  trigger(width: number, height: number): void {
    this.callback([{ contentRect: { width, height } } as ResizeObserverEntry], this as unknown as ResizeObserver)
  }
}

describe('Dockview 尺寸观察器', () => {
  afterEach(() => { FakeResizeObserver.latest = null; vi.restoreAllMocks() })

  it('容器尺寸变化后在下一帧调用 Dockview layout', () => {
    vi.stubGlobal('ResizeObserver', FakeResizeObserver)
    let queuedFrame: FrameRequestCallback | null = null
    const requestFrame = vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => { queuedFrame = callback; return 1 })
    const api = { layout: vi.fn() }
    const host = document.createElement('div')
    const dispose = observeDockviewLayout(api, host)

    FakeResizeObserver.latest?.trigger(1280, 720)
    queuedFrame?.(0)

    expect(requestFrame).toHaveBeenCalledTimes(1)
    expect(api.layout).toHaveBeenCalledWith(1280, 720)
    dispose()
    expect(FakeResizeObserver.latest?.disconnect).toHaveBeenCalledTimes(1)
  })
})
