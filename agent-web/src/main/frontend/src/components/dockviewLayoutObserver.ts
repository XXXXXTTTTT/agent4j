export interface DockviewLayoutApi {
  layout(width: number, height: number): void
}

/** 在容器尺寸变化后合并到下一帧，确保 Dockview 与拖拽后的 CSS 尺寸一致。 */
export function observeDockviewLayout(api: DockviewLayoutApi, host: HTMLElement): () => void {
  let frame: number | null = null
  let disposed = false
  let width = 0
  let height = 0

  const flush = () => {
    frame = null
    if (disposed || width <= 0 || height <= 0) return
    api.layout(Math.round(width), Math.round(height))
  }
  const schedule = () => {
    if (frame !== null || typeof window === 'undefined') return
    frame = window.requestAnimationFrame(flush)
  }
  const observer = new ResizeObserver((entries) => {
    const rect = entries[0]?.contentRect
    if (rect === undefined) return
    width = rect.width
    height = rect.height
    schedule()
  })
  observer.observe(host)
  const initialRect = host.getBoundingClientRect()
  width = initialRect.width
  height = initialRect.height
  if (width > 0 && height > 0) schedule()

  return () => {
    disposed = true
    observer.disconnect()
    if (frame !== null && typeof window !== 'undefined') window.cancelAnimationFrame(frame)
    frame = null
  }
}
