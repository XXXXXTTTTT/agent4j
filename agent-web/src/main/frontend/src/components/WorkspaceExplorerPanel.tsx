import { FileCode2, Folder, RefreshCw, Save } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'

import type { WorkspaceFileContent, WorkspaceFileEntry } from '../api/contracts'
import { listWorkspaceFiles, readWorkspaceFile, writeWorkspaceFile } from '../api/workspaceFilesApi'
import { Editor } from '../monaco/MonacoEditors'
import { useAppearance } from '../appearance/AppearanceProvider'
import { getEditorTheme } from '../appearance/editorTheme'

interface Props {
  workspaceId: string
}

/** 在工作台项目活动栏中浏览和编辑当前工作区文本文件。 */
export function WorkspaceExplorerPanel({ workspaceId }: Props) {
  const { resolvedColorMode } = useAppearance()
  const [entries, setEntries] = useState<WorkspaceFileEntry[]>([])
  const [activePath, setActivePath] = useState('')
  const [file, setFile] = useState<WorkspaceFileContent | null>(null)
  const [draft, setDraft] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(async () => {
    setLoading(true); setError(null)
    try { setEntries(await listWorkspaceFiles(workspaceId)) } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)) } finally { setLoading(false) }
  }, [workspaceId])

  useEffect(() => { void reload() }, [reload])

  async function open(entry: WorkspaceFileEntry): Promise<void> {
    if (entry.kind === 'DIRECTORY') return
    setActivePath(entry.path); setError(null)
    try { const value = await readWorkspaceFile(workspaceId, entry.path); setFile(value); setDraft(value.content) } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)) }
  }

  async function save(): Promise<void> {
    if (file === null || saving) return
    setSaving(true); setError(null)
    try { const value = await writeWorkspaceFile(workspaceId, file.path, draft, file.sha256); setFile(value); setDraft(value.content) } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)) } finally { setSaving(false) }
  }

  function moveTreeFocus(event: React.KeyboardEvent<HTMLDivElement>): void {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
    const buttons = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('[data-path]'))
    const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
    if (index < 0) return
    const next = event.key === 'ArrowDown' ? buttons[index + 1] : buttons[index - 1]
    if (next !== undefined) { event.preventDefault(); next.focus() }
  }

  return <aside className="workspace-explorer" aria-label="项目资源">
    <header className="workspace-explorer-header"><div><span>EXPLORER</span><strong>项目文件</strong></div><button type="button" className="icon-button" aria-label="刷新项目文件" title="刷新项目文件" onClick={() => void reload()} disabled={loading}><RefreshCw size={14} /></button></header>
    <div className="workspace-explorer-tree" role="tree" aria-label="项目文件" onKeyDown={moveTreeFocus}>
      {loading ? <p className="empty-tool-state">正在读取项目文件</p> : null}
      {!loading && entries.length === 0 ? <p className="empty-tool-state">项目目录为空</p> : null}
      {entries.map((entry) => <button key={entry.path} type="button" role="treeitem" data-path={entry.path} aria-selected={activePath === entry.path} aria-expanded={entry.kind === 'DIRECTORY' ? false : undefined} className={`workspace-file-row ${activePath === entry.path ? 'is-active' : ''}`} onClick={() => void open(entry)}>
        {entry.kind === 'DIRECTORY' ? <Folder aria-hidden="true" size={15} /> : <FileCode2 aria-hidden="true" size={15} />}<span>{entry.name}</span>
      </button>)}
    </div>
    <section className="workspace-explorer-editor" aria-label="文件编辑">
      <div className="workspace-explorer-editor-bar"><code>{file?.path ?? '选择一个文本文件'}</code><button type="button" className="icon-button" aria-label="保存文件" title="保存文件" onClick={() => void save()} disabled={file === null || saving || draft === file.content}><Save size={14} /></button></div>
      {file === null ? <div className="empty-tool-state">从文件树选择文本文件</div> : <Editor height="100%" value={draft} language="java" theme={getEditorTheme(resolvedColorMode)} onChange={(value) => setDraft(value ?? '')} options={{ minimap: { enabled: false }, automaticLayout: true, scrollBeyondLastLine: false, fontSize: 13 }} />}
    </section>
    {error === null ? null : <p className="workspace-explorer-error" role="alert">{error}</p>}
  </aside>
}
