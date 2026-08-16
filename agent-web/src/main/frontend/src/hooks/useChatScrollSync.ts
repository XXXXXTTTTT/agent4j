import { useCallback, useEffect, useRef, useState } from 'react'

export interface ChatScrollSyncResult {
  containerRef: React.MutableRefObject<HTMLDivElement | null>
  scrollProgress: number
  activeMessageId: string | null
  scrollToMessage(id: string): void
}

function clampProgress(value: number): number {
  return Math.min(1, Math.max(0, Number.isFinite(value) ? value : 0))
}

/** 同步对话滚动位置、活跃消息和流式内容尺寸。 */
export function useChatScrollSync(): ChatScrollSyncResult {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const frameRef = useRef<number | null>(null)
  const [scrollProgress, setScrollProgress] = useState(0)
  const [activeMessageId, setActiveMessageId] = useState<string | null>(null)

  const updateScrollState = useCallback(() => {
    const container = containerRef.current
    if (container === null) return
    const range = container.scrollHeight - container.clientHeight
    setScrollProgress(clampProgress(range <= 0 ? 0 : container.scrollTop / range))
    const containerRect = container.getBoundingClientRect()
    const viewportCenter = containerRect.top + container.clientHeight / 2
    const messages = Array.from(container.querySelectorAll<HTMLElement>('[data-message-id]'))
    let nearest: { id: string; distance: number } | null = null
    for (const message of messages) {
      const id = message.dataset.messageId
      if (id === undefined) continue
      const rect = message.getBoundingClientRect()
      const distance = Math.abs((rect.top + rect.bottom) / 2 - viewportCenter)
      if (nearest === null || distance < nearest.distance) nearest = { id, distance }
    }
    setActiveMessageId(nearest?.id ?? null)
  }, [])

  const scheduleUpdate = useCallback(() => {
    if (frameRef.current !== null) return
    const run = () => {
      frameRef.current = null
      updateScrollState()
    }
    if (typeof window.requestAnimationFrame === 'function') frameRef.current = window.requestAnimationFrame(run)
    else frameRef.current = window.setTimeout(run, 0)
  }, [updateScrollState])

  useEffect(() => {
    const container = containerRef.current
    if (container === null) return undefined
    const onScroll = () => scheduleUpdate()
    container.addEventListener('scroll', onScroll, { passive: true })

    let resizeObserver: ResizeObserver | null = null
    if (typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver(() => scheduleUpdate())
      resizeObserver.observe(container)
      container.querySelectorAll<HTMLElement>('[data-message-id]').forEach((element) => resizeObserver?.observe(element))
    }
    const mutationObserver = typeof MutationObserver === 'undefined' ? null : new MutationObserver(() => {
      resizeObserver?.disconnect()
      if (resizeObserver !== null) {
        resizeObserver.observe(container)
        container.querySelectorAll<HTMLElement>('[data-message-id]').forEach((element) => resizeObserver?.observe(element))
      }
      scheduleUpdate()
    })
    mutationObserver?.observe(container, { childList: true, subtree: true, characterData: true })
    scheduleUpdate()
    return () => {
      container.removeEventListener('scroll', onScroll)
      mutationObserver?.disconnect()
      resizeObserver?.disconnect()
      if (frameRef.current !== null) {
        window.cancelAnimationFrame(frameRef.current)
        frameRef.current = null
      }
    }
  }, [scheduleUpdate])

  const scrollToMessage = useCallback((id: string) => {
    const container = containerRef.current
    if (container === null) return
    const target = Array.from(container.querySelectorAll<HTMLElement>('[data-message-id]'))
      .find((element) => element.dataset.messageId === id)
    if (target !== undefined && typeof target.scrollIntoView === 'function') {
      target.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }
  }, [])

  return { containerRef, scrollProgress, activeMessageId, scrollToMessage }
}
