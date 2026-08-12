import { Settings, X } from 'lucide-react'
import { useState } from 'react'
import type { UseConversationWorkspaceResult } from '../hooks/useConversationWorkspace'
import { ModelProviderSettingsSection } from './ModelProviderSettingsSection'
import { ModelEndpointSettingsSection } from './ModelEndpointSettingsSection'
import { ModelGroupSettingsSection } from './ModelGroupSettingsSection'

interface Props { controller: UseConversationWorkspaceResult; onClose(): void }

export function ModelSettingsDialog({ controller, onClose }: Props) {
  const [busyResource, setBusyResource] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const run = async (resource: string, operation: () => Promise<unknown>) => {
    setBusyResource(resource); setError(null)
    try { await operation() } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)) } finally { setBusyResource(null) }
  }
  return <div className="dialog-backdrop" role="dialog" aria-modal="true" aria-label="模型配置">
    <section className="workspace-dialog model-settings-dialog">
      <header><div><Settings aria-hidden="true" size={17} /><h2>模型池配置</h2></div><button type="button" aria-label="关闭模型配置" title="关闭" onClick={onClose} disabled={busyResource !== null}><X aria-hidden="true" size={16} /></button></header>
      {error !== null && <p className="inline-error" role="alert">{error}</p>}
      <ModelProviderSettingsSection snapshot={controller.modelConfiguration} busy={busyResource !== null} onRun={run} create={controller.createModelProvider} update={controller.updateModelProvider} remove={controller.deleteModelProvider} />
      <ModelEndpointSettingsSection snapshot={controller.modelConfiguration} busy={busyResource !== null} onRun={run} create={controller.createModelEndpoint} update={controller.updateModelEndpoint} remove={controller.deleteModelEndpoint} />
      <ModelGroupSettingsSection snapshot={controller.modelConfiguration} busy={busyResource !== null} onRun={run} create={controller.createModelGroup} update={controller.updateModelGroup} remove={controller.deleteModelGroup} />
    </section>
  </div>
}
