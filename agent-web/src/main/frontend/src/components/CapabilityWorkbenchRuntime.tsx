import { useEffect, useState } from 'react'

import { confirmMcp, confirmSkill, listMcpCatalog, listMcpInstallations, listSkillInstallations, prepareMcpMaterial, previewMcp, previewSkill, refreshMcpCatalog, searchSkills, startMcpInstallation, stopMcpInstallation, uninstallMcpInstallation, uninstallSkillInstallation, type GitHubSkillRepository, type McpCatalog, type McpInstallation, type McpPreview, type SkillInstallation, type SkillPreview, type InstallationScope } from '../api/capabilityApi'
import { CapabilityWorkbenchPanel } from './CapabilityWorkbenchPanel'

export function CapabilityWorkbenchRuntime({ workspaceId }: { workspaceId: string }) {
  const [catalog, setCatalog] = useState<McpCatalog | null>(null)
  const [skills, setSkills] = useState<GitHubSkillRepository[]>([])
  const [mcpInstallations, setMcpInstallations] = useState<McpInstallation[]>([])
  const [skillInstallations, setSkillInstallations] = useState<SkillInstallation[]>([])
  useEffect(() => { void listMcpCatalog().then(setCatalog).catch(() => setCatalog(null)) }, [])
  const refreshInstallations = async () => { setMcpInstallations(await listMcpInstallations(workspaceId)); setSkillInstallations(await listSkillInstallations(workspaceId)) }
  useEffect(() => { void refreshInstallations().catch(() => { setMcpInstallations([]); setSkillInstallations([]) }) }, [workspaceId])
  return <CapabilityWorkbenchPanel workspaceId={workspaceId} mcpCatalog={catalog} skillResults={skills} mcpInstallations={mcpInstallations} skillInstallations={skillInstallations} onRefreshMcp={async () => { setCatalog(await refreshMcpCatalog()); await refreshInstallations() }} onSearchSkills={async (query) => setSkills(await searchSkills(query))} onPreviewMcp={(serverKey: string, scope: InstallationScope) => previewMcp(workspaceId, serverKey, scope)} onConfirmMcp={async (preview: McpPreview) => { await confirmMcp(workspaceId, preview); await refreshInstallations() }} onPreviewSkill={(repository: string, scope: InstallationScope) => previewSkill(workspaceId, repository, scope)} onConfirmSkill={async (preview: SkillPreview) => { await confirmSkill(workspaceId, preview); await refreshInstallations() }} onPrepareMcp={async (installation) => { await prepareMcpMaterial(workspaceId, installation); await refreshInstallations() }} onStartMcp={async (installation) => { await startMcpInstallation(workspaceId, installation); await refreshInstallations() }} onStopMcp={async (installation) => { await stopMcpInstallation(workspaceId, installation); await refreshInstallations() }} onUninstallMcp={async (installation) => { await uninstallMcpInstallation(workspaceId, installation); await refreshInstallations() }} onUninstallSkill={async (installation) => { await uninstallSkillInstallation(workspaceId, installation); await refreshInstallations() }} />
}
