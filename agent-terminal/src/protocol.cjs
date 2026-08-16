const path = require('node:path')

function exactKeys(value, expected) {
  const actual = Object.keys(value).sort()
  const required = [...expected].sort()
  if (actual.length !== required.length || actual.some((key, index) => key !== required[index])) {
    throw new Error(`终端控制帧字段不匹配: ${actual.join(',')}`)
  }
}

function decodeClientMessage(payload) {
  let value
  try {
    value = JSON.parse(payload)
  } catch {
    throw new Error('终端控制帧必须是 JSON 对象')
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value) || typeof value.type !== 'string') {
    throw new Error('终端控制帧必须包含 type')
  }
  if (value.type === 'input') {
    exactKeys(value, ['type', 'data'])
    if (typeof value.data !== 'string' || value.data.length === 0 || value.data.length > 65536) throw new Error('终端输入长度必须在 1 到 65536 之间')
    return { type: 'input', data: value.data }
  }
  if (value.type === 'resize') {
    exactKeys(value, ['type', 'cols', 'rows'])
    if (!Number.isInteger(value.cols) || !Number.isInteger(value.rows) || value.cols < 2 || value.cols > 500 || value.rows < 1 || value.rows > 300) throw new Error('终端尺寸超出允许范围')
    return { type: 'resize', cols: value.cols, rows: value.rows }
  }
  throw new Error(`终端控制帧 type 未知: ${value.type}`)
}

function resolveWorkspaceCwd(hostRoot, containerRoot, workspacePath) {
  if (typeof hostRoot !== 'string' || hostRoot.length === 0 || typeof containerRoot !== 'string' || containerRoot.length === 0 || typeof workspacePath !== 'string' || workspacePath.length === 0) {
    throw new Error('终端工作区路径配置不完整')
  }
  const normalizedRoot = path.posix.normalize(containerRoot)
  const normalizedWorkspace = path.posix.normalize(workspacePath)
  const relative = path.posix.relative(normalizedRoot, normalizedWorkspace)
  if (relative === '..' || relative.startsWith('../') || path.posix.isAbsolute(relative)) throw new Error('终端工作区必须位于配置工作区根目录内')
  const hostRootAbsolute = path.resolve(hostRoot)
  const candidate = path.resolve(hostRootAbsolute, ...relative.split('/').filter(Boolean))
  const contained = candidate === hostRootAbsolute || candidate.startsWith(`${hostRootAbsolute}${path.sep}`)
  if (!contained) throw new Error('终端工作区必须位于配置工作区根目录内')
  return candidate
}

function isAllowedOrigin(origin, configuredOrigins) {
  if (typeof origin !== 'string' || typeof configuredOrigins !== 'string') return false
  return configuredOrigins.split(',').map((value) => value.trim()).filter(Boolean).includes(origin)
}

function isContainedPath(root, candidate) {
  const relative = path.relative(path.resolve(root), path.resolve(candidate))
  return relative === '' || (relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative))
}

module.exports = { decodeClientMessage, resolveWorkspaceCwd, isAllowedOrigin, isContainedPath }
