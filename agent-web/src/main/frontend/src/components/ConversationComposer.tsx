import { Send, Terminal } from 'lucide-react'
import { type FormEvent, type KeyboardEvent, useEffect, useState } from 'react'

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
import type { SlashCommandDefinition } from '../api/commandApi'

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
  const [slashCommands, setSlashCommands] = useState<SlashCommandDefinition[]>([])
  const [commandsLoadedForWorkspace, setCommandsLoadedForWorkspace] = useState<string | null>(null)
  const [selectedIndex, setSelectedIndex] = useState(0)
  const [selectedCommand, setSelectedCommand] = useState<GovernedCliCommand | null>(null)
  const [selectedSlashCommand, setSelectedSlashCommand] = useState<SlashCommandDefinition | null>(null)
  const [argumentsInput, setArgumentsInput] = useState('')
  const [slashArgumentsInput, setSlashArgumentsInput] = useState('')
  const [commandFeedback, setCommandFeedback] = useState<string | null>(null)
  const [timeoutSeconds, setTimeoutSeconds] = useState('30')

  useEffect(() => {
    if (orchestrationMode === 'SERIAL_DEVELOPMENT' || modelGroupId.trim().length > 0 || modelGroups.length === 0) return
    setModelGroupId(modelGroups[0].groupId)
  }, [modelGroupId, modelGroups, orchestrationMode])

  const slashMode = content.startsWith('/') && selectedCommand === null && selectedSlashCommand === null
  const commandQuery = slashMode ? content.slice(1).trim().toLocaleLowerCase() : ''
  const visibleCommands = commands.filter((command) =>
    command.riskLevel !== 'DESTRUCTIVE'
      && command.name.toLocaleLowerCase().includes(commandQuery),
  )
  const visibleSlashCommands = slashCommands.filter((command) =>
    command.name.toLocaleLowerCase().includes(commandQuery)
      || command.description.toLocaleLowerCase().includes(commandQuery),
  )
  const menuItems: Array<{ kind: 'slash'; command: SlashCommandDefinition } | { kind: 'cli'; command: GovernedCliCommand }> = [
    ...visibleSlashCommands.map((command) => ({ kind: 'slash' as const, command })),
    ...visibleCommands.map((command) => ({ kind: 'cli' as const, command })),
  ]
  const missingPrimaryModelGroup = selectedCommand === null && selectedSlashCommand === null
    && orchestrationMode !== 'SERIAL_DEVELOPMENT'
    && modelGroupId.trim().length === 0

  async function loadCommands(): Promise<void> {
    const workspaceId = conversation.activeWorkspace?.workspaceId
    if (workspaceId === undefined || commandsLoadedForWorkspace === workspaceId) return
    const loaded = await listGovernedCliCommands(workspaceId)
    setCommands(loaded)
    setCommandsLoadedForWorkspace(workspaceId)
    setSelectedIndex(0)
  }

  async function loadSlashCommands(): Promise<void> {
    if (conversation.listSlashCommands === undefined) return
    const loaded = await conversation.listSlashCommands()
    setSlashCommands(loaded.commands)
    setSelectedIndex(0)
  }

  function selectCommand(command: GovernedCliCommand): void {
    setSelectedCommand(command)
    setContent('')
    setSelectedIndex(0)
    setInputError(null)
    setCommandFeedback(null)
  }

  function selectSlashCommand(command: SlashCommandDefinition): void {
    setSelectedSlashCommand(command)
    setContent('')
    setSelectedIndex(0)
    setSlashArgumentsInput('')
    setInputError(null)
    setCommandFeedback(null)
  }

  function onMessageChange(value: string): void {
    setContent(value)
    setInputError(null)
    setCommandFeedback(null)
    if (!value.startsWith('/')) setSelectedCommand(null)
    if (value.startsWith('/')) {
      void loadCommands().catch((failure) =>
        setInputError(failure instanceof Error ? failure.message : String(failure)),
      )
      void loadSlashCommands().catch((failure) =>
        setInputError(failure instanceof Error ? failure.message : String(failure)),
      )
    }
  }

  function onMessageKeyDown(event: KeyboardEvent<HTMLTextAreaElement>): void {
    if (!slashMode || menuItems.length === 0) return
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setSelectedIndex((index) => (index + 1) % menuItems.length)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setSelectedIndex((index) => (index - 1 + menuItems.length) % menuItems.length)
    } else if (event.key === 'Enter') {
      event.preventDefault()
      const selected = menuItems[selectedIndex] ?? menuItems[0]
      if (selected.kind === 'slash') selectSlashCommand(selected.command)
      else selectCommand(selected.command)
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

  function slashArguments(): string[] {
    return slashArgumentsInput.split(/\r?\n/).map((argument) => argument.trim()).filter((argument) => argument.length > 0)
  }

  function slashInput(): string {
    if (selectedSlashCommand === null) throw new Error('当前没有选中的 Slash Command')
    const argumentsText = slashArguments().map((argument) => /\s/.test(argument)
      ? `"${argument.replaceAll('"', '\\"')}"`
      : argument).join(' ')
    return `/${selectedSlashCommand.name}${argumentsText.length === 0 ? '' : ` ${argumentsText}`}`
  }

  async function submitSlashCommand(): Promise<void> {
    if (conversation.dispatchSlashCommand === undefined) throw new Error('Slash Command 接口未配置')
    const result = await conversation.dispatchSlashCommand(slashInput(), modelGroupId || undefined)
    const runId = typeof result.data.runId === 'string' ? result.data.runId : null
    setSelectedSlashCommand(null)
    setSlashArgumentsInput('')
    setCommandFeedback(result.message)
    if (runId !== null) await runController.followRun(runId)
  }

  async function submit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    setInputError(null)
    try {
      if (selectedCommand !== null) {
        await submitCli()
        return
      }
      if (selectedSlashCommand !== null) {
        await submitSlashCommand()
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
            {menuItems.length === 0 ? <p>未找到可用命令</p> : menuItems.map((item, index) => item.kind === 'slash' ? (
              <button key={`slash-${item.command.name}`} type="button" role="option" aria-selected={index === selectedIndex} onMouseDown={(event) => event.preventDefault()} onClick={() => selectSlashCommand(item.command)}>
                <Terminal aria-hidden="true" size={15} />
                <span><strong>/{item.command.name}</strong><code>{item.command.description}</code></span>
                <em>{item.command.channel === 'SYSTEM_DIRECTIVE' ? '本地执行' : '进入 Agent 工作流'}</em>
              </button>
            ) : (
              <button key={`cli-${item.command.name}`} type="button" role="option" aria-selected={index === selectedIndex} onMouseDown={(event) => event.preventDefault()} onClick={() => selectCommand(item.command)}>
                <Terminal aria-hidden="true" size={15} />
                <span><strong>{item.command.name}</strong><code>{[item.command.executable, ...item.command.fixedArguments].join(' ')}</code></span>
                <em>{approvalText(item.command.riskLevel)}</em>
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
        {selectedSlashCommand === null ? null : (
          <div className="cli-command-preview" aria-label="Slash Command 执行预览">
            <div><Terminal aria-hidden="true" size={15} /><strong>/{selectedSlashCommand.name}</strong><code>{selectedSlashCommand.description}</code></div>
            <p className="cli-risk">{selectedSlashCommand.channel === 'SYSTEM_DIRECTIVE' ? '本地执行，不调用模型' : '进入 Agent 工作流，使用当前模型组'}</p>
            {selectedSlashCommand.parameters.length === 0 ? null : (
              <>
                <label className="field-label" htmlFor="slash-command-arguments">命令参数</label>
                <textarea id="slash-command-arguments" aria-label="Slash Command 参数" value={slashArgumentsInput} onChange={(event) => setSlashArgumentsInput(event.target.value)} rows={3} placeholder={selectedSlashCommand.parameters.map((parameter) => `${parameter.name}${parameter.required ? '（必填）' : ''}`).join('，')} disabled={conversation.submitting} />
              </>
            )}
          </div>
        )}
        {inputError === null ? null : <p className="inline-error" role="alert">{inputError}</p>}
        {commandFeedback === null ? null : <p className="inline-success" role="status">{commandFeedback}</p>}
        {selectedCommand === null && selectedSlashCommand === null ? (
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
            <select aria-label="模型组" value={modelGroupId} onChange={(event) => {
              const selectedGroupId = event.target.value
              setModelGroupId(selectedGroupId.length > 0 || orchestrationMode === 'SERIAL_DEVELOPMENT'
                ? selectedGroupId
                : (modelGroups[0]?.groupId ?? ''))
            }} disabled={modelGroups.length === 0}>
              <option value="">默认模型组</option>
              {modelGroups.map((group) => <option key={group.groupId} value={group.groupId}>{group.displayName}</option>)}
            </select>
          </label>
          <button className="primary-command" type="submit" aria-label={selectedCommand === null && selectedSlashCommand === null ? '发送消息' : selectedSlashCommand === null ? '执行命令' : '执行 Slash Command'} title={selectedCommand === null && selectedSlashCommand === null ? '发送消息' : '执行命令'} disabled={conversation.activeWorkspace === null || conversation.submitting || missingPrimaryModelGroup || (selectedCommand === null && selectedSlashCommand === null && (conversation.activeConversation === null || content.trim().length === 0))}>
            <Send aria-hidden="true" size={17} />
          </button>
        </div>
      </form>
      <span className="sr-only" data-testid="run-status" aria-live="polite">{runController.run?.status ?? 'IDLE'}</span>
    </section>
  )
}
