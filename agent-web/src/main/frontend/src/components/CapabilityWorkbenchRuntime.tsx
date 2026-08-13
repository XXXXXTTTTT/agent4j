import { useEffect, useRef, useState } from 'react'

import { confirmMcp, confirmSkill, listMcpCatalog, listMcpInstallations, listSkillInstallations, prepareMcpMaterial, previewMcp, previewSkill, refreshMcpCatalog, searchSkills, startMcpInstallation, stopMcpInstallation, uninstallMcpInstallation, uninstallSkillInstallation, type GitHubSkillRepository, type McpCatalog, type McpInstallation, type McpPreview, type SkillInstallation, type SkillPreview, type InstallationScope } from '../api/capabilityApi'
import { CapabilityWorkbenchPanel } from './CapabilityWorkbenchPanel'

export function CapabilityWorkbenchRuntime({ workspaceId }: { workspaceId: string }) {
  const [catalog, setCatalog] = useState<McpCatalog | null>(null)
  const [skills, setSkills] = useState<GitHubSkillRepository[]>([])
  const [mcpInstallations, setMcpInstallations] = useState<McpInstallation[]>([])
  const [skillInstallations, setSkillInstallations] = useState<SkillInstallation[]>([])
  const catalogRequest = useRef(0)
  const installationRequest = useRef(0)
  const workspaceRef = useRef(workspaceId)
  workspaceRef.current = workspaceId
  const loadCatalog = async (loader: () => Promise<McpCatalog>, keepCurrentOnFailure: boolean) => {
    const request = ++catalogRequest.current
    try {
      const nextCatalog = await loader()
      if (request !== catalogRequest.current) return
      setCatalog(nextCatalog)
    } catch (failure) {
      if (request !== catalogRequest.current) return
      if (!keepCurrentOnFailure) setCatalog(null)
      throw failure
    }
  }
  const skillSearchRequest = useRef(0)
  useEffect(() => { void loadCatalog(listMcpCatalog, false).catch(() => undefined) }, [])
  const refreshInstallations = async () => {
    const request = ++installationRequest.current
    try {
      const [mcp, skills] = await Promise.all([
        listMcpInstallations(workspaceId),
        listSkillInstallations(workspaceId),
      ])
      if (request !== installationRequest.current) return
      setMcpInstallations(mcp)
      setSkillInstallations(skills)
    } catch (failure) {
      if (request !== installationRequest.current) return
      setMcpInstallations([])
      setSkillInstallations([])
      throw failure
    }
  }
  useEffect(() => { void refreshInstallations().catch(() => undefined) }, [workspaceId])
  const refreshCurrentWorkspace = async (operationWorkspaceId: string) => {
    if (workspaceRef.current !== operationWorkspaceId) return
    await refreshInstallations()
  }
  return <CapabilityWorkbenchPanel workspaceId={workspaceId} mcpCatalog={catalog} skillResults={skills} mcpInstallations={mcpInstallations} skillInstallations={skillInstallations} onRefreshMcp={async () => { await loadCatalog(refreshMcpCatalog, true); await refreshCurrentWorkspace(workspaceId) }} onSearchSkills={async (query) => { const request = ++skillSearchRequest.current; try { const nextSkills = await searchSkills(query); if (request === skillSearchRequest.current) setSkills(nextSkills) } catch (failure) { if (request === skillSearchRequest.current) throw failure } }} onPreviewMcp={(serverKey: string, scope: InstallationScope) => previewMcp(workspaceId, serverKey, scope)} onConfirmMcp={async (preview: McpPreview) => { await confirmMcp(workspaceId, preview); await refreshCurrentWorkspace(workspaceId) }} onPreviewSkill={(repository: string, scope: InstallationScope) => previewSkill(workspaceId, repository, scope)} onConfirmSkill={async (preview: SkillPreview) => { await confirmSkill(workspaceId, preview); await refreshCurrentWorkspace(workspaceId) }} onPrepareMcp={async (installation) => { await prepareMcpMaterial(workspaceId, installation); await refreshCurrentWorkspace(workspaceId) }} onStartMcp={async (installation, environment) => { await startMcpInstallation(workspaceId, installation, environment); await refreshCurrentWorkspace(workspaceId) }} onStopMcp={async (installation) => { await stopMcpInstallation(workspaceId, installation); await refreshCurrentWorkspace(workspaceId) }} onUninstallMcp={async (installation) => { await uninstallMcpInstallation(workspaceId, installation); await refreshCurrentWorkspace(workspaceId) }} onUninstallSkill={async (installation) => { await uninstallSkillInstallation(workspaceId, installation); await refreshCurrentWorkspace(workspaceId) }} />
}
