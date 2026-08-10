import { Archive, FolderGit2, FolderPlus, Plus, Search } from 'lucide-react'
import { useState } from 'react'

import type { UseConversationWorkspaceResult } from '../hooks/useConversationWorkspace'
import { WorkspaceDialog } from './WorkspaceDialog'

interface ConversationSidebarProps {
  controller: UseConversationWorkspaceResult
}

/** 展示身份、工作区和服务端持久化会话列表。 */
export function ConversationSidebar({ controller }: ConversationSidebarProps) {
  const [dialogOpen, setDialogOpen] = useState(false)
  return (
    <aside className="conversation-sidebar" aria-label="会话与工作区">
      <div className="sidebar-identity">
        <span className="sidebar-eyebrow">WORKSPACE</span>
        <strong>{controller.identity?.displayName ?? '加载身份中'}</strong>
        <code>{controller.identity?.userId ?? ''}</code>
      </div>
      <label className="sidebar-label" htmlFor="workspace-select">工作区</label>
      <div className="sidebar-workspace-row">
        <div className="sidebar-select-wrap">
          <FolderGit2 aria-hidden="true" size={15} />
        <select
          id="workspace-select"
          aria-label="工作区"
          value={controller.activeWorkspace?.workspaceId ?? ''}
          onChange={(event) => void controller.selectWorkspace(event.target.value).catch(() => undefined)}
          disabled={controller.loading || controller.workspaces.length === 0}
        >
          {controller.workspaces.length === 0 ? <option value="">没有可用工作区</option> : null}
          {controller.workspaces.map((workspace) => (
            <option key={workspace.workspaceId} value={workspace.workspaceId}>{workspace.displayName}</option>
          ))}
        </select>
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
        <button className="sidebar-archive" type="button" onClick={() => void controller.archive().catch(() => undefined)}>
          <Archive aria-hidden="true" size={14} /> 归档当前会话
        </button>
      ) : null}
      {controller.error === null ? null : <p className="sidebar-error" role="alert">{controller.error.message}</p>}
      {dialogOpen ? <WorkspaceDialog createWorkspace={controller.createWorkspace} onClose={() => setDialogOpen(false)} /> : null}
    </aside>
  )
}
