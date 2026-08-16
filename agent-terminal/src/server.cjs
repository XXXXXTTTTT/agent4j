const fs = require('node:fs')
const http = require('node:http')
const path = require('node:path')
const pty = require('node-pty')
const { WebSocketServer, WebSocket } = require('ws')
const { decodeClientMessage, resolveWorkspaceCwd, isAllowedOrigin, isContainedPath } = require('./protocol.cjs')

const host = '127.0.0.1'
const port = Number.parseInt(process.env.AGENT_TERMINAL_BRIDGE_PORT ?? '8090', 10)
const workspaceRoot = process.env.AGENT_TERMINAL_WORKSPACE_ROOT
const containerWorkspaceRoot = process.env.AGENT_TERMINAL_CONTAINER_WORKSPACE_ROOT ?? '/agent-workspace'
const shell = process.env.AGENT_TERMINAL_SHELL ?? 'powershell.exe'
const allowedOrigins = process.env.AGENT_TERMINAL_ALLOWED_ORIGINS ?? 'http://localhost:8080,http://127.0.0.1:8080'
const maxConcurrentPtys = Number.parseInt(process.env.AGENT_TERMINAL_MAX_CONCURRENT_PTY ?? '4', 10)
const maxBufferedOutputBytes = Number.parseInt(process.env.AGENT_TERMINAL_MAX_BUFFERED_OUTPUT_BYTES ?? '4194304', 10)

if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('AGENT_TERMINAL_BRIDGE_PORT 必须是有效端口')
if (process.platform !== 'win32') throw new Error('PowerShell PTY 桥接必须运行在 Windows 宿主机')
if (workspaceRoot === undefined || workspaceRoot.trim().length === 0) throw new Error('AGENT_TERMINAL_WORKSPACE_ROOT 不能为空')
if (!Number.isInteger(maxConcurrentPtys) || maxConcurrentPtys < 1 || maxConcurrentPtys > 32) throw new Error('AGENT_TERMINAL_MAX_CONCURRENT_PTY 必须在 1 到 32 之间')
if (!Number.isInteger(maxBufferedOutputBytes) || maxBufferedOutputBytes < 65536 || maxBufferedOutputBytes > 67108864) throw new Error('AGENT_TERMINAL_MAX_BUFFERED_OUTPUT_BYTES 超出允许范围')

const workspaceRootAbsolute = path.resolve(workspaceRoot)
if (!fs.statSync(workspaceRootAbsolute).isDirectory()) throw new Error('AGENT_TERMINAL_WORKSPACE_ROOT 必须是现有目录')
const workspaceRootReal = fs.realpathSync(workspaceRootAbsolute)
const activeTerminals = new Set()

function shellArguments(executable) {
  const name = path.basename(executable).toLowerCase()
  return name === 'powershell.exe' || name === 'pwsh.exe' ? ['-NoLogo', '-NoProfile'] : []
}

function createPty(cwd) {
  return pty.spawn(shell, shellArguments(shell), {
    name: 'xterm-256color',
    cols: 80,
    rows: 30,
    cwd,
    env: { ...process.env, TERM: 'xterm-256color', COLORTERM: 'truecolor' },
  })
}

const server = http.createServer((request, response) => {
  response.writeHead(404).end()
})
const webSocketServer = new WebSocketServer({
  server,
  path: '/ws/terminal',
  perMessageDeflate: false,
  maxPayload: 65536,
})

webSocketServer.on('connection', (socket, request) => {
  if (!isAllowedOrigin(request.headers.origin, allowedOrigins)) {
    socket.close(1008, '终端来源未获允许')
    return
  }
  if (activeTerminals.size >= maxConcurrentPtys) {
    socket.close(1013, '终端连接数已达上限')
    return
  }
  const requestUrl = new URL(request.url, `http://${host}:${port}`)
  const workspacePath = requestUrl.searchParams.get('workspacePath')
  let terminal
  let exited = false
  try {
    const cwd = resolveWorkspaceCwd(workspaceRootAbsolute, containerWorkspaceRoot, workspacePath)
    if (!fs.statSync(cwd).isDirectory()) throw new Error('终端工作区目录不存在')
    const realCwd = fs.realpathSync(cwd)
    if (!isContainedPath(workspaceRootReal, realCwd)) throw new Error('终端工作区真实路径必须位于配置工作区根目录内')
    terminal = createPty(realCwd)
  } catch (error) {
    socket.close(1008, error instanceof Error ? error.message.slice(0, 120) : 'terminal setup failed')
    return
  }
  activeTerminals.add(terminal)
  const closeForBackpressure = () => {
    if (socket.readyState === WebSocket.OPEN) socket.close(1013, '终端输出缓冲区已达上限')
    if (!exited) terminal.kill()
  }

  const output = terminal.onData((data) => {
    if (socket.readyState !== WebSocket.OPEN) return
    if (socket.bufferedAmount + Buffer.byteLength(data, 'utf8') > maxBufferedOutputBytes) {
      closeForBackpressure()
      return
    }
    socket.send(data)
  })
  const exit = terminal.onExit(({ exitCode }) => {
    exited = true
    output.dispose()
    activeTerminals.delete(terminal)
    if (socket.readyState === WebSocket.OPEN) socket.close(1000, `exit ${exitCode}`)
  })
  socket.on('message', (payload, isBinary) => {
    if (isBinary) {
      socket.close(1003, 'binary input is not supported')
      return
    }
    try {
      const message = decodeClientMessage(payload.toString('utf8'))
      if (message.type === 'input') terminal.write(message.data)
      else terminal.resize(message.cols, message.rows)
    } catch (error) {
      socket.close(1008, error instanceof Error ? error.message.slice(0, 120) : 'invalid terminal message')
    }
  })
  socket.on('close', () => {
    output.dispose()
    exit.dispose()
    activeTerminals.delete(terminal)
    if (!exited) terminal.kill()
  })
})

server.listen(port, host, () => {
  process.stdout.write(`Agent4J PowerShell PTY bridge listening at ws://${host}:${port}/ws/terminal\n`)
})
