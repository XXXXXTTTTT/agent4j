import { Archive, FolderGit2, FolderPlus, Palette, Plus, Search, Trash2, Settings } from 'lucide-react'
import { useState } from 'react'

import { AccountPlaceholder } from './AccountPlaceholder'
import { DesktopConnectionStatus } from './DesktopConnectionStatus'
import type { WorkbenchConnectionState } from '../hooks/useRunWorkbench'
import type { UseConversationWorkspaceResult } from '../hooks/useConversationWorkspace'
import { WorkspaceDialog } from './WorkspaceDialog'
import { ModelSettingsDialog } from './ModelSettingsDialog'
import { AppearanceSettingsDialog } from './AppearanceSettingsDialog'
import { ThemedSelect } from './ThemedSelect'

interface ConversationSidebarProps {
  controller: UseConversationWorkspaceResult
  connectionState: WorkbenchConnectionState
  activeContext?: string
}

/** 展示身份、工作区和服务端持久化会话列表。 */
export function ConversationSidebar({ controller, connectionState, activeContext }: ConversationSidebarProps) {
  const [dialogOpen, setDialogOpen] = useState(false)
  const [modelDialogOpen, setModelDialogOpen] = useState(false)
  const [appearanceDialogOpen, setAppearanceDialogOpen] = useState(false)
  const connected = connectionState.trace === WebSocket.OPEN && connectionState.terminal === WebSocket.OPEN
  const connectionLabel = connected ? '运行通道已连接' : '运行通道未连接'
  const connectionDetail = connected ? 'Trace 与 PTY 已连接' : '等待下一次任务运行'
  return (
    <aside className="conversation-sidebar" aria-label="会话与工作区" data-active-context={activeContext}>
      <AccountPlaceholder identity={controller.identity} />
      <DesktopConnectionStatus connected={connected} label={connectionLabel} detail={connectionDetail} />
      <button type="button" className="sidebar-model-settings" onClick={() => setAppearanceDialogOpen(true)}><Palette aria-hidden="true" size={14} /> 外观设置</button>
      <button type="button" className="sidebar-model-settings" onClick={() => setModelDialogOpen(true)}><Settings aria-hidden="true" size={14} /> 模型池配置</button>
      <label className="sidebar-label" htmlFor="workspace-select">工作区</label>
      <div className="sidebar-workspace-row">
        <div className="sidebar-select-wrap">
          <FolderGit2 aria-hidden="true" size={15} />
          <ThemedSelect
            id="workspace-select"
            label="工作区"
            value={controller.activeWorkspace?.workspaceId ?? ''}
            options={controller.workspaces.map((workspace) => ({ value: workspace.workspaceId, label: workspace.displayName }))}
            emptyLabel="没有可用工作区"
            disabled={controller.loading}
            onChange={(workspaceId) => void controller.selectWorkspace(workspaceId).catch(() => undefined)}
          />
        </div>
        <button type="button" className="sidebar-icon-button sidebar-workspace-add" aria-label="新建工作区" title="新建工作区" onClick={() => setDialogOpen(true)} disabled={controller.loading}>
          <FolderPlus aria-hidden="true" size={15} />
        </button>
      </div>
      {controller.activeWorkspace === null ? null : (
        <div className="sidebar-workspace-meta">
          <code>{controller.activeWorkspace.workspacePath}</code>
          <span>{controller.activeWorkspace.permission}</span>
        </div>
      )}
      <div className="sidebar-section-heading">
        <span>会话</span>
        <button type="button" className="sidebar-icon-button" aria-label="新建会话" title="新建会话" onClick={() => void controller.createConversation().catch(() => undefined)} disabled={controller.activeWorkspace === null}>
          <Plus aria-hidden="true" size={15} />
        </button>
      </div>
      <label className="sidebar-search">
        <Search aria-hidden="true" size={14} />
        <span className="sr-only">搜索会话</span>
        <input value={controller.searchQuery} onChange={(event) => void controller.search(event.target.value).catch(() => undefined)} placeholder="搜索会话" />
      </label>
      <label className="sidebar-archive-filter"><input type="checkbox" checked={controller.includeArchived} onChange={() => void controller.toggleArchived()} /> 显示已归档</label>
      <div className="conversation-list">
        {controller.conversations.length === 0 ? <p className="sidebar-empty">还没有会话，创建一个开始工作。</p> : null}
        {controller.conversations.map((conversation) => (
          <button
            className={`conversation-list-item ${controller.activeConversation?.conversationId === conversation.conversationId ? 'is-active' : ''}`}
            key={conversation.conversationId}
            type="button"
            onClick={() => void controller.selectConversation(conversation.conversationId).catch(() => undefined)}
          >
            <span className="conversation-list-title">{conversation.title}</span>
            <span className="conversation-list-meta">{conversation.status === 'ARCHIVED' ? '已归档' : '活动'}</span>
          </button>
        ))}
      </div>
      {controller.activeConversation?.status === 'ACTIVE' ? (
        <div className="sidebar-conversation-actions">
          <button className="sidebar-archive" type="button" onClick={() => void controller.archive().catch(() => undefined)}>
            <Archive aria-hidden="true" size={14} /> 归档当前会话
          </button>
          <button className="sidebar-delete" type="button" aria-label="删除当前会话" title="删除当前会话" onClick={() => { if (window.confirm('删除后会话轮次和运行记录不可恢复，工作区保留。确认删除？')) void controller.deleteConversation().catch(() => undefined) }}>
            <Trash2 aria-hidden="true" size={14} />
          </button>
        </div>
      ) : null}
      {controller.error === null ? null : <p className="sidebar-error" role="alert">{controller.error.message}</p>}
      {dialogOpen ? (
        <WorkspaceDialog
          createWorkspace={controller.createWorkspace}
          browseWorkspaceDirectories={controller.browseWorkspaceDirectories}
          importWorkspace={controller.importWorkspace}
          importDesktopWorkspace={controller.importDesktopWorkspace}
          onClose={() => setDialogOpen(false)}
        />
      ) : null}
      {modelDialogOpen ? <ModelSettingsDialog controller={controller} onClose={() => setModelDialogOpen(false)} /> : null}
      {appearanceDialogOpen ? <AppearanceSettingsDialog onClose={() => setAppearanceDialogOpen(false)} /> : null}
    </aside>
  )
}
