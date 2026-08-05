import { ChevronDown, FolderGit2, Send } from 'lucide-react'
import { type FormEvent, useState } from 'react'

import { decodeAgentState } from '../api/runApi'
import type { UseRunWorkbenchResult } from '../hooks/useRunWorkbench'

interface RunLauncherProps {
  controller: UseRunWorkbenchResult
}

const DEFAULT_TASK = ''
const DEFAULT_STATE = '{"messages":[],"variables":{},"trace":[]}'

/** 在会话底部收集自然语言任务，并保留可展开的底层运行参数。 */
export function RunLauncher({ controller }: RunLauncherProps) {
  const [task, setTask] = useState(DEFAULT_TASK)
  const [graphId, setGraphId] = useState('code-agent')
  const [initialStateJson, setInitialStateJson] = useState(DEFAULT_STATE)
  const [advanced, setAdvanced] = useState(false)
  const [inputError, setInputError] = useState<string | null>(null)
  const [starting, setStarting] = useState(false)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setInputError(null)
    setStarting(true)
    try {
      if (task.trim().length === 0) throw new Error('任务描述不能为空')
      if (advanced) {
        const parsed = JSON.parse(initialStateJson) as unknown
        await controller.start(graphId, decodeAgentState(parsed, 'initialState'))
      } else {
        await controller.startTask(task.trim())
      }
    } catch (error) {
      setInputError(error instanceof Error ? error.message : String(error))
    } finally {
      setStarting(false)
    }
  }

  return (
    <section className="run-launcher" data-testid="run-launcher" aria-label="任务输入">
      <form onSubmit={(event) => void submit(event)}>
        <label className="sr-only" htmlFor="task-description">任务描述</label>
        <textarea
          id="task-description"
          value={task}
          onChange={(event) => setTask(event.target.value)}
          rows={3}
          placeholder="描述任务，例如：修复登录超时问题并运行测试"
          required
        />
        {advanced ? (
          <div className="advanced-fields">
            <label className="field-label" htmlFor="graph-id">图 ID</label>
            <input
              id="graph-id"
              value={graphId}
              onChange={(event) => setGraphId(event.target.value)}
              autoComplete="off"
              required
            />

            <label className="field-label" htmlFor="initial-state">初始状态 JSON</label>
            <textarea
              id="initial-state"
              value={initialStateJson}
              onChange={(event) => setInitialStateJson(event.target.value)}
              rows={8}
              spellCheck={false}
              required
            />
          </div>
        ) : null}

        {inputError === null ? null : <p className="inline-error" role="alert">{inputError}</p>}
        <div className="composer-toolbar">
          <div className="composer-context"><FolderGit2 aria-hidden="true" size={15} /><span>当前工作区</span></div>
          <div className="composer-actions">
            <button
              className="advanced-toggle"
              type="button"
              aria-label="高级运行参数"
              title="高级运行参数"
              aria-expanded={advanced}
              onClick={() => setAdvanced((value) => !value)}
            >
              <ChevronDown aria-hidden="true" size={16} />
            </button>
            <button className="primary-command" type="submit" disabled={starting} aria-label="运行 Agent" title="运行 Agent">
              <Send aria-hidden="true" size={17} />
            </button>
          </div>
        </div>
      </form>
      <span className="sr-only" data-testid="run-status" aria-live="polite">{controller.run?.status ?? 'IDLE'}</span>
    </section>
  )
}
