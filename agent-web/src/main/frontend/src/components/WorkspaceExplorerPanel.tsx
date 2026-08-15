import { FileCode2, Folder, FolderOpen, RefreshCw, Save } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'

import type { WorkspaceFileContent, WorkspaceFileEntry } from '../api/contracts'
import { listWorkspaceFiles, readWorkspaceFile, writeWorkspaceFile } from '../api/workspaceFilesApi'
import { Editor } from '../monaco/MonacoEditors'
import { useAppearance } from '../appearance/AppearanceProvider'
import { getEditorTheme } from '../appearance/editorTheme'

interface Props {
  workspaceId: string
}

type DirectoryEntries = Record<string, WorkspaceFileEntry[]>

function parentPath(path: string): string {
  const separator = path.lastIndexOf('/')
  return separator < 0 ? '' : path.slice(0, separator)
}

/** 在工作台项目活动栏中浏览和编辑当前工作区文本文件。 */
export function WorkspaceExplorerPanel({ workspaceId }: Props) {
  const { resolvedColorMode } = useAppearance()
  const [entriesByDirectory, setEntriesByDirectory] = useState<DirectoryEntries>({})
  const [expandedDirectories, setExpandedDirectories] = useState<Set<string>>(new Set())
  const [loadingDirectories, setLoadingDirectories] = useState<Set<string>>(new Set())
  const [activePath, setActivePath] = useState('')
  const [file, setFile] = useState<WorkspaceFileContent | null>(null)
  const [draft, setDraft] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const loadDirectory = useCallback(async (path: string): Promise<WorkspaceFileEntry[]> => {
    setLoadingDirectories((items) => new Set(items).add(path))
    try {
      const loaded = await listWorkspaceFiles(workspaceId, path)
      setEntriesByDirectory((items) => ({ ...items, [path]: loaded }))
      return loaded
    } finally {
      setLoadingDirectories((items) => {
        const next = new Set(items)
        next.delete(path)
        return next
      })
    }
  }, [workspaceId])

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    setEntriesByDirectory({})
    setExpandedDirectories(new Set())
    try {
      await loadDirectory('')
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setLoading(false)
    }
  }, [loadDirectory])

  useEffect(() => { void reload() }, [reload])

  async function toggleDirectory(path: string): Promise<WorkspaceFileEntry[]> {
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
      const loaded = entriesByDirectory[path] ?? await loadDirectory(path)
      setExpandedDirectories((items) => new Set(items).add(path))
      return loaded
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
      return []
    }
  }

  async function open(entry: WorkspaceFileEntry): Promise<void> {
    if (entry.kind === 'DIRECTORY') {
      await toggleDirectory(entry.path)
      return
    }
    setActivePath(entry.path)
    setError(null)
    try {
      const value = await readWorkspaceFile(workspaceId, entry.path)
      setFile(value)
      setDraft(value.content)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    }
  }

  async function save(): Promise<void> {
    if (file === null || saving) return
    setSaving(true)
    setError(null)
    try {
      const value = await writeWorkspaceFile(workspaceId, file.path, draft, file.sha256)
      setFile(value)
      setDraft(value.content)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setSaving(false)
    }
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
        return <button key={entry.path} type="button" role="treeitem" data-path={entry.path} aria-selected={activePath === entry.path} aria-expanded={directory ? expanded : undefined} aria-level={level + 1} className={`workspace-file-row ${activePath === entry.path ? 'is-active' : ''}`} style={{ paddingLeft: `${10 + level * 16}px` }} onClick={() => void open(entry)} disabled={busy}>
          {directory ? (expanded ? <FolderOpen aria-hidden="true" size={15} /> : <Folder aria-hidden="true" size={15} />) : <FileCode2 aria-hidden="true" size={15} />}<span>{entry.name}</span>
        </button>
      })}
    </div>
    <section className="workspace-explorer-editor" aria-label="文件编辑">
      <div className="workspace-explorer-editor-bar"><code>{file?.path ?? '选择一个文本文件'}</code><button type="button" className="icon-button" aria-label="保存文件" title="保存文件" onClick={() => void save()} disabled={file === null || saving || draft === file.content}><Save size={14} /></button></div>
      {file === null ? <div className="empty-tool-state">从文件树选择文本文件</div> : <Editor height="100%" value={draft} language="java" theme={getEditorTheme(resolvedColorMode)} onChange={(value) => setDraft(value ?? '')} options={{ minimap: { enabled: false }, automaticLayout: true, scrollBeyondLastLine: false, fontSize: 13 }} />}
    </section>
    {error === null ? null : <p className="workspace-explorer-error" role="alert">{error}</p>}
  </aside>
}
