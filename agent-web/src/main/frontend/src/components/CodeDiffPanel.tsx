import { FileCode2 } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'

import { parseUnifiedDiff, type UnifiedDiffFile } from '../diff/unifiedDiff'
import { DiffEditor } from '../monaco/MonacoEditors'

interface CodeDiffPanelProps {
  unifiedDiff: string | undefined
}

function useNarrowLayout(): boolean {
  const query = '(max-width: 900px)'
  const [narrow, setNarrow] = useState(() =>
    typeof window.matchMedia === 'function' ? window.matchMedia(query).matches : false,
  )
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return undefined
    const media = window.matchMedia(query)
    const update = () => setNarrow(media.matches)
    media.addEventListener('change', update)
    return () => media.removeEventListener('change', update)
  }, [])
  return narrow
}

/** 将 Unified Diff 拆分为可选择的只读 Monaco 文件视图。 */
export function CodeDiffPanel({ unifiedDiff }: CodeDiffPanelProps) {
  const parsed = useMemo<{ files: UnifiedDiffFile[]; error: string | null }>(() => {
    if (unifiedDiff === undefined || unifiedDiff.length === 0) return { files: [], error: null }
    try {
      return { files: parseUnifiedDiff(unifiedDiff), error: null }
    } catch (error) {
      return { files: [], error: error instanceof Error ? error.message : String(error) }
    }
  }, [unifiedDiff])
  const [selectedPath, setSelectedPath] = useState('')
  const narrow = useNarrowLayout()

  useEffect(() => {
    if (!parsed.files.some((file) => file.path === selectedPath)) {
      setSelectedPath(parsed.files[0]?.path ?? '')
    }
  }, [parsed.files, selectedPath])

  const selected = parsed.files.find((file) => file.path === selectedPath) ?? parsed.files[0]

  return (
    <section className="tool-panel code-panel" data-testid="code-panel" aria-label="代码变更">
      <div className="tool-panel-bar">
        <div className="panel-title"><FileCode2 aria-hidden="true" size={16} /><span>变更文件</span></div>
        {parsed.files.length === 0 ? null : (
          <label className="file-select">
            <span>Diff 文件</span>
            <select
              aria-label="Diff 文件"
              value={selected?.path ?? ''}
              onChange={(event) => setSelectedPath(event.target.value)}
            >
              {parsed.files.map((file) => <option key={file.path} value={file.path}>{file.path}</option>)}
            </select>
          </label>
        )}
      </div>
      {parsed.error === null ? null : <p className="panel-message is-error">{parsed.error}</p>}
      {selected === undefined ? (
        <div className="empty-tool-state">等待 CoderNode 生成 Unified Diff</div>
      ) : (
        <DiffEditor
          height="100%"
          original={selected.original}
          modified={selected.modified}
          language="java"
          theme="vs"
          options={{
            readOnly: true,
            originalEditable: false,
            renderSideBySide: !narrow,
            minimap: { enabled: false },
            scrollBeyondLastLine: false,
            fontSize: 13,
            automaticLayout: true,
          }}
        />
      )}
    </section>
  )
}
