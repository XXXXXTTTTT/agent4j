import parseDiff from 'parse-diff'

export interface UnifiedDiffFile {
  path: string
  original: string
  modified: string
}

export class UnifiedDiffError extends Error {
  constructor(
    message: string,
    readonly rawDiff: string,
    options?: ErrorOptions,
  ) {
    super(message, options)
    this.name = 'UnifiedDiffError'
  }
}

function selectPath(file: parseDiff.File, rawDiff: string): string {
  const path = file.to !== '/dev/null' ? file.to : file.from
  if (path === undefined || path === '/dev/null' || path.length === 0) {
    throw new UnifiedDiffError('Unified Diff 文件缺少有效路径', rawDiff)
  }
  return path
}

export function parseUnifiedDiff(rawDiff: string): UnifiedDiffFile[] {
  if (typeof rawDiff !== 'string' || rawDiff.trim().length === 0) {
    throw new UnifiedDiffError('Unified Diff 不能为空', rawDiff)
  }
  try {
    const files = parseDiff(rawDiff)
    if (files.length === 0) {
      throw new UnifiedDiffError('Unified Diff 未包含文件', rawDiff)
    }
    return files.map((file) => {
      let original = ''
      let modified = ''
      for (const chunk of file.chunks) {
        for (const change of chunk.changes) {
          if (change.content.length === 0) continue
          const line = `${change.content.slice(1)}\n`
          if (change.type !== 'add') original += line
          if (change.type !== 'del') modified += line
        }
      }
      return { path: selectPath(file, rawDiff), original, modified }
    })
  } catch (error) {
    if (error instanceof UnifiedDiffError) throw error
    throw new UnifiedDiffError('Unified Diff 解析失败', rawDiff, { cause: error })
  }
}
