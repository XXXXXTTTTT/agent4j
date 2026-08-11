import { Settings, X } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import type { UseConversationWorkspaceResult } from '../hooks/useConversationWorkspace'

interface ModelSettingsDialogProps { controller: UseConversationWorkspaceResult; onClose(): void }

/** 管理当前用户 Provider 和模型组的轻量配置面板。 */
export function ModelSettingsDialog({ controller, onClose }: ModelSettingsDialogProps) {
  const [provider, setProvider] = useState({ displayName: '', baseUrl: '', apiKey: '' })
  const [group, setGroup] = useState({ displayName: '', taskType: 'CODE', endpointIds: [] as string[] })
  const [endpoint, setEndpoint] = useState({ providerId: '', displayName: '', modelId: '', capabilities: ['CHAT_COMPLETIONS'], priority: 0, weight: 1, enabled: true })
  const [error, setError] = useState<string | null>(null)
  async function createProvider(event: FormEvent): Promise<void> {
    event.preventDefault(); setError(null)
    try { if (controller.createModelProvider === undefined) throw new Error('模型 Provider 接口未配置'); await controller.createModelProvider(provider); await controller.reloadModelConfiguration() } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)) }
  }
  async function createGroup(event: FormEvent): Promise<void> {
    event.preventDefault(); setError(null)
    try { if (controller.createModelGroup === undefined) throw new Error('模型组接口未配置'); await controller.createModelGroup(group); await controller.reloadModelConfiguration() } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)) }
  }
  async function createEndpoint(event: FormEvent): Promise<void> {
    event.preventDefault(); setError(null)
    try { if (controller.createModelEndpoint === undefined) throw new Error('模型端点接口未配置'); await controller.createModelEndpoint(endpoint); await controller.reloadModelConfiguration() } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)) }
  }
  return <div className="dialog-backdrop" role="dialog" aria-modal="true" aria-label="模型配置">
    <section className="workspace-dialog model-settings-dialog">
      <header><div><Settings aria-hidden="true" size={17} /><h2>模型池配置</h2></div><button type="button" aria-label="关闭模型配置" title="关闭" onClick={onClose}><X aria-hidden="true" size={16} /></button></header>
      <form onSubmit={(event) => void createProvider(event)}><h3>新增 Provider</h3><input aria-label="Provider 名称" placeholder="Provider 名称" value={provider.displayName} onChange={(event) => setProvider({ ...provider, displayName: event.target.value })} /><input aria-label="API Base URL" placeholder="https://gateway.example/v1" value={provider.baseUrl} onChange={(event) => setProvider({ ...provider, baseUrl: event.target.value })} /><input aria-label="API Key" type="password" placeholder="API Key" value={provider.apiKey} onChange={(event) => setProvider({ ...provider, apiKey: event.target.value })} /><button type="submit">保存 Provider</button></form>
      <form onSubmit={(event) => void createGroup(event)}><h3>新增模型组</h3><input aria-label="模型组名称" placeholder="模型组名称" value={group.displayName} onChange={(event) => setGroup({ ...group, displayName: event.target.value })} /><select aria-label="任务类型" value={group.taskType} onChange={(event) => setGroup({ ...group, taskType: event.target.value })}><option value="CODE">代码</option><option value="VISION">视觉</option><option value="QUICK_CLASSIFICATION">快速分类</option></select><select aria-label="模型端点" multiple value={group.endpointIds} onChange={(event) => setGroup({ ...group, endpointIds: Array.from(event.target.selectedOptions, (option) => option.value) })}>{controller.modelConfiguration.endpoints.map((endpoint) => <option key={endpoint.endpointId} value={endpoint.endpointId}>{endpoint.displayName} · {endpoint.modelId}</option>)}</select><button type="submit">保存模型组</button></form>
      <form onSubmit={(event) => void createEndpoint(event)}><h3>新增模型端点</h3><select aria-label="Provider" value={endpoint.providerId} onChange={(event) => setEndpoint({ ...endpoint, providerId: event.target.value })}><option value="">选择 Provider</option>{controller.modelConfiguration.providers.map((provider) => <option key={provider.providerId} value={provider.providerId}>{provider.displayName} · {provider.apiKeyMasked}</option>)}</select><input aria-label="端点名称" placeholder="端点名称" value={endpoint.displayName} onChange={(event) => setEndpoint({ ...endpoint, displayName: event.target.value })} /><input aria-label="模型 ID" placeholder="精确模型 ID" value={endpoint.modelId} onChange={(event) => setEndpoint({ ...endpoint, modelId: event.target.value })} /><button type="submit">保存模型端点</button></form>
      {error === null ? null : <p className="inline-error" role="alert">{error}</p>}
    </section>
  </div>
}
