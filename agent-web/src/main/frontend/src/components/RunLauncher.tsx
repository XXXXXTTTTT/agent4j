import { ChevronDown, Play, RefreshCw } from 'lucide-react'
import { type FormEvent, useState } from 'react'

import { decodeAgentState } from '../api/runApi'
import type { UseRunWorkbenchResult } from '../hooks/useRunWorkbench'

interface RunLauncherProps {
  controller: UseRunWorkbenchResult
}

const DEFAULT_TASK = '请检查当前工作区，完成一次代码修改、测试和质量审查。'
const DEFAULT_STATE = '{"messages":[],"variables":{},"trace":[]}'

/** 收集图标识与初始状态，并在浏览器侧执行严格协议校验。 */
export function RunLauncher({ controller }: RunLauncherProps) {
  const [task, setTask] = useState(DEFAULT_TASK)
  const [graphId, setGraphId] = useState('demo-agent')
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
        await controller.start('demo-agent', decodeAgentState({
          messages: [],
          variables: {
            'demo.task': task.trim(),
            'demo.workspace': '当前工作区',
          },
          trace: [],
        }, 'initialState'))
      }
    } catch (error) {
      setInputError(error instanceof Error ? error.message : String(error))
    } finally {
      setStarting(false)
    }
  }

  return (
    <section className="run-launcher" data-testid="run-launcher" aria-labelledby="launcher-title">
      <div className="section-heading">
        <div>
          <p className="section-kicker">RUN CONTROL</p>
          <h2 id="launcher-title">执行入口</h2>
        </div>
        {controller.run === null ? null : (
          <button
            className="icon-button"
            type="button"
            title="刷新 Run"
            aria-label="刷新 Run"
            onClick={() => void controller.reload().catch(() => undefined)}
          >
            <RefreshCw aria-hidden="true" size={17} />
          </button>
        )}
      </div>

      <form onSubmit={(event) => void submit(event)}>
        <label className="field-label" htmlFor="task-description">任务描述</label>
        <textarea
          id="task-description"
          value={task}
          onChange={(event) => setTask(event.target.value)}
          rows={5}
          placeholder="例如：修复登录超时问题并运行测试"
          required
        />

        <div className="workspace-context">
          <span>工作区</span>
          <strong>当前工作区</strong>
        </div>

        <button
          className="advanced-toggle"
          type="button"
          aria-expanded={advanced}
          onClick={() => setAdvanced((value) => !value)}
        >
          <ChevronDown aria-hidden="true" size={15} />
          高级运行参数
        </button>
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
        <button className="primary-command" type="submit" disabled={starting}>
          <Play aria-hidden="true" size={17} fill="currentColor" />
          {starting ? '正在启动' : '运行 Agent'}
        </button>
      </form>

      <div className="run-readout" aria-live="polite">
        <span>STATUS</span>
        <strong data-testid="run-status">{controller.run?.status ?? 'IDLE'}</strong>
      </div>
      {controller.run === null ? null : (
        <dl className="run-metadata">
          <div><dt>Run</dt><dd>{controller.run.runId}</dd></div>
          <div><dt>Version</dt><dd>{controller.run.version}</dd></div>
        </dl>
      )}
    </section>
  )
}
