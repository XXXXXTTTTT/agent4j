import { Check, Eye, RefreshCw, ShieldCheck, X } from 'lucide-react'
import { useState } from 'react'

import type { GitHubSkillRepository, InstallationScope, McpCatalog, McpPreview, SkillPreview } from '../api/capabilityApi'

interface Props {
  workspaceId: string
  mcpCatalog: McpCatalog | null
  skillResults: GitHubSkillRepository[]
  onPreviewMcp(serverKey: string, scope: InstallationScope): Promise<McpPreview>
  onConfirmMcp(preview: McpPreview): Promise<unknown>
  onPreviewSkill(repository: string, scope: InstallationScope): Promise<SkillPreview>
  onConfirmSkill(preview: SkillPreview): Promise<unknown>
  onSearchSkills?(query: string): Promise<void>
  onRefreshMcp?(): Promise<void>
}

function ScopeSelect({ value, onChange }: { value: InstallationScope; onChange(value: InstallationScope): void }) {
  return <label className="capability-scope">安装范围<select aria-label="安装范围" value={value} onChange={(event) => onChange(event.target.value as InstallationScope)}><option value="WORKSPACE">当前工作区</option><option value="USER_GLOBAL">用户全局</option></select></label>
}

export function CapabilityWorkbenchPanel({ mcpCatalog, skillResults, onPreviewMcp, onConfirmMcp, onPreviewSkill, onConfirmSkill, onSearchSkills, onRefreshMcp }: Props) {
  const [mcpScope, setMcpScope] = useState<InstallationScope>('WORKSPACE')
  const [skillScope, setSkillScope] = useState<InstallationScope>('WORKSPACE')
  const [mcpPreview, setMcpPreview] = useState<McpPreview | null>(null)
  const [skillPreview, setSkillPreview] = useState<SkillPreview | null>(null)
  const [query, setQuery] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const run = (operation: () => Promise<void>) => { setBusy(true); setError(null); void operation().catch((failure: unknown) => setError(failure instanceof Error ? failure.message : String(failure))).finally(() => setBusy(false)) }
  return <section className="capability-panel" aria-label="能力管理">
    <div className="tool-panel-bar"><div className="panel-title"><ShieldCheck size={15} />能力管理</div><button type="button" className="icon-button" aria-label="刷新 MCP 目录" title="刷新 MCP 目录" disabled={busy} onClick={() => onRefreshMcp && run(onRefreshMcp)}><RefreshCw size={14} /></button></div>
    <div className="capability-section"><div className="capability-heading"><h3>官方 MCP 目录</h3><code>{mcpCatalog?.commitSha ?? '未加载'}</code></div><ScopeSelect value={mcpScope} onChange={setMcpScope} />{mcpCatalog === null ? <p className="empty-tool-state">正在读取官方目录</p> : mcpCatalog.servers.map((server) => <div className="capability-row" key={server.serviceId}><div><strong>{server.serviceId}</strong><p>{server.description || server.readmeSummary}</p><small>{server.license || '未声明许可证'} · {server.command} {server.arguments.join(' ')}</small></div><button type="button" aria-label={`预览 ${server.serviceId}`} title="预览安装" disabled={busy} onClick={() => run(async () => { setMcpPreview(await onPreviewMcp(server.serviceId, mcpScope)) })}><Eye size={14} />预览</button></div>)}</div>
    {mcpPreview !== null && <PreviewCard title="MCP 安装预览" preview={mcpPreview} onCancel={() => setMcpPreview(null)} onConfirm={() => run(async () => { await onConfirmMcp(mcpPreview); setMcpPreview(null) })} />}
    <div className="capability-section"><div className="capability-heading"><h3>GitHub Skills</h3><form onSubmit={(event) => { event.preventDefault(); if (onSearchSkills) run(() => onSearchSkills(query)) }}><input aria-label="搜索 GitHub Skills" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索仓库" /><button type="submit" aria-label="搜索 Skills" title="搜索"><Eye size={14} /></button></form></div><ScopeSelect value={skillScope} onChange={setSkillScope} />{skillResults.map((skill) => <div className="capability-row" key={skill.repository}><div><strong>{skill.repository}</strong><p>{skill.description}</p><small>{skill.license || '未声明许可证'} · {skill.defaultBranch}</small></div><button type="button" aria-label={`预览 ${skill.repository}`} disabled={busy} onClick={() => run(async () => { setSkillPreview(await onPreviewSkill(skill.repository, skillScope)) })}><Eye size={14} />预览</button></div>)}</div>
    {skillPreview !== null && <PreviewCard title="Skill 安装预览" preview={skillPreview} onCancel={() => setSkillPreview(null)} onConfirm={() => run(async () => { await onConfirmSkill(skillPreview); setSkillPreview(null) })} />}
    {error !== null && <p className="inline-error" role="alert">{error}</p>}
  </section>
}

function PreviewCard({ title, preview, onCancel, onConfirm }: { title: string; preview: McpPreview | SkillPreview; onCancel(): void; onConfirm(): void }) {
  const isSkill = 'repository' in preview
  return <div className="capability-preview" role="dialog" aria-label={title}><div className="capability-heading"><h3>{title}</h3><span className="risk-badge">{preview.requiresConfirmation ? '需要确认' : '可直接安装'}</span></div><dl className="capability-metadata"><div><dt>来源</dt><dd><a href={isSkill ? preview.repositoryUrl : preview.sourceUrl} target="_blank" rel="noreferrer">{isSkill ? preview.repository : preview.sourceUrl}</a></dd></div><div><dt>Commit</dt><dd><code>{preview.commitSha}</code></dd></div><div><dt>许可证</dt><dd>{isSkill ? preview.license : '官方目录'}</dd></div><div><dt>范围</dt><dd>{preview.scope === 'WORKSPACE' ? '当前工作区' : '用户全局'}</dd></div><div><dt>摘要</dt><dd>{preview.summary}</dd></div>{isSkill ? <div><dt>工具权限</dt><dd>{preview.requestedToolNames.join(', ') || '无'}</dd></div> : <div><dt>启动环境</dt><dd>{preview.environmentVariableNames.join(', ') || '无'}</dd></div>}</dl><div className="approval-actions"><button type="button" onClick={onConfirm} disabled={!preview.requiresConfirmation}><Check size={14} />确认安装{isSkill ? ' Skill' : ' MCP'}</button><button type="button" onClick={onCancel}><X size={14} />取消</button></div></div>
}
