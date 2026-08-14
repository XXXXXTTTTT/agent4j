import { Camera, FileCode2 } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'

import type { RunView } from '../api/contracts'
import { Editor } from '../monaco/MonacoEditors'

interface ReviewEvidencePanelProps {
  run: RunView | null
  history: RunView[]
}

type EvidenceTab = 'screenshot' | 'dom'
const PNG_DATA_URL = /^data:image\/png;base64,[A-Za-z0-9+/]+={0,2}$/

/** 按 Checkpoint 版本展示 ReviewerNode 的截图与纯文本 DOM 证据。 */
export function ReviewEvidencePanel({ run, history }: ReviewEvidencePanelProps) {
  const evidenceRuns = useMemo(() => {
    const byVersion = new Map<number, RunView>()
    for (const item of history) byVersion.set(item.version, item)
    if (run !== null) byVersion.set(run.version, run)
    return [...byVersion.values()].sort((left, right) => left.version - right.version)
  }, [history, run])
  const [selectedVersion, setSelectedVersion] = useState<number | null>(run?.version ?? null)
  const [tab, setTab] = useState<EvidenceTab>('screenshot')

  useEffect(() => {
    setSelectedVersion(run?.version ?? null)
  }, [run?.runId, run?.version])

  useEffect(() => {
    setTab('screenshot')
  }, [run?.runId, run?.version])

  const selected = evidenceRuns.find((item) => item.version === selectedVersion)
    ?? run
    ?? evidenceRuns[evidenceRuns.length - 1]
  const variables = selected?.state.variables ?? {}
  const screenshot = variables['reviewer.screenshotDataUrl']
  const dom = variables['reviewer.dom'] ?? ''
  const finalUrl = variables['reviewer.finalUrl']
  const summary = variables['reviewer.summary']
  const feedback = variables['reviewer.feedback']
  const model = variables['reviewer.model']
  const reviewerError = variables['reviewer.error']
  const reviewerRequest = variables['reviewer.request']
  const reviewerResponse = variables['reviewer.response']
  const validScreenshot = screenshot !== undefined && PNG_DATA_URL.test(screenshot)

  return (
    <section className="tool-panel review-panel" data-testid="review-panel" aria-label="审查证据">
      <div className="tool-panel-bar evidence-bar">
        <div className="panel-title"><Camera aria-hidden="true" size={16} /><span>审查证据</span></div>
        <div className="version-switcher" aria-label="证据版本">
          {evidenceRuns.map((item) => (
            <button
              type="button"
              key={item.version}
              className={item.version === selected?.version ? 'is-active' : ''}
              onClick={() => setSelectedVersion(item.version)}
            >
              版本 {item.version}
            </button>
          ))}
        </div>
      </div>
      <div className="evidence-tabs" role="tablist" aria-label="审查证据类型">
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'screenshot'}
          onClick={() => setTab('screenshot')}
        >
          <Camera aria-hidden="true" size={15} />截图
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'dom'}
          onClick={() => setTab('dom')}
        >
          <FileCode2 aria-hidden="true" size={15} />DOM
        </button>
      </div>
      {summary === undefined && feedback === undefined && model === undefined && reviewerError === undefined ? null : (
        <div className={`review-result ${reviewerError === undefined ? '' : 'is-error'}`}>
          <div className="review-result-heading">
            <strong>Reviewer 结论</strong>
            {model === undefined ? null : <code>{model}</code>}
          </div>
          {summary === undefined ? null : <p>{summary}</p>}
          {feedback === undefined ? null : <p>{feedback}</p>}
          {reviewerError === undefined ? null : <pre>{reviewerError}</pre>}
        </div>
      )}
      {finalUrl === undefined ? null : <p className="evidence-url">{finalUrl}</p>}
      {reviewerRequest === undefined && reviewerResponse === undefined ? null : (
        <details className="evidence-details review-call-details">
          <summary>Reviewer 模型调用</summary>
          {reviewerRequest === undefined ? null : <pre>{reviewerRequest}</pre>}
          {reviewerResponse === undefined ? null : <pre>{reviewerResponse}</pre>}
        </details>
      )}
      <div className="evidence-content">
        {tab === 'screenshot' ? (
          validScreenshot ? (
            <img
              src={screenshot}
              alt={`版本 ${selected?.version ?? 0} 审查截图`}
            />
          ) : (
            <div className="empty-tool-state">
              {screenshot === undefined ? '等待 ReviewerNode 截图' : '截图格式无效'}
            </div>
          )
        ) : (
          <Editor
            height="100%"
            value={dom}
            language="html"
            theme="vs"
            options={{
              readOnly: true,
              minimap: { enabled: false },
              scrollBeyondLastLine: false,
              wordWrap: 'on',
              fontSize: 13,
              automaticLayout: true,
            }}
          />
        )}
      </div>
    </section>
  )
}
