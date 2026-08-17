import { FileCode2, Folder, FolderOpen, RefreshCw } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import type { WorkspaceFileEntry } from '../api/contracts'
import { listWorkspaceFiles } from '../api/workspaceFilesApi'

interface Props {
  workspaceId: string
  onOpenFile(path: string): void
}

type DirectoryEntries = Record<string, WorkspaceFileEntry[]>

function parentPath(path: string): string {
  const separator = path.lastIndexOf('/')
  return separator < 0 ? '' : path.slice(0, separator)
}

/** 在工作台项目活动栏中浏览和编辑当前工作区文本文件。 */
export function WorkspaceExplorerPanel({ workspaceId, onOpenFile }: Props) {
  const [entriesByDirectory, setEntriesByDirectory] = useState<DirectoryEntries>({})
  const [expandedDirectories, setExpandedDirectories] = useState<Set<string>>(new Set())
  const [loadingDirectories, setLoadingDirectories] = useState<Set<string>>(new Set())
  const [activePath, setActivePath] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const requestGenerationRef = useRef(0)
  const currentWorkspaceIdRef = useRef(workspaceId)
  currentWorkspaceIdRef.current = workspaceId

  const loadDirectory = useCallback(async (path: string, requestGeneration: number): Promise<WorkspaceFileEntry[]> => {
    const isCurrentRequest = () => currentWorkspaceIdRef.current === workspaceId && requestGenerationRef.current === requestGeneration
    if (isCurrentRequest()) setLoadingDirectories((items) => new Set(items).add(path))
    try {
      const loaded = await listWorkspaceFiles(workspaceId, path)
      if (isCurrentRequest()) setEntriesByDirectory((items) => ({ ...items, [path]: loaded }))
      return loaded
    } finally {
      if (isCurrentRequest()) {
        setLoadingDirectories((items) => {
          const next = new Set(items)
          next.delete(path)
          return next
        })
      }
    }
  }, [workspaceId])

  const reload = useCallback(async () => {
    const requestGeneration = ++requestGenerationRef.current
    const isCurrentRequest = () => currentWorkspaceIdRef.current === workspaceId && requestGenerationRef.current === requestGeneration
    setLoading(true)
    setError(null)
    setEntriesByDirectory({})
    setExpandedDirectories(new Set())
    setLoadingDirectories(new Set())
    try {
      await loadDirectory('', requestGeneration)
    } catch (failure) {
      if (isCurrentRequest()) setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      if (isCurrentRequest()) setLoading(false)
    }
  }, [loadDirectory, workspaceId])

  useEffect(() => { void reload() }, [reload])

  async function toggleDirectory(path: string): Promise<WorkspaceFileEntry[]> {
    const requestGeneration = requestGenerationRef.current
    const isCurrentRequest = () => currentWorkspaceIdRef.current === workspaceId && requestGenerationRef.current === requestGeneration
    if (expandedDirectories.has(path)) {
      setExpandedDirectories((items) => {
        const next = new Set(items)
        next.delete(path)
        return next
      })
      return entriesByDirectory[path] ?? []
    }
    setError(null)
    try {
      const loaded = entriesByDirectory[path] ?? await loadDirectory(path, requestGeneration)
      if (!isCurrentRequest()) return []
      setExpandedDirectories((items) => new Set(items).add(path))
      return loaded
    } catch (failure) {
      if (isCurrentRequest()) setError(failure instanceof Error ? failure.message : String(failure))
      return []
    }
  }

  function open(entry: WorkspaceFileEntry): void {
    if (entry.kind === 'DIRECTORY') {
      void toggleDirectory(entry.path)
      return
    }
    setActivePath(entry.path)
    setError(null)
    onOpenFile(entry.path)
  }

  const visibleEntries = useMemo(() => {
    const rows: Array<{ entry: WorkspaceFileEntry; level: number }> = []
    const visit = (directory: string, level: number, ancestors = new Set<string>()): void => {
      if (ancestors.has(directory)) return
      const nextAncestors = new Set(ancestors).add(directory)
      for (const entry of entriesByDirectory[directory] ?? []) {
        rows.push({ entry, level })
        if (entry.kind === 'DIRECTORY' && expandedDirectories.has(entry.path)) visit(entry.path, level + 1, nextAncestors)
      }
    }
    visit('', 0)
    return rows
  }, [entriesByDirectory, expandedDirectories])

  function focusVisibleTreeItem(path: string): void {
    const item = Array.from(document.querySelectorAll<HTMLButtonElement>('[data-path]')).find((button) => button.dataset.path === path)
    item?.focus()
  }

  async function handleTreeKeyDown(event: React.KeyboardEvent<HTMLDivElement>): Promise<void> {
    const current = document.activeElement as HTMLButtonElement | null
    if (current?.dataset.path === undefined) return
    const currentEntry = visibleEntries.find(({ entry }) => entry.path === current.dataset.path)?.entry
    if (currentEntry === undefined) return

    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      const index = visibleEntries.findIndex(({ entry }) => entry.path === currentEntry.path)
      const next = event.key === 'ArrowDown' ? visibleEntries[index + 1] : visibleEntries[index - 1]
      if (next !== undefined) {
        event.preventDefault()
        focusVisibleTreeItem(next.entry.path)
      }
      return
    }

    if (event.key === 'Enter' || event.key === ' ') {
      if (currentEntry.kind === 'DIRECTORY') {
        event.preventDefault()
        await toggleDirectory(currentEntry.path)
      }
      return
    }

    if (event.key === 'ArrowRight' && currentEntry.kind === 'DIRECTORY') {
      event.preventDefault()
      if (!expandedDirectories.has(currentEntry.path)) {
        const children = await toggleDirectory(currentEntry.path)
        if (children[0] !== undefined) requestAnimationFrame(() => focusVisibleTreeItem(children[0].path))
      } else {
        const firstChild = entriesByDirectory[currentEntry.path]?.[0]
        if (firstChild !== undefined) focusVisibleTreeItem(firstChild.path)
      }
      return
    }

    if (event.key === 'ArrowLeft') {
      event.preventDefault()
      if (currentEntry.kind === 'DIRECTORY' && expandedDirectories.has(currentEntry.path)) {
        await toggleDirectory(currentEntry.path)
        focusVisibleTreeItem(currentEntry.path)
      } else {
        const parent = parentPath(currentEntry.path)
        if (expandedDirectories.has(parent)) await toggleDirectory(parent)
        focusVisibleTreeItem(parent)
      }
    }
  }

  const rootEntries = entriesByDirectory[''] ?? []
  return <aside className="workspace-explorer" aria-label="项目资源">
    <header className="workspace-explorer-header"><div><span>EXPLORER</span><strong>项目文件</strong></div><button type="button" className="icon-button" aria-label="刷新项目文件" title="刷新项目文件" onClick={() => void reload()} disabled={loading}><RefreshCw size={14} /></button></header>
    <div className="workspace-explorer-tree" role="tree" aria-label="项目文件" onKeyDown={(event) => void handleTreeKeyDown(event)}>
      {loading ? <p className="empty-tool-state">正在读取项目文件</p> : null}
      {!loading && rootEntries.length === 0 ? <p className="empty-tool-state">项目目录为空</p> : null}
      {visibleEntries.map(({ entry, level }) => {
        const directory = entry.kind === 'DIRECTORY'
        const expanded = directory && expandedDirectories.has(entry.path)
        const busy = directory && loadingDirectories.has(entry.path)
        return <button key={entry.path} type="button" role="treeitem" data-path={entry.path} aria-selected={activePath === entry.path} aria-expanded={directory ? expanded : undefined} aria-level={level + 1} className={`workspace-file-row ${activePath === entry.path ? 'is-active' : ''}`} style={{ paddingLeft: `${10 + level * 16}px` }} onClick={() => open(entry)} disabled={busy}>
          {directory ? (expanded ? <FolderOpen aria-hidden="true" size={15} /> : <Folder aria-hidden="true" size={15} />) : <FileCode2 aria-hidden="true" size={15} />}<span>{entry.name}</span>
        </button>
      })}
    </div>
    {error === null ? null : <p className="workspace-explorer-error" role="alert">{error}</p>}
  </aside>
}
