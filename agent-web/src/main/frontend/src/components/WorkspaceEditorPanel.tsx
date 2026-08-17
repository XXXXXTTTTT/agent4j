import { FileCode2, Save, SaveAll, X } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'

import type { WorkspaceFileContent } from '../api/contracts'
import { readWorkspaceFile, writeWorkspaceFile } from '../api/workspaceFilesApi'
import { useAppearance } from '../appearance/AppearanceProvider'
import { getEditorTheme } from '../appearance/editorTheme'
import { Editor } from '../monaco/MonacoEditors'

export interface WorkspaceEditorSnapshot {
  files: Record<string, WorkspaceFileContent>
  drafts: Record<string, string>
  loadedRevisions?: Record<string, number>
}

interface Props {
  workspaceId: string
  activationRevision?: number
  openPaths: string[]
  activePath: string | null
  onActivate(path: string): void
  onClose(path: string, discardDraft?: boolean): void
  snapshot?: WorkspaceEditorSnapshot | null
  onSnapshotChange?(snapshot: WorkspaceEditorSnapshot): void
  onFileSaved?(workspaceId: string, file: WorkspaceFileContent, savedDraft: string, latestDraft: string, activationRevision: number): void
}

interface ClosePrompt {
  paths: string[]
}

function fileName(path: string): string {
  const separator = path.lastIndexOf('/')
  return separator < 0 ? path : path.slice(separator + 1)
}

function languageForPath(path: string): string {
  const extension = path.slice(path.lastIndexOf('.') + 1).toLowerCase()
  const languages: Record<string, string> = {
    css: 'css', html: 'html', js: 'javascript', json: 'json', md: 'markdown', py: 'python', ts: 'typescript', tsx: 'typescript', xml: 'xml', yml: 'yaml', yaml: 'yaml',
  }
  return languages[extension] ?? 'plaintext'
}

/** 中心文件编辑器：管理标签、草稿、保存和关闭确认。 */
export function WorkspaceEditorPanel({ workspaceId, activationRevision = 0, openPaths, activePath, onActivate, onClose, snapshot, onSnapshotChange, onFileSaved }: Props) {
  const { resolvedColorMode } = useAppearance()
  const [files, setFiles] = useState<Record<string, WorkspaceFileContent>>(() => snapshot?.files ?? {})
  const [drafts, setDrafts] = useState<Record<string, string>>(() => snapshot?.drafts ?? {})
  const [loadedRevisions, setLoadedRevisions] = useState<Record<string, number>>(() => snapshot?.loadedRevisions ?? {})
  const [stateWorkspaceId, setStateWorkspaceId] = useState(workspaceId)
  const [workspaceGeneration, setWorkspaceGeneration] = useState(0)
  const [loadingPaths, setLoadingPaths] = useState<Set<string>>(new Set())
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [closePrompt, setClosePrompt] = useState<ClosePrompt | null>(null)
  const filesRef = useRef(files)
  const draftsRef = useRef(drafts)
  const loadedRevisionsRef = useRef(loadedRevisions)
  const workspaceIdRef = useRef(workspaceId)
  const workspaceGenerationRef = useRef(0)
  const requestedPathsKey = openPaths.join('\u0000')

  if (workspaceIdRef.current !== workspaceId) {
    workspaceIdRef.current = workspaceId
    workspaceGenerationRef.current += 1
  }

  function isCurrentWorkspace(requestWorkspaceId: string, requestGeneration: number): boolean {
    return workspaceIdRef.current === requestWorkspaceId && workspaceGenerationRef.current === requestGeneration
  }

  function applyLoadedFiles(values: WorkspaceFileContent[], loadedRevision: number): void {
    const nextFiles = { ...filesRef.current }
    const nextDrafts = { ...draftsRef.current }
    const nextLoadedRevisions = { ...loadedRevisionsRef.current }
    for (const value of values) {
      const previousFile = nextFiles[value.path]
      const previousDraft = nextDrafts[value.path]
      nextFiles[value.path] = value
      if (previousFile === undefined || previousDraft === undefined || previousDraft === previousFile.content) nextDrafts[value.path] = value.content
      nextLoadedRevisions[value.path] = loadedRevision
    }
    filesRef.current = nextFiles
    draftsRef.current = nextDrafts
    loadedRevisionsRef.current = nextLoadedRevisions
    setFiles(nextFiles)
    setDrafts(nextDrafts)
    setLoadedRevisions(nextLoadedRevisions)
  }

  function applySavedFile(value: WorkspaceFileContent, savedDraft: string, loadedRevision: number): void {
    const nextFiles = { ...filesRef.current, [value.path]: value }
    const currentDraft = draftsRef.current[value.path]
    const nextDrafts = {
      ...draftsRef.current,
      [value.path]: currentDraft === savedDraft ? value.content : currentDraft ?? value.content,
    }
    filesRef.current = nextFiles
    draftsRef.current = nextDrafts
    const nextLoadedRevisions = { ...loadedRevisionsRef.current, [value.path]: loadedRevision }
    loadedRevisionsRef.current = nextLoadedRevisions
    setFiles(nextFiles)
    setDrafts(nextDrafts)
    setLoadedRevisions(nextLoadedRevisions)
  }

  function updateDraft(path: string, value: string): void {
    const nextDrafts = { ...draftsRef.current, [path]: value }
    draftsRef.current = nextDrafts
    setDrafts(nextDrafts)
  }

  function discardPaths(paths: string[]): void {
    const nextFiles = { ...filesRef.current }
    const nextDrafts = { ...draftsRef.current }
    const nextLoadedRevisions = { ...loadedRevisionsRef.current }
    for (const path of paths) {
      delete nextFiles[path]
      delete nextDrafts[path]
      delete nextLoadedRevisions[path]
    }
    filesRef.current = nextFiles
    draftsRef.current = nextDrafts
    loadedRevisionsRef.current = nextLoadedRevisions
    setFiles(nextFiles)
    setDrafts(nextDrafts)
    setLoadedRevisions(nextLoadedRevisions)
    paths.forEach((path) => onClose(path, true))
  }

  useEffect(() => {
    if (stateWorkspaceId === workspaceId) return
    const restoredFiles = snapshot?.files ?? {}
    const restoredDrafts = snapshot?.drafts ?? {}
    const restoredLoadedRevisions = snapshot?.loadedRevisions ?? {}
    filesRef.current = restoredFiles
    draftsRef.current = restoredDrafts
    loadedRevisionsRef.current = restoredLoadedRevisions
    setFiles(restoredFiles)
    setDrafts(restoredDrafts)
    setLoadedRevisions(restoredLoadedRevisions)
    setLoadingPaths(new Set())
    setSaving(false)
    setClosePrompt(null)
    setError(null)
    setWorkspaceGeneration(workspaceGenerationRef.current)
    setStateWorkspaceId(workspaceId)
  }, [snapshot, stateWorkspaceId, workspaceId])

  useEffect(() => {
    if (stateWorkspaceId !== workspaceId) return undefined
    const requestWorkspaceId = workspaceId
    const requestGeneration = workspaceGeneration
    let cancelled = false
    const missing = openPaths.filter((path) => {
      // 组件在同一挂载周期内切换工作区时，即使调用方未提供激活版本也必须重新读取。
      // 同工作区关闭并重新打开 Dockview 面板则由 activationRevision 决定是否复用快照。
      const needsRefresh = workspaceGeneration > 0 || loadedRevisionsRef.current[path] !== activationRevision
      return !loadingPaths.has(path) && (files[path] === undefined || needsRefresh)
    })
    if (missing.length === 0) return undefined
    setLoadingPaths((paths) => new Set([...paths, ...missing]))
    void Promise.all(missing.map((path) => readWorkspaceFile(requestWorkspaceId, path)))
      .then((values) => {
        if (cancelled || !isCurrentWorkspace(requestWorkspaceId, requestGeneration)) return
        applyLoadedFiles(values, activationRevision)
      })
      .catch((failure) => {
        if (!cancelled && isCurrentWorkspace(requestWorkspaceId, requestGeneration)) setError(failure instanceof Error ? failure.message : String(failure))
      })
      .finally(() => {
        if (!cancelled && isCurrentWorkspace(requestWorkspaceId, requestGeneration)) setLoadingPaths((paths) => {
          const next = new Set(paths)
          missing.forEach((path) => next.delete(path))
          return next
        })
    })
    return () => { cancelled = true }
  }, [activationRevision, requestedPathsKey, stateWorkspaceId, workspaceGeneration, workspaceId])

  useEffect(() => {
    if (stateWorkspaceId !== workspaceId || !isCurrentWorkspace(workspaceId, workspaceGeneration)) return
    onSnapshotChange?.({ files, drafts, loadedRevisions })
  }, [drafts, files, loadedRevisions, onSnapshotChange, stateWorkspaceId, workspaceGeneration, workspaceId])

  const dirtyPaths = useMemo(
    () => openPaths.filter((path) => files[path] !== undefined && drafts[path] !== files[path].content),
    [drafts, files, openPaths],
  )
  const activeFile = activePath === null ? null : files[activePath] ?? null
  const activeDraft = activePath === null ? '' : drafts[activePath] ?? ''

  async function savePath(path: string, requestWorkspaceId: string, requestGeneration: number, requestActivationRevision: number): Promise<void> {
    const file = filesRef.current[path]
    if (file === undefined) return
    const savedDraft = draftsRef.current[path] ?? file.content
    const updated = await writeWorkspaceFile(requestWorkspaceId, path, savedDraft, file.sha256)
    const latestDraft = draftsRef.current[path] ?? savedDraft
    onFileSaved?.(requestWorkspaceId, updated, savedDraft, latestDraft, requestActivationRevision)
    if (!isCurrentWorkspace(requestWorkspaceId, requestGeneration)) return
    applySavedFile(updated, savedDraft, requestActivationRevision)
  }

  async function saveActive(): Promise<void> {
    if (activePath === null || saving) return
    const requestWorkspaceId = workspaceId
    const requestGeneration = workspaceGenerationRef.current
    setSaving(true)
    setError(null)
    try {
      await savePath(activePath, requestWorkspaceId, requestGeneration, activationRevision)
    } catch (failure) {
      if (isCurrentWorkspace(requestWorkspaceId, requestGeneration)) setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      if (isCurrentWorkspace(requestWorkspaceId, requestGeneration)) setSaving(false)
    }
  }

  async function saveAll(): Promise<void> {
    if (saving || dirtyPaths.length === 0) return
    const requestWorkspaceId = workspaceId
    const requestGeneration = workspaceGenerationRef.current
    setSaving(true)
    setError(null)
    try {
      const results = await Promise.allSettled(dirtyPaths.map((path) => savePath(path, requestWorkspaceId, requestGeneration, activationRevision)))
      if (!isCurrentWorkspace(requestWorkspaceId, requestGeneration)) return
      const failures = results.flatMap((result, index) => result.status === 'rejected'
        ? [`${dirtyPaths[index]}：${result.reason instanceof Error ? result.reason.message : String(result.reason)}`]
        : [])
      if (failures.length > 0) setError(`保存失败：${failures.join('；')}`)
    } catch (failure) {
      if (isCurrentWorkspace(requestWorkspaceId, requestGeneration)) setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      if (isCurrentWorkspace(requestWorkspaceId, requestGeneration)) setSaving(false)
    }
  }

  function requestClose(path: string): void {
    if (dirtyPaths.includes(path)) setClosePrompt({ paths: [path] })
    else onClose(path)
  }

  function requestCloseAll(): void {
    if (dirtyPaths.length > 0) setClosePrompt({ paths: [...openPaths] })
    else openPaths.forEach((path) => onClose(path))
  }

  async function saveAndClose(): Promise<void> {
    if (closePrompt === null) return
    const requestWorkspaceId = workspaceId
    const requestGeneration = workspaceGenerationRef.current
    setSaving(true)
    setError(null)
    try {
      for (const path of closePrompt.paths) await savePath(path, requestWorkspaceId, requestGeneration, activationRevision)
      if (!isCurrentWorkspace(requestWorkspaceId, requestGeneration)) return
      closePrompt.paths.forEach((path) => onClose(path))
      setClosePrompt(null)
    } catch (failure) {
      if (isCurrentWorkspace(requestWorkspaceId, requestGeneration)) setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      if (isCurrentWorkspace(requestWorkspaceId, requestGeneration)) setSaving(false)
    }
  }

  return <section className="workspace-editor-panel" aria-label="文件编辑器">
    <header className="workspace-editor-toolbar">
      <div className="workspace-editor-title"><FileCode2 aria-hidden="true" size={16} /><span>文件编辑器</span></div>
      <button type="button" className="icon-button" aria-label="全部关闭" title="全部关闭" onClick={requestCloseAll} disabled={openPaths.length === 0}><X size={15} /></button>
    </header>
    <div className="workspace-editor-tabs" role="tablist" aria-label="打开的文件">
      {stateWorkspaceId !== workspaceId ? null : openPaths.map((path) => {
        const dirty = dirtyPaths.includes(path)
        const label = `${fileName(path)}${dirty ? ' 未保存' : ''}`
        return <div key={path} role="tab" aria-label={label} aria-selected={activePath === path} className={`workspace-editor-tab ${activePath === path ? 'is-active' : ''}`}>
          <button type="button" className="workspace-editor-tab-label" onClick={() => onActivate(path)} aria-label={label}>{fileName(path)}{dirty ? <span aria-hidden="true"> ·</span> : null}</button>
          <button type="button" className="icon-button workspace-editor-tab-close" aria-label={`关闭 ${fileName(path)}`} title={`关闭 ${fileName(path)}`} onClick={() => requestClose(path)}><X size={13} /></button>
        </div>
      })}
    </div>
    <div className="workspace-editor-content" role="tabpanel" aria-label={activePath === null ? '未选择文件' : fileName(activePath)}>
      {stateWorkspaceId !== workspaceId ? <div className="empty-tool-state">正在切换工作区</div> : activePath === null ? <div className="empty-tool-state">从项目文件中打开一个文本文件</div> : loadingPaths.has(activePath) ? <div className="empty-tool-state">正在读取 {fileName(activePath)}</div> : activeFile === null ? <div className="empty-tool-state">文件读取失败</div> : <Editor height="100%" value={activeDraft} language={languageForPath(activeFile.path)} theme={getEditorTheme(resolvedColorMode)} onChange={(value) => updateDraft(activeFile.path, value ?? '')} options={{ minimap: { enabled: true }, automaticLayout: true, scrollBeyondLastLine: false, fontSize: 13 }} />}
    </div>
    <footer className="workspace-editor-statusbar">
      {error === null ? <span>{activePath ?? '未打开文件'}</span> : <span className="workspace-editor-error" role="alert">{error}</span>}
      <button type="button" className="icon-button" aria-label="保存文件" title="保存文件" onClick={() => void saveActive()} disabled={stateWorkspaceId !== workspaceId || activeFile === null || !dirtyPaths.includes(activePath ?? '') || saving}><Save size={14} /></button>
      <button type="button" className="icon-button" aria-label="保存全部" title="保存全部" onClick={() => void saveAll()} disabled={stateWorkspaceId !== workspaceId || dirtyPaths.length === 0 || saving}><SaveAll size={14} /></button>
    </footer>
    {closePrompt === null ? null : <div className="dialog-backdrop" role="presentation"><section className="workspace-editor-close-dialog" role="dialog" aria-modal="true" aria-labelledby="workspace-editor-close-title" aria-label="未保存文件"><h2 id="workspace-editor-close-title">未保存文件</h2><p>{closePrompt.paths.map(fileName).join('、')} 有未保存修改。</p><div className="workspace-dialog-actions"><button type="button" className="secondary-command" onClick={() => setClosePrompt(null)}>取消</button><button type="button" className="reject-command" onClick={() => { discardPaths(closePrompt.paths); setClosePrompt(null) }}>放弃并关闭</button><button type="button" className="primary-command" onClick={() => void saveAndClose()} disabled={saving}>保存并关闭</button></div></section></div>}
  </section>
}
