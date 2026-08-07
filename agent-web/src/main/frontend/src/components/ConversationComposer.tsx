import { Send } from 'lucide-react'
import { type FormEvent, useState } from 'react'

import type { UseConversationWorkspaceResult } from '../hooks/useConversationWorkspace'
import type { UseRunWorkbenchResult } from '../hooks/useRunWorkbench'

interface ConversationComposerProps {
  conversation: UseConversationWorkspaceResult
  runController: UseRunWorkbenchResult
}

/** 提交持久化轮次，并把已创建 Run 接入证据检查器。 */
export function ConversationComposer({ conversation, runController }: ConversationComposerProps) {
  const [content, setContent] = useState('')
  const [inputError, setInputError] = useState<string | null>(null)

  async function submit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    setInputError(null)
    try {
      const turn = await conversation.submit(content)
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
        <textarea id="conversation-message" value={content} onChange={(event) => setContent(event.target.value)} rows={3} placeholder={conversation.activeConversation === null ? '先新建或选择一个会话' : '输入消息，继续这个项目的对话'} disabled={conversation.activeConversation === null || conversation.submitting} />
        {inputError === null ? null : <p className="inline-error" role="alert">{inputError}</p>}
        <div className="composer-toolbar">
          <div className="composer-context"><span>{conversation.activeWorkspace?.displayName ?? '未选择工作区'}</span></div>
          <button className="primary-command" type="submit" aria-label="发送消息" title="发送消息" disabled={conversation.activeConversation === null || conversation.submitting || content.trim().length === 0}>
            <Send aria-hidden="true" size={17} />
          </button>
        </div>
      </form>
      <span className="sr-only" data-testid="run-status" aria-live="polite">{runController.run?.status ?? 'IDLE'}</span>
    </section>
  )
}
