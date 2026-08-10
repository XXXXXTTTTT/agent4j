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
  searchConversations,
  submitConversationTurn,
} from '../api/conversationApi'
import type {
  Actor,
  Conversation,
  ConversationTurn,
  Workspace,
  WorkspaceDirectoryListing,
} from '../api/contracts'
import type { CreateWorkspaceCommand, ImportWorkspaceCommand } from '../api/conversationApi'

export interface ConversationWorkspaceApi {
  getIdentity(): Promise<Actor>
  listWorkspaces(): Promise<Workspace[]>
  listConversations(workspaceId: string): Promise<Conversation[]>
  searchConversations(workspaceId: string, query: string): Promise<Conversation[]>
  createWorkspace(command: CreateWorkspaceCommand): Promise<Workspace>
  browseWorkspaceDirectories?(path: string): Promise<WorkspaceDirectoryListing>
  importWorkspace?(command: ImportWorkspaceCommand): Promise<Workspace>
  createConversation(workspaceId: string): Promise<Conversation>
  submitConversationTurn(conversationId: string, command: { content: string; reviewerUrl?: string }): Promise<ConversationTurn>
  listConversationTurns(conversationId: string): Promise<ConversationTurn[]>
  archiveConversation(conversationId: string): Promise<Conversation>
}

const DEFAULT_API: ConversationWorkspaceApi = {
  getIdentity: () => getIdentity(),
  listWorkspaces: () => listWorkspaces(),
  listConversations: (workspaceId) => listConversations(workspaceId),
  searchConversations: (workspaceId, query) => searchConversations(workspaceId, query),
  createWorkspace: (command) => createWorkspace(command),
  browseWorkspaceDirectories: (path) => browseWorkspaceDirectories(path),
  importWorkspace: (command) => importWorkspace(command),
  createConversation: (workspaceId) => createConversation(workspaceId),
  submitConversationTurn: (conversationId, command) => submitConversationTurn(conversationId, command),
  listConversationTurns: (conversationId) => listConversationTurns(conversationId),
  archiveConversation: (conversationId) => archiveConversation(conversationId),
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
  loading: boolean
  submitting: boolean
  error: Error | null
  selectWorkspace(workspaceId: string): Promise<void>
  createWorkspace(command: CreateWorkspaceCommand): Promise<void>
  browseWorkspaceDirectories(path: string): Promise<WorkspaceDirectoryListing>
  importWorkspace(command: ImportWorkspaceCommand): Promise<void>
  selectConversation(conversationId: string): Promise<void>
  search(query: string): Promise<void>
  createConversation(): Promise<void>
  submit(content: string, reviewerUrl?: string): Promise<ConversationTurn>
  archive(): Promise<void>
  reload(): Promise<void>
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
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<Error | null>(null)
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
      ? await apiRef.current.listConversations(workspaceId)
      : await apiRef.current.searchConversations(workspaceId, query)
    if (operation !== conversationListOperationRef.current) return null
    setConversations(loaded)
    return loaded
  }, [])

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

  const submit = useCallback(async (content: string, reviewerUrl?: string): Promise<ConversationTurn> => {
    if (activeConversationId === null) throw new Error('当前没有会话')
    const exactContent = content.trim()
    if (exactContent.length === 0) throw new Error('消息内容不能为空')
    setSubmitting(true)
    setError(null)
    try {
      const created = await apiRef.current.submitConversationTurn(activeConversationId, { content: exactContent, ...(reviewerUrl?.trim() ? { reviewerUrl: reviewerUrl.trim() } : {}) })
      setTurns((items) => [...items.filter((item) => item.turnId !== created.turnId), created].sort((a, b) => a.turnIndex - b.turnIndex))
      return created
    } catch (failure) {
      setError(asError(failure))
      throw failure
    } finally {
      setSubmitting(false)
    }
  }, [activeConversationId])

  const archive = useCallback(async (): Promise<void> => {
    if (activeConversationId === null) throw new Error('当前没有会话')
    try {
      const archived = await apiRef.current.archiveConversation(activeConversationId)
      setConversations((items) => items.map((item) => item.conversationId === archived.conversationId ? archived : item))
      setActiveConversationId(null)
      setTurns([])
      writeSelection(activeWorkspaceId, null)
    } catch (failure) {
      setError(asError(failure))
      throw failure
    }
  }, [activeConversationId, activeWorkspaceId])

  useEffect(() => {
    let disposed = false
    async function initialize(): Promise<void> {
      setLoading(true)
      try {
        const [loadedIdentity, loadedWorkspaces] = await Promise.all([
          apiRef.current.getIdentity(), apiRef.current.listWorkspaces(),
        ])
        if (disposed) return
        setIdentity(loadedIdentity)
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
        const loadedConversations = await apiRef.current.listConversations(selectedWorkspace.workspaceId)
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
  }, [])

  return {
    identity, workspaces, activeWorkspace, conversations, activeConversation, turns, searchQuery,
    loading, submitting, error, selectWorkspace, selectConversation, search,
    createConversation: create, createWorkspace: createWorkspaceEntry,
    browseWorkspaceDirectories: browseWorkspaceDirectoryEntries,
    importWorkspace: importWorkspaceEntry,
    submit, archive, reload,
  }
}
