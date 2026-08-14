import { Send, Terminal } from 'lucide-react'
import { type FormEvent, type KeyboardEvent, useState } from 'react'

import {
  createGovernedCliRun,
  listGovernedCliCommands,
  type CliRiskLevel,
  type GovernedCliCommand,
} from '../api/cliApi'
import type { UseConversationWorkspaceResult } from '../hooks/useConversationWorkspace'
import type { UseRunWorkbenchResult } from '../hooks/useRunWorkbench'
import { OrchestrationModeSelector } from './OrchestrationModeSelector'
import type { AgentRole, OrchestrationMode, RoleModelGroups } from '../api/conversationApi'

interface ConversationComposerProps {
  conversation: UseConversationWorkspaceResult
  runController: UseRunWorkbenchResult
}

/** 提交持久化轮次，并把已创建 Run 接入证据检查器。 */
export function ConversationComposer({ conversation, runController }: ConversationComposerProps) {
  const [content, setContent] = useState('')
  const [modelGroupId, setModelGroupId] = useState('')
  const [orchestrationMode, setOrchestrationMode] = useState<OrchestrationMode>('SERIAL_DEVELOPMENT')
  const [roleModelGroups, setRoleModelGroups] = useState<RoleModelGroups>({})
  const modelGroups = conversation.modelConfiguration?.groups ?? []
  const [inputError, setInputError] = useState<string | null>(null)
  const [commands, setCommands] = useState<GovernedCliCommand[]>([])
  const [commandsLoadedForWorkspace, setCommandsLoadedForWorkspace] = useState<string | null>(null)
  const [selectedIndex, setSelectedIndex] = useState(0)
  const [selectedCommand, setSelectedCommand] = useState<GovernedCliCommand | null>(null)
  const [argumentsInput, setArgumentsInput] = useState('')
  const [timeoutSeconds, setTimeoutSeconds] = useState('30')

  const slashMode = content.startsWith('/') && selectedCommand === null
  const commandQuery = slashMode ? content.slice(1).trim().toLocaleLowerCase() : ''
  const visibleCommands = commands.filter((command) =>
    command.riskLevel !== 'DESTRUCTIVE'
      && command.name.toLocaleLowerCase().includes(commandQuery),
  )

  async function loadCommands(): Promise<void> {
    const workspaceId = conversation.activeWorkspace?.workspaceId
    if (workspaceId === undefined || commandsLoadedForWorkspace === workspaceId) return
    const loaded = await listGovernedCliCommands(workspaceId)
    setCommands(loaded)
    setCommandsLoadedForWorkspace(workspaceId)
    setSelectedIndex(0)
  }

  function selectCommand(command: GovernedCliCommand): void {
    setSelectedCommand(command)
    setContent('')
    setSelectedIndex(0)
    setInputError(null)
  }

  function onMessageChange(value: string): void {
    setContent(value)
    setInputError(null)
    if (!value.startsWith('/')) setSelectedCommand(null)
    if (value.startsWith('/')) {
      void loadCommands().catch((failure) =>
        setInputError(failure instanceof Error ? failure.message : String(failure)),
      )
    }
  }

  function onMessageKeyDown(event: KeyboardEvent<HTMLTextAreaElement>): void {
    if (!slashMode || visibleCommands.length === 0) return
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setSelectedIndex((index) => (index + 1) % visibleCommands.length)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setSelectedIndex((index) => (index - 1 + visibleCommands.length) % visibleCommands.length)
    } else if (event.key === 'Enter') {
      event.preventDefault()
      selectCommand(visibleCommands[selectedIndex] ?? visibleCommands[0])
    } else if (event.key === 'Escape') {
      event.preventDefault()
      setContent('')
    }
  }

  function approvalText(riskLevel: CliRiskLevel): string {
    if (riskLevel === 'READ_ONLY') return '可直接执行'
    if (riskLevel === 'MUTATING') return '执行前需要审批'
    return '需要用户和管理员审批'
  }

  function argumentsList(): string[] {
    return argumentsInput.split(/\r?\n/).map((argument) => argument.trim()).filter((argument) => argument.length > 0)
  }

  async function submitCli(): Promise<void> {
    const workspaceId = conversation.activeWorkspace?.workspaceId
    if (workspaceId === undefined || selectedCommand === null) throw new Error('当前没有可执行的工作区命令')
    const parsedTimeoutSeconds = Number(timeoutSeconds)
    const created = await createGovernedCliRun(workspaceId, {
      commandName: selectedCommand.name,
      arguments: argumentsList(),
      timeoutSeconds: parsedTimeoutSeconds,
    })
    setSelectedCommand(null)
    setArgumentsInput('')
    setTimeoutSeconds('30')
    await runController.followRun(created.runId)
  }

  async function submit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    setInputError(null)
    try {
      if (selectedCommand !== null) {
        await submitCli()
        return
      }
      const selectedOrchestration = orchestrationMode === 'SERIAL_DEVELOPMENT' && Object.keys(roleModelGroups).length === 0
        ? undefined
        : orchestrationMode
      const turn = (modelGroupId || selectedOrchestration !== undefined || Object.keys(roleModelGroups).length > 0)
        ? await conversation.submit(content, undefined, modelGroupId || undefined, selectedOrchestration, Object.keys(roleModelGroups).length > 0 ? roleModelGroups : undefined)
        : await conversation.submit(content)
      setContent('')
      if (turn.runId !== null) await runController.followRun(turn.runId)
    } catch (failure) {
      setInputError(failure instanceof Error ? failure.message : String(failure))
    }
  }

  return (
    <section className="run-launcher conversation-composer" data-testid="run-launcher" aria-label="会话输入">
      <form onSubmit={(event) => void submit(event)}>
        <label className="sr-only" htmlFor="conversation-message">发送消息</label>
        <textarea id="conversation-message" value={content} onChange={(event) => onMessageChange(event.target.value)} onKeyDown={onMessageKeyDown} rows={3} placeholder={conversation.activeConversation === null ? '输入 / 选择受治理命令，或先新建会话' : '输入消息，继续这个项目的对话；输入 / 选择受治理命令'} disabled={conversation.activeWorkspace === null || conversation.submitting} />
        {slashMode ? (
          <div className="cli-command-menu" role="listbox" aria-label="受治理命令">
            {visibleCommands.length === 0 ? <p>未找到受治理命令</p> : visibleCommands.map((command, index) => (
              <button key={command.name} type="button" role="option" aria-selected={index === selectedIndex} onMouseDown={(event) => event.preventDefault()} onClick={() => selectCommand(command)}>
                <Terminal aria-hidden="true" size={15} />
                <span><strong>{command.name}</strong><code>{[command.executable, ...command.fixedArguments].join(' ')}</code></span>
                <em>{approvalText(command.riskLevel)}</em>
              </button>
            ))}
          </div>
        ) : null}
        {selectedCommand === null ? null : (
          <div className="cli-command-preview" aria-label="命令执行预览">
            <div><Terminal aria-hidden="true" size={15} /><strong>{selectedCommand.name}</strong><code>{[selectedCommand.executable, ...selectedCommand.fixedArguments].join(' ')}</code></div>
            <p className={`cli-risk cli-risk-${selectedCommand.riskLevel.toLocaleLowerCase()}`}>{approvalText(selectedCommand.riskLevel)}</p>
            <label className="field-label" htmlFor="cli-arguments">命令参数</label>
            <textarea id="cli-arguments" aria-label="命令参数" value={argumentsInput} onChange={(event) => setArgumentsInput(event.target.value)} rows={3} placeholder="每行一个参数" disabled={conversation.submitting} />
            <label className="field-label" htmlFor="cli-timeout">超时秒数</label>
            <input id="cli-timeout" aria-label="超时秒数" type="number" min="1" max="600" value={timeoutSeconds} onChange={(event) => setTimeoutSeconds(event.target.value)} disabled={conversation.submitting} />
          </div>
        )}
        {inputError === null ? null : <p className="inline-error" role="alert">{inputError}</p>}
        {selectedCommand === null ? (
          <OrchestrationModeSelector
            mode={orchestrationMode}
            roleModelGroups={roleModelGroups}
            modelGroups={modelGroups}
            onModeChange={(mode) => {
              setOrchestrationMode(mode)
              if (mode === 'SERIAL_DEVELOPMENT') setRoleModelGroups({})
            }}
            onRoleModelGroupChange={(role: AgentRole, groupId: string) => {
              setRoleModelGroups((current) => {
                if (groupId.length === 0) {
                  const next = { ...current }
                  delete next[role]
                  return next
                }
                return { ...current, [role]: groupId }
              })
            }}
          />
        ) : null}
        <div className="composer-toolbar">
          <div className="composer-context"><span>{conversation.activeWorkspace?.displayName ?? '未选择工作区'}</span></div>
          <label className="composer-model-select">
            <span className="sr-only">模型组</span>
            <select aria-label="模型组" value={modelGroupId} onChange={(event) => setModelGroupId(event.target.value)} disabled={modelGroups.length === 0}>
              <option value="">默认模型组</option>
              {modelGroups.map((group) => <option key={group.groupId} value={group.groupId}>{group.displayName}</option>)}
            </select>
          </label>
          <button className="primary-command" type="submit" aria-label={selectedCommand === null ? '发送消息' : '执行命令'} title={selectedCommand === null ? '发送消息' : '执行命令'} disabled={conversation.activeWorkspace === null || conversation.submitting || (selectedCommand === null && (conversation.activeConversation === null || content.trim().length === 0))}>
            <Send aria-hidden="true" size={17} />
          </button>
        </div>
      </form>
      <span className="sr-only" data-testid="run-status" aria-live="polite">{runController.run?.status ?? 'IDLE'}</span>
    </section>
  )
}
