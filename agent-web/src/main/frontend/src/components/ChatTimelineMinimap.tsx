import { useCallback, useEffect, useRef, useState } from 'react'

export type ChatTimelineRole = 'user' | 'agent' | 'tool'
export type ChatTimelineStatus = 'pending' | 'running' | 'success' | 'failed'

export interface ChatTimelineEntry {
  id: string
  label: string
  summary: string
  role: ChatTimelineRole
  status: ChatTimelineStatus
}

interface ChatTimelineMinimapProps {
  messages: ChatTimelineEntry[]
  scrollProgress: number
  activeMessageId?: string | null
  onSelectTurn(id: string): void
  onProgressChange?(progress: number): void
}

function clampProgress(value: number): number {
  return Math.min(1, Math.max(0, Number.isFinite(value) ? value : 0))
}

/** 对话消息的垂直刻度索引，支持点击和拖拽定位。 */
export function ChatTimelineMinimap({
  messages,
  scrollProgress,
  activeMessageId = null,
  onSelectTurn,
  onProgressChange,
}: ChatTimelineMinimapProps) {
  const railRef = useRef<HTMLDivElement | null>(null)
  const [dragging, setDragging] = useState(false)
  const progress = clampProgress(scrollProgress)

  const progressFromClientY = useCallback((clientY: number) => {
    const rail = railRef.current
    if (rail === null) return
    const rect = rail.getBoundingClientRect()
    if (rect.height <= 0) return
    onProgressChange?.(clampProgress((clientY - rect.top) / rect.height))
  }, [onProgressChange])

  useEffect(() => {
    if (!dragging) return undefined
    const move = (event: PointerEvent) => progressFromClientY(event.clientY)
    const end = () => setDragging(false)
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', end, { once: true })
    return () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', end)
    }
  }, [dragging, progressFromClientY])

  if (messages.length === 0) return null

  return (
    <aside className={`chat-timeline-minimap ${dragging ? 'is-dragging' : ''}`} aria-label="对话时间轴">
      <div
        ref={railRef}
        className="chat-timeline-rail"
        data-testid="timeline-tick-rail"
        onPointerDown={(event) => {
          if ((event.target as Element).closest('button') !== null) return
          setDragging(true)
          progressFromClientY(event.clientY)
        }}
      >
        <span
          className="chat-timeline-viewport-cursor"
          data-testid="timeline-viewport-cursor"
          style={{ top: `calc(${progress * 100}% - ${progress * 28}px)` }}
          aria-hidden="true"
        />
        <div className="chat-timeline-ticks">
          {messages.map((message, index) => (
            <button
              className={`chat-timeline-tick role-${message.role} status-${message.status} ${activeMessageId === message.id ? 'is-active' : ''}`}
              data-role={message.role}
              data-status={message.status}
              key={message.id}
              type="button"
              title={`${message.label}：${message.summary}`}
              aria-label={`${message.label}：${message.summary}`}
              style={{ top: `${messages.length === 1 ? 0 : (index / (messages.length - 1)) * 100}%` }}
              onClick={() => onSelectTurn(message.id)}
            >
              <span className="chat-timeline-tick-mark" aria-hidden="true" />
            </button>
          ))}
        </div>
      </div>
    </aside>
  )
}
