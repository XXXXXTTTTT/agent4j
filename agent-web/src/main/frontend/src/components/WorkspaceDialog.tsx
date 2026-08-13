import { ArrowLeft, ChevronRight, FolderOpen, FolderPlus, LoaderCircle, Upload, X } from 'lucide-react'
import { FormEvent, useEffect, useMemo, useState } from 'react'

import type { CreateWorkspaceCommand, ImportDesktopWorkspaceCommand, ImportWorkspaceCommand } from '../api/conversationApi'
import type { WorkspaceDirectoryListing } from '../api/contracts'

interface WorkspaceDialogProps {
  createWorkspace(command: CreateWorkspaceCommand): Promise<void>
  browseWorkspaceDirectories?(path: string): Promise<WorkspaceDirectoryListing>
  importWorkspace?(command: ImportWorkspaceCommand): Promise<void>
  importDesktopWorkspace?(command: ImportDesktopWorkspaceCommand): Promise<void>
  onClose(): void
}

type WorkspaceSource = 'mounted' | 'import'

/** 选择受控挂载目录或导入浏览器选择的外部项目文件夹。 */
export function WorkspaceDialog({ createWorkspace, browseWorkspaceDirectories, importWorkspace, importDesktopWorkspace, onClose }: WorkspaceDialogProps) {
  const [source, setSource] = useState<WorkspaceSource>('mounted')
  const [displayName, setDisplayName] = useState('')
  const [workspacePath, setWorkspacePath] = useState(() => browseWorkspaceDirectories === undefined ? '' : '/agent-workspace')
  const [repositoryId, setRepositoryId] = useState('')
  const [directory, setDirectory] = useState<WorkspaceDirectoryListing | null>(null)
  const [directoryLoading, setDirectoryLoading] = useState(false)
  const [files, setFiles] = useState<File[]>([])
  const [desktopArchive, setDesktopArchive] = useState<Agent4jDesktopProjectArchive | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<Error | null>(null)

  useEffect(() => {
    if (browseWorkspaceDirectories === undefined || source !== 'mounted') return
    let disposed = false
    setDirectoryLoading(true)
    void browseWorkspaceDirectories(workspacePath)
      .then((listing) => {
        if (!disposed) {
          setDirectory(listing)
          setWorkspacePath(listing.currentPath)
        }
      })
      .catch((failure) => {
        if (!disposed) setError(failure instanceof Error ? failure : new Error(String(failure)))
      })
      .finally(() => {
        if (!disposed) setDirectoryLoading(false)
      })
    return () => { disposed = true }
  }, [browseWorkspaceDirectories, source])

  const fileBytes = useMemo(() => files.reduce((total, file) => total + file.size, 0), [files])

  async function submit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    const name = displayName.trim()
    const repository = repositoryId.trim()
    if (name.length === 0 || repository.length === 0) {
      setError(new Error('请填写完整的工作区信息'))
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      if (source === 'import') {
        if (desktopArchive === null && files.length === 0) {
          setError(new Error('请选择要导入的文件夹'))
          return
        }
        if (desktopArchive !== null) {
          if (importDesktopWorkspace === undefined) throw new Error('桌面工作区导入接口未配置')
          await importDesktopWorkspace({ displayName: name, repositoryId: repository, archive: desktopArchive.archive })
        } else {
          if (importWorkspace === undefined) throw new Error('工作区导入接口未配置')
          await importWorkspace({ displayName: name, repositoryId: repository, files })
        }
      } else {
        const command = { displayName: name, workspacePath: workspacePath.trim(), repositoryId: repository }
        if (command.workspacePath.length === 0) {
          setError(new Error('请选择工作区目录'))
          return
        }
        await createWorkspace(command)
      }
      onClose()
    } catch (failure) {
      setError(failure instanceof Error ? failure : new Error(String(failure)))
    } finally {
      setSubmitting(false)
    }
  }

  function selectDirectory(path: string): void {
    setWorkspacePath(path)
    setError(null)
    if (browseWorkspaceDirectories === undefined) return
    setDirectoryLoading(true)
    void browseWorkspaceDirectories(path)
      .then((listing) => {
        setDirectory(listing)
        setWorkspacePath(listing.currentPath)
      })
      .catch((failure) => setError(failure instanceof Error ? failure : new Error(String(failure))))
      .finally(() => setDirectoryLoading(false))
  }

  async function selectDesktopProject(): Promise<void> {
    if (window.agent4jDesktop === undefined) return
    setError(null)
    try {
      const selected = await window.agent4jDesktop.selectProjectArchive()
      if (selected === null) return
      setDesktopArchive(selected)
      if (displayName.trim().length === 0) setDisplayName(selected.suggestedDisplayName)
    } catch (failure) {
      setError(failure instanceof Error ? failure : new Error(String(failure)))
    }
  }

  return (
    <div className="workspace-dialog-backdrop" role="presentation">
      <section className="workspace-dialog" role="dialog" aria-modal="true" aria-labelledby="workspace-dialog-title">
        <header className="workspace-dialog-heading">
          <div>
            <span className="section-kicker">PROJECT SPACE</span>
            <h2 id="workspace-dialog-title"><FolderPlus aria-hidden="true" size={17} />新建工作区</h2>
          </div>
          <button type="button" className="icon-button" aria-label="关闭" title="关闭" onClick={onClose} disabled={submitting}>
            <X aria-hidden="true" size={17} />
          </button>
        </header>
        <div className="workspace-source-tabs" role="tablist" aria-label="工作区来源">
          <button type="button" role="tab" aria-selected={source === 'mounted'} onClick={() => setSource('mounted')} disabled={submitting}>
            <FolderOpen aria-hidden="true" size={14} />选择已挂载项目
          </button>
          <button type="button" role="tab" aria-selected={source === 'import'} onClick={() => setSource('import')} disabled={submitting}>
            <Upload aria-hidden="true" size={14} />导入本地文件夹
          </button>
        </div>
        <form onSubmit={(event) => void submit(event)}>
          <label className="field-label" htmlFor="workspace-display-name">工作区名称</label>
          <input id="workspace-display-name" value={displayName} onChange={(event) => setDisplayName(event.target.value)} autoFocus required />
          {source === 'mounted' ? (
            <>
              <label className="field-label" htmlFor="workspace-path">工作区路径</label>
              <input id="workspace-path" value={workspacePath} onChange={(event) => setWorkspacePath(event.target.value)} placeholder="/agent-workspace/project" required />
              <p className="workspace-dialog-hint">路径必须位于当前 Agent 挂载根内。</p>
              {directory === null ? null : (
                <div className="workspace-directory-browser" aria-label="已挂载目录">
                  <div className="workspace-directory-toolbar">
                    <button type="button" className="icon-button" aria-label="返回上级目录" title="返回上级目录" disabled={directory.parentPath === null || directoryLoading || submitting} onClick={() => directory.parentPath === null ? undefined : selectDirectory(directory.parentPath)}>
                      <ArrowLeft aria-hidden="true" size={14} />
                    </button>
                    <code>{directory.currentPath}</code>
                    {directoryLoading ? <LoaderCircle className="workspace-directory-spinner" aria-label="读取目录中" size={14} /> : null}
                  </div>
                  <div className="workspace-directory-list">
                    {directory.entries.length === 0 ? <span className="workspace-dialog-hint">当前目录没有子目录</span> : null}
                    {directory.entries.map((entry) => (
                      <button type="button" key={entry.path} onClick={() => selectDirectory(entry.path)} disabled={directoryLoading || submitting}>
                        <FolderOpen aria-hidden="true" size={14} />{entry.name}<ChevronRight aria-hidden="true" size={13} />
                      </button>
                    ))}
                  </div>
                </div>
              )}
            </>
          ) : (
            <>
              <label className="field-label" htmlFor="workspace-folder">本地项目文件夹</label>
              {window.agent4jDesktop === undefined ? (
                <>
                  <input
                    id="workspace-folder"
                    type="file"
                    multiple
                    {...({ webkitdirectory: 'true', directory: 'true' } as Record<string, string>)}
                    onChange={(event) => setFiles(Array.from(event.target.files ?? []))}
                    disabled={submitting}
                  />
                  <p className="workspace-dialog-hint">浏览器会将所选文件夹打包上传到 Agent 的受控导入目录，不会读取目录之外的文件。</p>
                  {files.length === 0 ? null : <p className="workspace-dialog-hint">已选择 {files.length} 个文件，共 {fileBytes.toLocaleString()} 字节</p>}
                </>
              ) : (
                <>
                  <button type="button" className="secondary-command" onClick={() => void selectDesktopProject()} disabled={submitting}>
                    <FolderOpen aria-hidden="true" size={15} />选择本地项目文件夹
                  </button>
                  <p className="workspace-dialog-hint">桌面端只上传安全归档，不向服务端传递本机文件夹路径。</p>
                  {desktopArchive === null ? null : <p className="workspace-dialog-hint">已选择 {desktopArchive.fileCount} 个文件，共 {desktopArchive.totalBytes.toLocaleString()} 字节</p>}
                </>
              )}
            </>
          )}
          <label className="field-label" htmlFor="workspace-repository-id">仓库标识</label>
          <input id="workspace-repository-id" value={repositoryId} onChange={(event) => setRepositoryId(event.target.value)} required />
          {error === null ? null : <p className="workspace-dialog-error" role="alert">{error.message}</p>}
          <footer className="workspace-dialog-actions">
            <button type="button" className="secondary-command" onClick={onClose} disabled={submitting}>取消</button>
            <button type="submit" className="primary-command workspace-dialog-submit" disabled={submitting}>
              {submitting ? <LoaderCircle className="workspace-directory-spinner" aria-hidden="true" size={15} /> : source === 'import' ? <Upload aria-hidden="true" size={15} /> : <FolderPlus aria-hidden="true" size={15} />}
              {submitting ? '导入中' : source === 'import' ? '导入并创建' : '创建工作区'}
            </button>
          </footer>
        </form>
      </section>
    </div>
  )
}
