import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ModelConfigurationSnapshot } from '../api/contracts'
import { ModelSettingsDialog } from './ModelSettingsDialog'
import { ModelProviderSettingsSection } from './ModelProviderSettingsSection'
import { ModelEndpointSettingsSection } from './ModelEndpointSettingsSection'
import { ModelGroupSettingsSection } from './ModelGroupSettingsSection'

const snapshot: ModelConfigurationSnapshot = {
  providers: [{ providerId: 'p1', ownerUserId: 'u1', displayName: 'OpenAI', baseUrl: 'https://api.openai.com', chatCompletionsPath: '/v1/chat/completions', apiKeyMasked: 'sk-***', createdAt: '', updatedAt: '' }],
  endpoints: [
    { endpointId: 'e1', providerId: 'p1', displayName: 'GPT 4o', modelId: 'gpt-4o', capabilities: ['CHAT_COMPLETIONS', 'STREAMING'], priority: 1, weight: 2, enabled: true, createdAt: '', updatedAt: '' },
    { endpointId: 'e2', providerId: 'p1', displayName: 'Vision', modelId: 'vision', capabilities: ['VISION_INPUT'], priority: 0, weight: 1, enabled: false, createdAt: '', updatedAt: '' },
  ],
  groups: [{ groupId: 'g1', ownerUserId: 'u1', displayName: '代码组', taskType: 'CODE', endpointIds: ['e2', 'e1'], createdAt: '', updatedAt: '' }],
}

function controller(overrides: Record<string, unknown> = {}) {
  return { modelConfiguration: snapshot, updateModelProvider: vi.fn(async () => snapshot), deleteModelProvider: vi.fn(async () => snapshot), updateModelEndpoint: vi.fn(async () => snapshot), deleteModelEndpoint: vi.fn(async () => snapshot), updateModelGroup: vi.fn(async () => snapshot), deleteModelGroup: vi.fn(async () => snapshot), createModelProvider: vi.fn(async () => snapshot), createModelEndpoint: vi.fn(async () => snapshot), createModelGroup: vi.fn(async () => snapshot), ...overrides } as any
}

describe('ModelSettingsDialog', () => {
  it('shows all existing resources', () => {
    render(<ModelSettingsDialog controller={controller()} onClose={vi.fn()} />)
    expect(screen.getAllByText('OpenAI').length).toBeGreaterThan(0)
    expect(screen.getAllByText('GPT 4o').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Vision').length).toBeGreaterThan(0)
    expect(screen.getByText('代码组')).toBeInTheDocument()
    expect(screen.getByText(/Vision, GPT 4o/)).toBeInTheDocument()
  })

  it('does not refill provider key and sends only entered key', async () => {
    const user = userEvent.setup(); const update = vi.fn(async () => snapshot)
    render(<ModelSettingsDialog controller={controller({ updateModelProvider: update })} onClose={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: '编辑 Provider OpenAI' }))
    expect(screen.getByLabelText('Provider API Key')).toHaveValue('')
    await user.clear(screen.getByLabelText('Provider 名称')); await user.type(screen.getByLabelText('Provider 名称'), 'OpenAI 2')
    await user.type(screen.getByLabelText('Provider API Key'), 'secret')
    await user.click(screen.getByRole('button', { name: '保存 Provider' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith('p1', { displayName: 'OpenAI 2', baseUrl: 'https://api.openai.com', chatCompletionsPath: '/v1/chat/completions', apiKey: 'secret' }))
  })

  it('omits an empty provider key on update', async () => {
    const user = userEvent.setup(); const update = vi.fn(async () => snapshot)
    render(<ModelSettingsDialog controller={controller({ updateModelProvider: update })} onClose={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: '编辑 Provider OpenAI' }))
    await user.click(screen.getByRole('button', { name: '保存 Provider' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith('p1', { displayName: 'OpenAI', baseUrl: 'https://api.openai.com', chatCompletionsPath: '/v1/chat/completions' }))
  })

  it('confirms provider deletion and reports conflict', async () => {
    const user = userEvent.setup(); const remove = vi.fn(async () => { throw new Error('Provider 仍被端点引用') })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<ModelSettingsDialog controller={controller({ deleteModelProvider: remove })} onClose={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: '删除 Provider OpenAI' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Provider 仍被端点引用'))
  })

  it('edits endpoint controls and group members in stable order', async () => {
    const user = userEvent.setup(); const updateEndpoint = vi.fn(async () => snapshot); const updateGroup = vi.fn(async () => snapshot)
    render(<ModelSettingsDialog controller={controller({ updateModelEndpoint: updateEndpoint, updateModelGroup: updateGroup })} onClose={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: '编辑端点 GPT 4o' }))
    await user.clear(screen.getByLabelText('端点优先级')); await user.type(screen.getByLabelText('端点优先级'), '3')
    await user.click(screen.getByRole('button', { name: '保存端点' }))
    await waitFor(() => expect(updateEndpoint).toHaveBeenCalledWith('e1', expect.objectContaining({ priority: 3, weight: 2, enabled: true, capabilities: ['CHAT_COMPLETIONS', 'STREAMING'] })))
    await user.click(screen.getByRole('button', { name: '编辑模型组 代码组' }))
    const vision = screen.getByLabelText('组成员 Vision'); await user.click(vision)
    await user.click(screen.getByRole('button', { name: '保存模型组' }))
    await waitFor(() => expect(updateGroup).toHaveBeenCalledWith('g1', expect.objectContaining({ endpointIds: ['e1'] })))
    expect(updateEndpoint.mock.calls[0][1]).not.toHaveProperty('providerId')
    expect(screen.getByText(/P1\/W2/)).toBeInTheDocument()
  })

  it('reports endpoint and group delete conflicts after confirmation', async () => {
    const user = userEvent.setup(); vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<ModelSettingsDialog controller={controller({ deleteModelEndpoint: vi.fn(async () => { throw new Error('端点仍被模型组引用') }), deleteModelGroup: vi.fn(async () => { throw new Error('模型组删除冲突') }) })} onClose={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: '删除端点 GPT 4o' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('端点仍被模型组引用'))
    await user.click(screen.getByRole('button', { name: '删除模型组 代码组' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('模型组删除冲突'))
  })

  it.each([
    ['provider', ModelProviderSettingsSection],
    ['endpoint', ModelEndpointSettingsSection],
    ['group', ModelGroupSettingsSection],
  ])('%s serializes an in-flight save', async (_name, Section) => {
    let resolve!: () => void
    const onRun = vi.fn(() => new Promise<void>((done) => { resolve = done }))
    const props = { snapshot, busy: false, onRun, create: vi.fn(async () => snapshot), update: vi.fn(async () => snapshot), remove: vi.fn(async () => snapshot) } as any
    render(<Section {...props} />)
    const form = screen.getAllByRole('button', { name: /新增|保存/ }).at(-1)!.closest('form')!
    fireEvent.submit(form)
    fireEvent.submit(form)
    expect(onRun).toHaveBeenCalledTimes(1)
    expect(Array.from(form.querySelectorAll('input,select,button')).every((control) => (control as HTMLInputElement).disabled)).toBe(true)
    resolve()
  })
})
