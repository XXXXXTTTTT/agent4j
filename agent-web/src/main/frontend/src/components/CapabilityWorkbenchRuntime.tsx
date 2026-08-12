import { useEffect, useState } from 'react'

import { confirmMcp, confirmSkill, listMcpCatalog, previewMcp, previewSkill, searchSkills, type GitHubSkillRepository, type McpCatalog, type McpPreview, type SkillPreview, type InstallationScope } from '../api/capabilityApi'
import { CapabilityWorkbenchPanel } from './CapabilityWorkbenchPanel'

export function CapabilityWorkbenchRuntime({ workspaceId }: { workspaceId: string }) {
  const [catalog, setCatalog] = useState<McpCatalog | null>(null)
  const [skills, setSkills] = useState<GitHubSkillRepository[]>([])
  useEffect(() => { void listMcpCatalog().then(setCatalog).catch(() => setCatalog(null)) }, [])
  return <CapabilityWorkbenchPanel workspaceId={workspaceId} mcpCatalog={catalog} skillResults={skills} onRefreshMcp={async () => setCatalog(await listMcpCatalog())} onSearchSkills={async (query) => setSkills(await searchSkills(query))} onPreviewMcp={(serverKey: string, scope: InstallationScope) => previewMcp(workspaceId, serverKey, scope)} onConfirmMcp={(preview: McpPreview) => confirmMcp(workspaceId, preview)} onPreviewSkill={(repository: string, scope: InstallationScope) => previewSkill(workspaceId, repository, scope)} onConfirmSkill={(preview: SkillPreview) => confirmSkill(workspaceId, preview)} />
}
