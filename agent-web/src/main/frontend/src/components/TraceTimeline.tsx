import {
  Activity,
  CheckCircle2,
  CircleAlert,
  CircleCheck,
  CirclePause,
  CirclePlay,
  ShieldCheck,
  ShieldX,
  Wifi,
  WifiOff,
  XCircle,
} from 'lucide-react'
import type { ComponentType } from 'react'

import type { TraceEvent } from '../api/contracts'
import type { WorkbenchConnectionState } from '../hooks/useRunWorkbench'

interface TraceTimelineProps {
  events: TraceEvent[]
  connectionState: WorkbenchConnectionState
  persistedNodes: string[]
}

interface TracePresentation {
  label: string
  detail: string
  tone: string
  icon: ComponentType<{ size?: number }>
}

function presentation(event: TraceEvent): TracePresentation {
  switch (event.type) {
    case 'NODE_STARTED':
      return { label: '节点开始', detail: event.nodeName, tone: 'running', icon: CirclePlay }
    case 'NODE_PROGRESS':
      return { label: '执行进度', detail: `${event.nodeName} · ${event.summary}`, tone: 'running', icon: Activity }
    case 'NODE_COMPLETED':
      return { label: '节点完成', detail: `${event.nodeName} → ${event.nextNode}`, tone: 'success', icon: CircleCheck }
    case 'INTERRUPTED':
      return { label: '已挂起', detail: event.request.reason, tone: 'warning', icon: CirclePause }
    case 'APPROVED':
      return { label: '已批准', detail: event.reason, tone: 'success', icon: ShieldCheck }
    case 'REJECTED':
      return { label: '已拒绝', detail: event.reason, tone: 'danger', icon: ShieldX }
    case 'FAILED':
      return { label: '失败', detail: event.error, tone: 'danger', icon: XCircle }
    case 'COMPLETED':
      return { label: '完成', detail: `Checkpoint ${event.checkpointVersion}`, tone: 'success', icon: CheckCircle2 }
  }
}

function socketLabel(value: number | null): string {
  if (value === null) return '未连接'
  if (value === WebSocket.CONNECTING) return '连接中'
  if (value === WebSocket.OPEN) return '已连接'
  if (value === WebSocket.CLOSING) return '关闭中'
  return '已关闭'
}

/** 将图执行事件呈现为右侧状态机信号轨。 */
export function TraceTimeline({ events, connectionState, persistedNodes }: TraceTimelineProps) {
  const connected = connectionState.trace === WebSocket.OPEN
    && connectionState.terminal === WebSocket.OPEN
  return (
    <aside className="trace-timeline" data-testid="trace-timeline" aria-labelledby="trace-title">
      <div className="section-heading">
        <div>
          <p className="section-kicker">STATE SIGNAL</p>
          <h2 id="trace-title">Trace</h2>
        </div>
        <Activity aria-hidden="true" size={19} />
      </div>
      <div className={`connection-strip ${connected ? 'is-online' : ''}`}>
        {connected ? <Wifi aria-hidden="true" size={15} /> : <WifiOff aria-hidden="true" size={15} />}
        <span>Trace {socketLabel(connectionState.trace)}</span>
        <span>PTY {socketLabel(connectionState.terminal)}</span>
      </div>
      {events.length === 0 && persistedNodes.length > 0 ? (
        <>
          <div className="trace-empty is-persisted">
            <CheckCircle2 aria-hidden="true" size={18} />
            <span>已恢复阶段</span>
            <span className="trace-source">已保存节点轨迹</span>
          </div>
          <ol className="signal-rail">
            {persistedNodes.map((nodeName, index) => (
              <li key={`persisted-${nodeName}-${index}`} className="trace-event is-success">
                <span className="trace-marker" aria-hidden="true"><CircleCheck size={17} /></span>
                <div>
                  <strong>节点记录</strong>
                  <p>{nodeName}</p>
                  <time>state.trace[{index}]</time>
                </div>
              </li>
            ))}
          </ol>
        </>
      ) : events.length === 0 ? (
        <div className="trace-empty"><CircleAlert aria-hidden="true" size={18} />等待执行事件</div>
      ) : (
        <ol className="signal-rail">
          {events.map((event, index) => {
            const item = presentation(event)
            const Icon = item.icon
            return (
              <li key={`${event.eventId}-${index}`} className={`trace-event is-${item.tone}`}>
                <span className="trace-marker" aria-hidden="true"><Icon size={17} /></span>
                <div>
                  <strong>{item.label}</strong>
                  <p>{item.detail}</p>
                  <time dateTime={event.occurredAt}>v{event.checkpointVersion}</time>
                </div>
              </li>
            )
          })}
        </ol>
      )}
    </aside>
  )
}
