import { RotateCw, Wifi, WifiOff } from 'lucide-react'

interface DesktopConnectionStatusProps {
  connected: boolean
  label: string
  detail?: string
}

/** 展示运行通道状态，不将未启动的任务通道等同于服务离线。 */
export function DesktopConnectionStatus({ connected, label, detail }: DesktopConnectionStatusProps) {
  return (
    <div className={`desktop-connection-status ${connected ? 'is-connected' : 'is-offline'}`} aria-live="polite">
      {connected ? <Wifi aria-hidden="true" size={14} /> : <WifiOff aria-hidden="true" size={14} />}
      <div>
        <strong>{label}</strong>
        {detail === undefined ? null : <span>{detail}</span>}
      </div>
      {connected ? null : <RotateCw aria-label="运行通道等待连接" size={13} />}
    </div>
  )
}
