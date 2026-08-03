import { Pencil, ShieldCheck, ShieldX } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'

import type { RunView } from '../api/contracts'
import type { UseRunWorkbenchResult } from '../hooks/useRunWorkbench'

interface ApprovalDialogProps {
  run: RunView
  decide: UseRunWorkbenchResult['decide']
}

/** 仅开放中断详情与权威变量中同时存在的字段。 */
export function ApprovalDialog({ run, decide }: ApprovalDialogProps) {
  const interrupt = run.interruptRequest
  const editableKeys = useMemo(() => {
    if (interrupt === null) return []
    return Object.keys(interrupt.details).filter((key) =>
      Object.hasOwn(run.state.variables, key),
    )
  }, [interrupt, run.state.variables])
  const [reason, setReason] = useState('')
  const [editing, setEditing] = useState(false)
  const [updates, setUpdates] = useState<Record<string, string>>({})

  useEffect(() => {
    setReason('')
    setEditing(false)
    setUpdates(Object.fromEntries(
      editableKeys.map((key) => [key, run.state.variables[key]]),
    ))
  }, [editableKeys, run.runId, run.version, run.state.variables])

  if (run.status !== 'WAITING_APPROVAL' || interrupt === null) return null

  function submit(decision: 'APPROVE' | 'REJECT', variableUpdates: Record<string, string>) {
    void decide({
      decision,
      expectedVersion: run.version,
      reason,
      variableUpdates,
    }).catch(() => undefined)
  }

  return (
    <section
      className="approval-dialog"
      data-testid="approval-dialog"
      role="dialog"
      aria-modal="false"
      aria-labelledby="approval-title"
    >
      <div className="approval-heading">
        <div>
          <p className="section-kicker">HUMAN CHECKPOINT</p>
          <h2 id="approval-title">操作审批</h2>
        </div>
        <span className="risk-badge">需确认</span>
      </div>
      <p className="approval-reason">{interrupt.reason}</p>
      <dl className="approval-details">
        <div><dt>节点</dt><dd>{interrupt.nodeName}</dd></div>
        {Object.entries(interrupt.details).map(([key, value]) => (
          <div key={key}><dt>{key}</dt><dd>{value}</dd></div>
        ))}
      </dl>

      {editing ? (
        <div className="approval-updates">
          {editableKeys.map((key) => (
            <label key={key}>
              <span>{key}</span>
              <input
                aria-label={key}
                value={updates[key] ?? ''}
                onChange={(event) => setUpdates((current) => ({
                  ...current,
                  [key]: event.target.value,
                }))}
              />
            </label>
          ))}
        </div>
      ) : null}

      <label className="field-label" htmlFor="approval-note">审批说明</label>
      <textarea
        id="approval-note"
        value={reason}
        onChange={(event) => setReason(event.target.value)}
        rows={3}
      />

      <div className="approval-actions">
        <button
          className="approve-command"
          type="button"
          disabled={reason.trim().length === 0}
          onClick={() => submit('APPROVE', {})}
        >
          <ShieldCheck aria-hidden="true" size={16} />批准
        </button>
        {editing ? (
          <button
            className="modify-command"
            type="button"
            disabled={reason.trim().length === 0}
            onClick={() => submit('APPROVE', Object.fromEntries(
              editableKeys.map((key) => [key, updates[key] ?? '']),
            ))}
          >
            <ShieldCheck aria-hidden="true" size={16} />批准修改
          </button>
        ) : (
          <button className="modify-command" type="button" onClick={() => setEditing(true)}>
            <Pencil aria-hidden="true" size={16} />修改
          </button>
        )}
        <button
          className="reject-command"
          type="button"
          disabled={reason.trim().length === 0}
          onClick={() => submit('REJECT', {})}
        >
          <ShieldX aria-hidden="true" size={16} />拒绝
        </button>
      </div>
    </section>
  )
}
