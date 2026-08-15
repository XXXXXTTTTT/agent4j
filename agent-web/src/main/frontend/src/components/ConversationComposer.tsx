import { Send, Terminal } from 'lucide-react'
import { type FormEvent, type KeyboardEvent, useEffect, useRef, useState } from 'react'

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
import { parseComposerCommand } from './composerCommandParser'

interface ConversationComposerProps {
  conversation: UseConversationWorkspaceResult
  runController: UseRunWorkbenchResult
}

function formatCommandResultValue(value: unknown): string {
  if (value === null) return 'null'
  if (['string', 'number', 'boolean'].includes(typeof value)) return String(value)
  return JSON.stringify(value, null, 2) ?? String(value)
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
  const [commandMenuOpen, setCommandMenuOpen] = useState(false)
  const [commandFeedback, setCommandFeedback] = useState<string | null>(null)
  const [commandFeedbackData, setCommandFeedbackData] = useState<Record<string, unknown> | null>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const formRef = useRef<HTMLFormElement>(null)
  const commandItemRefs = useRef<Array<HTMLButtonElement | null>>([])

  useEffect(() => {
    if (orchestrationMode === 'SERIAL_DEVELOPMENT' || modelGroupId.trim().length > 0 || modelGroups.length === 0) return
    setModelGroupId(modelGroups[0].groupId)
  }, [modelGroupId, modelGroups, orchestrationMode])

  const slashMode = commandMenuOpen && content.startsWith('/')
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
  const safeSelectedIndex = menuItems.length === 0
    ? 0
    : Math.min(selectedIndex, menuItems.length - 1)
  const composerCommand = parseComposerCommand(content)
  const missingPrimaryModelGroup = composerCommand.kind === 'message'
    && orchestrationMode !== 'SERIAL_DEVELOPMENT'
    && modelGroupId.trim().length === 0
  const submitDisabled = conversation.activeWorkspace === null
    || conversation.submitting
    || missingPrimaryModelGroup
    || (composerCommand.kind === 'message'
      && (conversation.activeConversation === null || content.trim().length === 0))
    || (composerCommand.kind === 'slash' && conversation.activeConversation === null)

  useEffect(() => {
    if (menuItems.length === 0) {
      setSelectedIndex(0)
      commandItemRefs.current = []
      return
    }
    setSelectedIndex((index) => Math.min(index, menuItems.length - 1))
  }, [commandQuery, menuItems.length])

  useEffect(() => {
    if (!slashMode || menuItems.length === 0) return
    commandItemRefs.current[safeSelectedIndex]?.scrollIntoView?.({ block: 'nearest' })
  }, [safeSelectedIndex, slashMode, menuItems.length])

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
    setContent(`/cli ${command.name} `)
    setCommandMenuOpen(false)
    setSelectedIndex(0)
    setInputError(null)
    setCommandFeedback(null)
    requestAnimationFrame(() => inputRef.current?.focus())
  }

  function selectSlashCommand(command: SlashCommandDefinition): void {
    setContent(`/${command.name} `)
    setCommandMenuOpen(false)
    setSelectedIndex(0)
    setInputError(null)
    setCommandFeedback(null)
    requestAnimationFrame(() => inputRef.current?.focus())
  }

  function onMessageChange(value: string): void {
    setContent(value)
    setInputError(null)
    setCommandFeedback(null)
    setCommandFeedbackData(null)
    if (value.startsWith('/')) {
      setCommandMenuOpen(!/\s/.test(value.slice(1)))
      void loadCommands().catch((failure) =>
        setInputError(failure instanceof Error ? failure.message : String(failure)),
      )
      void loadSlashCommands().catch((failure) =>
        setInputError(failure instanceof Error ? failure.message : String(failure)),
      )
    } else {
      setCommandMenuOpen(false)
    }
  }

  function onMessageKeyDown(event: KeyboardEvent<HTMLTextAreaElement>): void {
    if (slashMode && menuItems.length > 0 && event.key === 'ArrowDown') {
      event.preventDefault()
      setSelectedIndex((index) => (index + 1) % menuItems.length)
      return
    }
    if (slashMode && menuItems.length > 0 && event.key === 'ArrowUp') {
      event.preventDefault()
      setSelectedIndex((index) => (index - 1 + menuItems.length) % menuItems.length)
      return
    }
    if (slashMode && menuItems.length > 0 && event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault()
      const selected = menuItems[safeSelectedIndex] ?? menuItems[0]
      if (selected.kind === 'slash') selectSlashCommand(selected.command)
      else selectCommand(selected.command)
      return
    }
    if (slashMode && event.key === 'Escape') {
      event.preventDefault()
      setCommandMenuOpen(false)
      return
    }
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault()
      if (!submitDisabled) formRef.current?.requestSubmit()
    }
  }

  function approvalText(riskLevel: CliRiskLevel): string {
    if (riskLevel === 'READ_ONLY') return '可直接执行'
    if (riskLevel === 'MUTATING') return '执行前需要审批'
    return '需要用户和管理员审批'
  }

  async function submitCli(commandName: string, argumentsList: string[]): Promise<void> {
    const workspaceId = conversation.activeWorkspace?.workspaceId
    if (workspaceId === undefined) throw new Error('当前没有可执行的工作区命令')
    const created = await createGovernedCliRun(workspaceId, {
      commandName,
      arguments: argumentsList,
      timeoutSeconds: 30,
    })
    setContent('')
    await runController.followRun(created.runId)
  }

  async function submitSlashCommand(input: string): Promise<void> {
    if (conversation.dispatchSlashCommand === undefined) throw new Error('Slash Command 接口未配置')
    const result = await conversation.dispatchSlashCommand(input, modelGroupId || undefined)
    const runId = typeof result.data.runId === 'string' ? result.data.runId : null
    const createdConversationId = typeof result.data.conversationId === 'string'
      ? result.data.conversationId
      : null
    setContent('')
    setCommandFeedback(result.message)
    setCommandFeedbackData(result.data)
    if (createdConversationId !== null) {
      await conversation.reload()
      await conversation.selectConversation(createdConversationId)
    }
    if (runId !== null) await runController.followRun(runId)
  }

  async function submit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    setInputError(null)
    try {
      const command = parseComposerCommand(content)
      if (command.kind === 'invalid') throw new Error(command.message)
      if (command.kind === 'cli') {
        await submitCli(command.commandName, command.arguments)
        return
      }
      if (command.kind === 'slash') {
        await submitSlashCommand(command.input)
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
      <form ref={formRef} onSubmit={(event) => void submit(event)}>
        <label className="sr-only" htmlFor="conversation-message">发送消息</label>
        <textarea
          ref={inputRef}
          id="conversation-message"
          value={content}
          onChange={(event) => onMessageChange(event.target.value)}
          onKeyDown={onMessageKeyDown}
          rows={3}
          placeholder={conversation.activeConversation === null ? '输入 / 选择受治理命令，或先新建会话' : '输入消息，继续这个项目的对话；输入 / 选择命令'}
          disabled={conversation.activeWorkspace === null || conversation.submitting}
          aria-autocomplete="list"
          aria-expanded={slashMode}
          aria-controls={slashMode ? 'composer-command-menu' : undefined}
          aria-activedescendant={slashMode && menuItems.length > 0 ? `composer-command-option-${safeSelectedIndex}` : undefined}
        />
        {slashMode ? (
          <div id="composer-command-menu" className="cli-command-menu" role="listbox" aria-label="命令补全">
            {menuItems.length === 0 ? <p>未找到可用命令</p> : menuItems.map((item, index) => item.kind === 'slash' ? (
              <button id={`composer-command-option-${index}`} ref={(element) => { commandItemRefs.current[index] = element }} key={`slash-${item.command.name}`} type="button" role="option" tabIndex={-1} aria-selected={index === safeSelectedIndex} onMouseDown={(event) => event.preventDefault()} onClick={() => selectSlashCommand(item.command)}>
                <Terminal aria-hidden="true" size={15} />
                <span><strong>/{item.command.name}</strong><code>{item.command.description}</code></span>
                <em>{item.command.channel === 'SYSTEM_DIRECTIVE' ? '本地执行' : '进入 Agent 工作流'}</em>
              </button>
            ) : (
              <button id={`composer-command-option-${index}`} ref={(element) => { commandItemRefs.current[index] = element }} key={`cli-${item.command.name}`} type="button" role="option" tabIndex={-1} aria-selected={index === safeSelectedIndex} onMouseDown={(event) => event.preventDefault()} onClick={() => selectCommand(item.command)}>
                <Terminal aria-hidden="true" size={15} />
                <span><strong>/cli {item.command.name}</strong><code>{[item.command.executable, ...item.command.fixedArguments].join(' ')}</code></span>
                <em>{approvalText(item.command.riskLevel)}</em>
              </button>
            ))}
          </div>
        ) : null}
        {composerCommand.kind === 'cli' ? (
          <p className="composer-command-hint" role="status">/cli {composerCommand.commandName} · {approvalText(commands.find((command) => command.name === composerCommand.commandName)?.riskLevel ?? 'DESTRUCTIVE')} · 默认超时 30 秒</p>
        ) : composerCommand.kind === 'slash' ? (
          <p className="composer-command-hint" role="status">/{composerCommand.commandName} · {slashCommands.find((command) => command.name === composerCommand.commandName || command.aliases.includes(composerCommand.commandName))?.channel === 'SYSTEM_DIRECTIVE' ? '本地执行，不调用模型' : '进入 Agent 工作流'}</p>
        ) : null}
        {inputError === null ? null : <p className="inline-error" role="alert">{inputError}</p>}
        {commandFeedback === null ? null : <p className="inline-success" role="status">{commandFeedback}</p>}
        {commandFeedbackData === null || Object.keys(commandFeedbackData).length === 0 ? null : (
          <dl className="command-result-data" data-testid="command-result-data" aria-label="命令结果详情">
            {Object.entries(commandFeedbackData)
              .map(([key, value]) => (
                <div key={key}><dt>{key}</dt><dd className={typeof value === 'object' && value !== null ? 'is-structured' : undefined}>{formatCommandResultValue(value)}</dd></div>
              ))}
          </dl>
        )}
        {composerCommand.kind === 'message' ? (
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
          <button className="primary-command" type="submit" aria-label={composerCommand.kind === 'message' ? '发送消息' : '执行命令'} title={composerCommand.kind === 'message' ? '发送消息' : '执行命令'} disabled={submitDisabled}>
            <Send aria-hidden="true" size={17} />
          </button>
        </div>
      </form>
      <span className="sr-only" data-testid="run-status" aria-live="polite">{runController.run?.status ?? 'IDLE'}</span>
    </section>
  )
}
