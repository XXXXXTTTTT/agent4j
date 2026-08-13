import { useCallback, useEffect, useRef, useState } from 'react'

import {
  archiveConversation,
  browseWorkspaceDirectories,
  createConversation,
  createWorkspace,
  getIdentity,
  listConversationTurns,
  listConversations,
  listWorkspaces,
  importWorkspace,
  importDesktopWorkspace,
  searchConversations,
  submitConversationTurn,
  listModelConfiguration,
  deleteConversation,
  createModelProvider,
  createModelGroup,
  createModelEndpoint,
  updateModelProvider,
  updateModelEndpoint,
  updateModelGroup,
  deleteModelProvider,
  deleteModelEndpoint,
  deleteModelGroup,
} from '../api/conversationApi'
import type {
  Actor,
  Conversation,
  ConversationTurn,
  Workspace,
  WorkspaceDirectoryListing,
  ModelConfigurationSnapshot,
} from '../api/contracts'
import type { CreateWorkspaceCommand, ImportDesktopWorkspaceCommand, ImportWorkspaceCommand, UpdateModelEndpointCommand, UpdateModelGroupCommand, UpdateModelProviderCommand } from '../api/conversationApi'

export interface ConversationWorkspaceApi {
  getIdentity(): Promise<Actor>
  listWorkspaces(): Promise<Workspace[]>
  listConversations(workspaceId: string, includeArchived?: boolean): Promise<Conversation[]>
  searchConversations(workspaceId: string, query: string, includeArchived?: boolean): Promise<Conversation[]>
  createWorkspace(command: CreateWorkspaceCommand): Promise<Workspace>
  browseWorkspaceDirectories?(path: string): Promise<WorkspaceDirectoryListing>
  importWorkspace?(command: ImportWorkspaceCommand): Promise<Workspace>
  importDesktopWorkspace?(command: ImportDesktopWorkspaceCommand): Promise<Workspace>
  createConversation(workspaceId: string): Promise<Conversation>
  submitConversationTurn(conversationId: string, command: { content: string; reviewerUrl?: string; modelGroupId?: string }): Promise<ConversationTurn>
  listConversationTurns(conversationId: string): Promise<ConversationTurn[]>
  archiveConversation(conversationId: string): Promise<Conversation>
  deleteConversation?(conversationId: string): Promise<Conversation>
  listModelConfiguration?(): Promise<ModelConfigurationSnapshot>
  createModelProvider?(command: { displayName: string; baseUrl: string; chatCompletionsPath?: string; apiKey: string }): Promise<ModelConfigurationSnapshot>
  createModelGroup?(command: { displayName: string; taskType: string; endpointIds: string[] }): Promise<ModelConfigurationSnapshot>
  createModelEndpoint?(command: { providerId: string; displayName: string; modelId: string; capabilities: string[]; priority: number; weight: number; enabled: boolean }): Promise<ModelConfigurationSnapshot>
  updateModelProvider?(id: string, command: UpdateModelProviderCommand): Promise<ModelConfigurationSnapshot>
  updateModelEndpoint?(id: string, command: UpdateModelEndpointCommand): Promise<ModelConfigurationSnapshot>
  updateModelGroup?(id: string, command: UpdateModelGroupCommand): Promise<ModelConfigurationSnapshot>
  deleteModelProvider?(id: string): Promise<ModelConfigurationSnapshot>
  deleteModelEndpoint?(id: string): Promise<ModelConfigurationSnapshot>
  deleteModelGroup?(id: string): Promise<ModelConfigurationSnapshot>
}

const DEFAULT_API: ConversationWorkspaceApi = {
  getIdentity: () => getIdentity(),
  listWorkspaces: () => listWorkspaces(),
  listConversations: (workspaceId, includeArchived) => listConversations(workspaceId, includeArchived),
  searchConversations: (workspaceId, query, includeArchived) => searchConversations(workspaceId, query, includeArchived),
  createWorkspace: (command) => createWorkspace(command),
  browseWorkspaceDirectories: (path) => browseWorkspaceDirectories(path),
  importWorkspace: (command) => importWorkspace(command),
  importDesktopWorkspace: (command) => importDesktopWorkspace(command),
  createConversation: (workspaceId) => createConversation(workspaceId),
  submitConversationTurn: (conversationId, command) => submitConversationTurn(conversationId, command),
  listConversationTurns: (conversationId) => listConversationTurns(conversationId),
  archiveConversation: (conversationId) => archiveConversation(conversationId),
  deleteConversation: (conversationId) => deleteConversation(conversationId),
  listModelConfiguration: () => listModelConfiguration(),
  createModelProvider: (command) => createModelProvider(command),
  createModelGroup: (command) => createModelGroup(command),
  createModelEndpoint: (command) => createModelEndpoint(command),
  updateModelProvider: (id, command) => updateModelProvider(id, command),
  updateModelEndpoint: (id, command) => updateModelEndpoint(id, command),
  updateModelGroup: (id, command) => updateModelGroup(id, command),
  deleteModelProvider: (id) => deleteModelProvider(id),
  deleteModelEndpoint: (id) => deleteModelEndpoint(id),
  deleteModelGroup: (id) => deleteModelGroup(id),
}

export interface UseConversationWorkspaceOptions {
  api?: ConversationWorkspaceApi
}

export interface UseConversationWorkspaceResult {
  identity: Actor | null
  workspaces: Workspace[]
  activeWorkspace: Workspace | null
  conversations: Conversation[]
  activeConversation: Conversation | null
  turns: ConversationTurn[]
  searchQuery: string
  includeArchived: boolean
  loading: boolean
  submitting: boolean
  error: Error | null
  modelConfiguration: ModelConfigurationSnapshot
  selectWorkspace(workspaceId: string): Promise<void>
  createWorkspace(command: CreateWorkspaceCommand): Promise<void>
  browseWorkspaceDirectories(path: string): Promise<WorkspaceDirectoryListing>
  importWorkspace(command: ImportWorkspaceCommand): Promise<void>
  importDesktopWorkspace(command: ImportDesktopWorkspaceCommand): Promise<void>
  selectConversation(conversationId: string): Promise<void>
  search(query: string): Promise<void>
  toggleArchived(): Promise<void>
  createConversation(): Promise<void>
  submit(content: string, reviewerUrl?: string, modelGroupId?: string): Promise<ConversationTurn>
  deleteConversation(): Promise<void>
  archive(): Promise<void>
  reload(): Promise<void>
  reloadModelConfiguration(): Promise<void>
  createModelProvider?(command: { displayName: string; baseUrl: string; chatCompletionsPath?: string; apiKey: string }): Promise<ModelConfigurationSnapshot>
  createModelGroup?(command: { displayName: string; taskType: string; endpointIds: string[] }): Promise<ModelConfigurationSnapshot>
  createModelEndpoint?(command: { providerId: string; displayName: string; modelId: string; capabilities: string[]; priority: number; weight: number; enabled: boolean }): Promise<ModelConfigurationSnapshot>
  updateModelProvider(id: string, command: UpdateModelProviderCommand): Promise<ModelConfigurationSnapshot>
  updateModelEndpoint(id: string, command: UpdateModelEndpointCommand): Promise<ModelConfigurationSnapshot>
  updateModelGroup(id: string, command: UpdateModelGroupCommand): Promise<ModelConfigurationSnapshot>
  deleteModelProvider(id: string): Promise<ModelConfigurationSnapshot>
  deleteModelEndpoint(id: string): Promise<ModelConfigurationSnapshot>
  deleteModelGroup(id: string): Promise<ModelConfigurationSnapshot>
}

function asError(value: unknown): Error {
  return value instanceof Error ? value : new Error(String(value))
}

function readConversationId(): string | null {
  const value = new URLSearchParams(window.location.search).get('conversationId')
  return value !== null && value.trim().length > 0 ? value : null
}

function readWorkspaceId(): string | null {
  const value = new URLSearchParams(window.location.search).get('workspaceId')
  return value !== null && value.trim().length > 0 ? value : null
}

function writeSelection(workspaceId: string | null, conversationId: string | null): void {
  const url = new URL(window.location.href)
  if (workspaceId === null) url.searchParams.delete('workspaceId')
  else url.searchParams.set('workspaceId', workspaceId)
  if (conversationId === null) url.searchParams.delete('conversationId')
  else url.searchParams.set('conversationId', conversationId)
  window.history.replaceState({}, '', `${url.pathname}${url.search}${url.hash}`)
}

/** 管理用户、工作区和持久化会话，消息始终来自服务端。 */
export function useConversationWorkspace(
  options: UseConversationWorkspaceOptions = {},
): UseConversationWorkspaceResult {
  const apiRef = useRef(options.api ?? DEFAULT_API)
  apiRef.current = options.api ?? DEFAULT_API
  const [identity, setIdentity] = useState<Actor | null>(null)
  const [workspaces, setWorkspaces] = useState<Workspace[]>([])
  const [activeWorkspaceId, setActiveWorkspaceId] = useState<string | null>(null)
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [activeConversationId, setActiveConversationId] = useState<string | null>(readConversationId)
  const [turns, setTurns] = useState<ConversationTurn[]>([])
  const [searchQuery, setSearchQuery] = useState('')
  const [includeArchived, setIncludeArchived] = useState(false)
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const [modelConfiguration, setModelConfiguration] = useState<ModelConfigurationSnapshot>({ providers: [], endpoints: [], groups: [] })
  const operationRef = useRef(0)
  const conversationListOperationRef = useRef(0)

  const activeWorkspace = workspaces.find((item) => item.workspaceId === activeWorkspaceId) ?? null
  const activeConversation = conversations.find((item) => item.conversationId === activeConversationId) ?? null

  const loadTurns = useCallback(async (conversationId: string): Promise<void> => {
    const operation = ++operationRef.current
    setLoading(true)
    setError(null)
    try {
      const loaded = await apiRef.current.listConversationTurns(conversationId)
      if (operation === operationRef.current) setTurns(loaded)
    } catch (failure) {
      if (operation === operationRef.current) setError(asError(failure))
      throw failure
    } finally {
      if (operation === operationRef.current) setLoading(false)
    }
  }, [])

  const loadWorkspaceConversations = useCallback(async (workspaceId: string, query = ''): Promise<Conversation[] | null> => {
    const operation = ++conversationListOperationRef.current
    const loaded = query.trim().length === 0
      ? (includeArchived ? await apiRef.current.listConversations(workspaceId, true) : await apiRef.current.listConversations(workspaceId))
      : (includeArchived ? await apiRef.current.searchConversations(workspaceId, query, true) : await apiRef.current.searchConversations(workspaceId, query))
    if (operation !== conversationListOperationRef.current) return null
    setConversations(loaded)
    return loaded
  }, [includeArchived])

  const selectConversation = useCallback(async (conversationId: string): Promise<void> => {
    const exactId = conversationId.trim()
    if (exactId.length === 0) throw new Error('conversationId 不能为空')
    setActiveConversationId(exactId)
    writeSelection(activeWorkspaceId, exactId)
    await loadTurns(exactId)
  }, [activeWorkspaceId, loadTurns])

  const selectWorkspace = useCallback(async (workspaceId: string): Promise<void> => {
    const exactId = workspaceId.trim()
    if (exactId.length === 0) throw new Error('workspaceId 不能为空')
    setError(null)
    setLoading(true)
    try {
      const loaded = await loadWorkspaceConversations(exactId, searchQuery)
      if (loaded === null) return
      setActiveWorkspaceId(exactId)
      const selected = loaded.find((item) => item.conversationId === activeConversationId) ?? loaded[0] ?? null
      setActiveConversationId(selected?.conversationId ?? null)
      writeSelection(exactId, selected?.conversationId ?? null)
      if (selected === null) setTurns([])
      else await loadTurns(selected.conversationId)
    } catch (failure) {
      setError(asError(failure))
      throw failure
    } finally {
      setLoading(false)
    }
  }, [activeConversationId, loadTurns, loadWorkspaceConversations, searchQuery])

  const reload = useCallback(async (): Promise<void> => {
    if (activeWorkspaceId === null) return
    setLoading(true)
    setError(null)
    try {
      const loaded = await loadWorkspaceConversations(activeWorkspaceId, searchQuery)
      if (loaded === null) return
      const selected = loaded.find((item) => item.conversationId === activeConversationId) ?? loaded[0] ?? null
      setActiveConversationId(selected?.conversationId ?? null)
      writeSelection(activeWorkspaceId, selected?.conversationId ?? null)
      if (selected === null) setTurns([])
      else await loadTurns(selected.conversationId)
    } catch (failure) {
      setError(asError(failure))
      throw failure
    } finally {
      setLoading(false)
    }
  }, [activeConversationId, activeWorkspaceId, loadTurns, loadWorkspaceConversations, searchQuery])

  const search = useCallback(async (query: string): Promise<void> => {
    setSearchQuery(query)
    if (activeWorkspaceId === null) return
    try {
      await loadWorkspaceConversations(activeWorkspaceId, query)
    } catch (failure) {
      setError(asError(failure))
      throw failure
    }
  }, [activeWorkspaceId, loadWorkspaceConversations])

  const toggleArchived = useCallback(async (): Promise<void> => {
    setIncludeArchived((value) => !value)
  }, [])

  const create = useCallback(async (): Promise<void> => {
    if (activeWorkspaceId === null) throw new Error('当前没有工作区')
    setError(null)
    try {
      const created = await apiRef.current.createConversation(activeWorkspaceId)
      setConversations((items) => [created, ...items.filter((item) => item.conversationId !== created.conversationId)])
      setActiveConversationId(created.conversationId)
      setTurns([])
      writeSelection(activeWorkspaceId, created.conversationId)
    } catch (failure) {
      setError(asError(failure))
      throw failure
    }
  }, [activeWorkspaceId])

  const createWorkspaceEntry = useCallback(async (command: CreateWorkspaceCommand): Promise<void> => {
    setError(null)
    try {
      const created = await apiRef.current.createWorkspace(command)
      setWorkspaces((items) => [created, ...items.filter((item) => item.workspaceId !== created.workspaceId)])
      await selectWorkspace(created.workspaceId)
    } catch (failure) {
      setError(asError(failure))
      throw failure
    }
  }, [selectWorkspace])

  const browseWorkspaceDirectoryEntries = useCallback(async (path: string): Promise<WorkspaceDirectoryListing> => {
    const browse = apiRef.current.browseWorkspaceDirectories
    if (browse === undefined) throw new Error('工作区目录浏览接口未配置')
    return browse(path)
  }, [])

  const importWorkspaceEntry = useCallback(async (command: ImportWorkspaceCommand): Promise<void> => {
    const importProject = apiRef.current.importWorkspace
    if (importProject === undefined) throw new Error('工作区导入接口未配置')
    setError(null)
    try {
      const created = await importProject(command)
      setWorkspaces((items) => [created, ...items.filter((item) => item.workspaceId !== created.workspaceId)])
      await selectWorkspace(created.workspaceId)
    } catch (failure) {
      setError(asError(failure))
      throw failure
    }
  }, [selectWorkspace])

  const importDesktopWorkspaceEntry = useCallback(async (command: ImportDesktopWorkspaceCommand): Promise<void> => {
    const importProject = apiRef.current.importDesktopWorkspace
    if (importProject === undefined) throw new Error('桌面工作区导入接口未配置')
    setError(null)
    try {
      const created = await importProject(command)
      setWorkspaces((items) => [created, ...items.filter((item) => item.workspaceId !== created.workspaceId)])
      await selectWorkspace(created.workspaceId)
    } catch (failure) {
      setError(asError(failure))
      throw failure
    }
  }, [selectWorkspace])

  const submit = useCallback(async (content: string, reviewerUrl?: string, modelGroupId?: string): Promise<ConversationTurn> => {
    if (activeConversationId === null) throw new Error('当前没有会话')
    const exactContent = content.trim()
    if (exactContent.length === 0) throw new Error('消息内容不能为空')
    setSubmitting(true)
    setError(null)
    try {
      const created = await apiRef.current.submitConversationTurn(activeConversationId, { content: exactContent, ...(reviewerUrl?.trim() ? { reviewerUrl: reviewerUrl.trim() } : {}), ...(modelGroupId?.trim() ? { modelGroupId: modelGroupId.trim() } : {}) })
      setTurns((items) => [...items.filter((item) => item.turnId !== created.turnId), created].sort((a, b) => a.turnIndex - b.turnIndex))
      return created
    } catch (failure) {
      setError(asError(failure))
      throw failure
    } finally {
      setSubmitting(false)
    }
  }, [activeConversationId])

  const deleteActiveConversation = useCallback(async (): Promise<void> => {
    if (activeConversationId === null) throw new Error('当前没有会话')
    if (apiRef.current.deleteConversation === undefined) throw new Error('删除会话接口未配置')
    setError(null)
    try {
      await apiRef.current.deleteConversation(activeConversationId)
      setConversations((items) => items.filter((item) => item.conversationId !== activeConversationId))
      setActiveConversationId(null); setTurns([]); writeSelection(activeWorkspaceId, null)
    } catch (failure) {
      setError(asError(failure))
      throw failure
    }
  }, [activeConversationId, activeWorkspaceId])

  const reloadModelConfiguration = useCallback(async (): Promise<void> => {
    if (apiRef.current.listModelConfiguration === undefined) return
    setModelConfiguration(await apiRef.current.listModelConfiguration())
  }, [])

  const runModelConfigurationCommand = useCallback(async (command: (() => Promise<ModelConfigurationSnapshot>) | undefined, missingMessage: string): Promise<ModelConfigurationSnapshot> => {
    if (command === undefined) {
      const failure = new Error(missingMessage)
      setError(failure)
      throw failure
    }
    setError(null)
    try {
      const snapshot = await command()
      setModelConfiguration(snapshot)
      return snapshot
    } catch (failure) {
      setError(asError(failure))
      throw failure
    }
  }, [])

  const archive = useCallback(async (): Promise<void> => {
    if (activeConversationId === null) throw new Error('当前没有会话')
    try {
      const archived = await apiRef.current.archiveConversation(activeConversationId)
      setConversations((items) => includeArchived
        ? items.map((item) => item.conversationId === archived.conversationId ? archived : item)
        : items.filter((item) => item.conversationId !== archived.conversationId))
      setActiveConversationId(null)
      setTurns([])
      writeSelection(activeWorkspaceId, null)
    } catch (failure) {
      setError(asError(failure))
      throw failure
    }
  }, [activeConversationId, activeWorkspaceId, includeArchived])

  useEffect(() => {
    let disposed = false
    async function initialize(): Promise<void> {
      setLoading(true)
      try {
        const [loadedIdentity, loadedWorkspaces, loadedModelConfiguration] = await Promise.all([
          apiRef.current.getIdentity(), apiRef.current.listWorkspaces(),
          apiRef.current.listModelConfiguration === undefined
            ? Promise.resolve({ providers: [], endpoints: [], groups: [] } as ModelConfigurationSnapshot)
            : apiRef.current.listModelConfiguration(),
        ])
        if (disposed) return
        setIdentity(loadedIdentity)
        setModelConfiguration(loadedModelConfiguration)
        setWorkspaces(loadedWorkspaces)
        const requestedWorkspaceId = readWorkspaceId()
        const requestedConversationId = readConversationId()
        const selectedWorkspace = loadedWorkspaces.find((item) => item.workspaceId === requestedWorkspaceId)
          ?? loadedWorkspaces[0]
          ?? null
        if (disposed) return
        if (selectedWorkspace === null) {
          setActiveWorkspaceId(null); setConversations([]); setActiveConversationId(null); setTurns([]); writeSelection(null, null); return
        }
        const loadedConversations = includeArchived
          ? await apiRef.current.listConversations(selectedWorkspace.workspaceId, true)
          : await apiRef.current.listConversations(selectedWorkspace.workspaceId)
        if (disposed) return
        setActiveWorkspaceId(selectedWorkspace.workspaceId)
        setConversations(loadedConversations)
        const selectedConversation = loadedConversations.find((item) => item.conversationId === requestedConversationId)
          ?? loadedConversations[0]
          ?? null
        setActiveConversationId(selectedConversation?.conversationId ?? null)
        writeSelection(selectedWorkspace.workspaceId, selectedConversation?.conversationId ?? null)
        if (selectedConversation === null) setTurns([])
        else setTurns(await apiRef.current.listConversationTurns(selectedConversation.conversationId))
      } catch (failure) {
        if (!disposed) setError(asError(failure))
      } finally {
        if (!disposed) setLoading(false)
      }
    }
    void initialize()
    return () => { disposed = true }
  }, [includeArchived])

  return {
    identity, workspaces, activeWorkspace, conversations, activeConversation, turns, searchQuery,
    loading, submitting, error, includeArchived, selectWorkspace, selectConversation, search, toggleArchived,
    createConversation: create, createWorkspace: createWorkspaceEntry,
    browseWorkspaceDirectories: browseWorkspaceDirectoryEntries,
    importWorkspace: importWorkspaceEntry,
    importDesktopWorkspace: importDesktopWorkspaceEntry,
    submit, archive, deleteConversation: deleteActiveConversation, reload, reloadModelConfiguration,
    createModelProvider: (command) => runModelConfigurationCommand(apiRef.current.createModelProvider === undefined ? undefined : () => apiRef.current.createModelProvider!(command), '模型 Provider 接口未配置'),
    createModelGroup: (command) => runModelConfigurationCommand(apiRef.current.createModelGroup === undefined ? undefined : () => apiRef.current.createModelGroup!(command), '模型组接口未配置'),
    createModelEndpoint: (command) => runModelConfigurationCommand(apiRef.current.createModelEndpoint === undefined ? undefined : () => apiRef.current.createModelEndpoint!(command), '模型端点接口未配置'),
    updateModelProvider: (id, command) => runModelConfigurationCommand(apiRef.current.updateModelProvider === undefined ? undefined : () => apiRef.current.updateModelProvider!(id, command), '更新模型 Provider 接口未配置'),
    updateModelEndpoint: (id, command) => runModelConfigurationCommand(apiRef.current.updateModelEndpoint === undefined ? undefined : () => apiRef.current.updateModelEndpoint!(id, command), '更新模型端点接口未配置'),
    updateModelGroup: (id, command) => runModelConfigurationCommand(apiRef.current.updateModelGroup === undefined ? undefined : () => apiRef.current.updateModelGroup!(id, command), '更新模型组接口未配置'),
    deleteModelProvider: (id) => runModelConfigurationCommand(apiRef.current.deleteModelProvider === undefined ? undefined : () => apiRef.current.deleteModelProvider!(id), '删除模型 Provider 接口未配置'),
    deleteModelEndpoint: (id) => runModelConfigurationCommand(apiRef.current.deleteModelEndpoint === undefined ? undefined : () => apiRef.current.deleteModelEndpoint!(id), '删除模型端点接口未配置'),
    deleteModelGroup: (id) => runModelConfigurationCommand(apiRef.current.deleteModelGroup === undefined ? undefined : () => apiRef.current.deleteModelGroup!(id), '删除模型组接口未配置'),
    modelConfiguration,
  }
}
