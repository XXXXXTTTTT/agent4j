import { Play, RefreshCw } from 'lucide-react'
import { type FormEvent, useState } from 'react'

import { decodeAgentState } from '../api/runApi'
import type { UseRunWorkbenchResult } from '../hooks/useRunWorkbench'

interface RunLauncherProps {
  controller: UseRunWorkbenchResult
}

const DEFAULT_STATE = '{"messages":[],"variables":{},"trace":[]}'

/** 收集图标识与初始状态，并在浏览器侧执行严格协议校验。 */
export function RunLauncher({ controller }: RunLauncherProps) {
  const [graphId, setGraphId] = useState('')
  const [initialStateJson, setInitialStateJson] = useState(DEFAULT_STATE)
  const [inputError, setInputError] = useState<string | null>(null)
  const [starting, setStarting] = useState(false)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setInputError(null)
    setStarting(true)
    try {
      const parsed = JSON.parse(initialStateJson) as unknown
      await controller.start(graphId, decodeAgentState(parsed, 'initialState'))
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

        {inputError === null ? null : <p className="inline-error" role="alert">{inputError}</p>}
        <button className="primary-command" type="submit" disabled={starting}>
          <Play aria-hidden="true" size={17} fill="currentColor" />
          {starting ? '正在启动' : '启动 Run'}
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
