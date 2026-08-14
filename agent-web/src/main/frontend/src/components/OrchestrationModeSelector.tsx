import type { AgentRole, OrchestrationMode, RoleModelGroups } from '../api/conversationApi'
import type { ModelGroup } from '../api/contracts'

interface OrchestrationModeSelectorProps {
  mode: OrchestrationMode
  roleModelGroups: RoleModelGroups
  modelGroups: ModelGroup[]
  onModeChange(mode: OrchestrationMode): void
  onRoleModelGroupChange(role: AgentRole, groupId: string): void
}

const MODE_LABELS: Record<OrchestrationMode, string> = {
  SERIAL_DEVELOPMENT: '串行开发',
  PARALLEL_RESEARCH: '并行研究',
  REVIEW_LOOP: '评审闭环',
}

const ROLE_LABELS: ReadonlyArray<readonly [AgentRole, string]> = [
  ['COORDINATOR', '协调者模型组'],
  ['RESEARCHER', '研究者模型组'],
  ['IMPLEMENTER', '实施者模型组'],
  ['VERIFIER', '验证者模型组'],
]

/** 选择生产编排模式，并为需要的子 Agent 指定已配置模型组。 */
export function OrchestrationModeSelector({ mode, roleModelGroups, modelGroups, onModeChange, onRoleModelGroupChange }: OrchestrationModeSelectorProps) {
  return (
    <div className="orchestration-selector" data-testid="orchestration-selector">
      <label className="composer-orchestration-mode">
        <span>编排模式</span>
        <select aria-label="编排模式" value={mode} onChange={(event) => onModeChange(event.target.value as OrchestrationMode)}>
          {(Object.keys(MODE_LABELS) as OrchestrationMode[]).map((value) => <option key={value} value={value}>{MODE_LABELS[value]}</option>)}
        </select>
      </label>
      {mode === 'SERIAL_DEVELOPMENT' ? null : (
        <div className="orchestration-role-groups">
          {ROLE_LABELS.map(([role, label]) => (
            <label key={role} className="composer-role-select">
              <span>{label}</span>
              <select aria-label={label} value={roleModelGroups[role] ?? ''} onChange={(event) => onRoleModelGroupChange(role, event.target.value)}>
                <option value="">跟随主模型组</option>
                {modelGroups.map((group) => <option key={group.groupId} value={group.groupId}>{group.displayName}</option>)}
              </select>
            </label>
          ))}
        </div>
      )}
    </div>
  )
}
