import { FolderPlus, X } from 'lucide-react'
import { FormEvent, useState } from 'react'

import type { CreateWorkspaceCommand } from '../api/conversationApi'

interface WorkspaceDialogProps {
  createWorkspace(command: CreateWorkspaceCommand): Promise<void>
  onClose(): void
}

/** 创建绑定当前 Agent 挂载根的工作区。 */
export function WorkspaceDialog({ createWorkspace, onClose }: WorkspaceDialogProps) {
  const [displayName, setDisplayName] = useState('')
  const [workspacePath, setWorkspacePath] = useState('')
  const [repositoryId, setRepositoryId] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<Error | null>(null)

  async function submit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    const command = {
      displayName: displayName.trim(),
      workspacePath: workspacePath.trim(),
      repositoryId: repositoryId.trim(),
    }
    if (Object.values(command).some((value) => value.length === 0)) {
      setError(new Error('请填写完整的工作区信息'))
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      await createWorkspace(command)
      onClose()
    } catch (failure) {
      setError(failure instanceof Error ? failure : new Error(String(failure)))
    } finally {
      setSubmitting(false)
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
        <form onSubmit={(event) => void submit(event)}>
          <label className="field-label" htmlFor="workspace-display-name">工作区名称</label>
          <input id="workspace-display-name" value={displayName} onChange={(event) => setDisplayName(event.target.value)} autoFocus required />
          <label className="field-label" htmlFor="workspace-path">工作区路径</label>
          <input id="workspace-path" value={workspacePath} onChange={(event) => setWorkspacePath(event.target.value)} placeholder="/agent-workspace/project" required />
          <p className="workspace-dialog-hint">路径必须位于当前 Agent 挂载根内。</p>
          <label className="field-label" htmlFor="workspace-repository-id">仓库标识</label>
          <input id="workspace-repository-id" value={repositoryId} onChange={(event) => setRepositoryId(event.target.value)} required />
          {error === null ? null : <p className="workspace-dialog-error" role="alert">{error.message}</p>}
          <footer className="workspace-dialog-actions">
            <button type="button" className="secondary-command" onClick={onClose} disabled={submitting}>取消</button>
            <button type="submit" className="primary-command workspace-dialog-submit" disabled={submitting}>
              <FolderPlus aria-hidden="true" size={15} />{submitting ? '创建中' : '创建工作区'}
            </button>
          </footer>
        </form>
      </section>
    </div>
  )
}
