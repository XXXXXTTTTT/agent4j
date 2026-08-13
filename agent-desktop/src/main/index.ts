import { app, BrowserWindow, dialog, ipcMain } from 'electron'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { probeBackend } from './backend-health-probe.js'
import { archiveProjectDirectory } from './local-project-archive-service.js'
import { LOCAL_BACKEND_ORIGIN, desktopWindowOptions, isAllowedDesktopBridgeOrigin, isAllowedNavigation, offlineScreenUrl } from './window-policy.js'

const currentDirectory = path.dirname(fileURLToPath(import.meta.url))
const preloadPath = path.join(currentDirectory, '../preload/index.js')
const readinessUrl = `${LOCAL_BACKEND_ORIGIN}/actuator/health/readiness`
let window: BrowserWindow | null = null
let retryTimer: NodeJS.Timeout | null = null
let refreshInFlight = false

/** 将 IPC 边界的系统异常转换为不包含宿主路径的稳定提示。 */
export function desktopImportErrorMessage(): string {
  return '无法归档所选项目，请检查项目内容后重试'
}

function clearRetryTimer(): void {
  if (retryTimer !== null) clearInterval(retryTimer)
  retryTimer = null
}

async function showConnectedWorkbench(): Promise<void> {
  if (window === null) return
  try {
    await window.loadURL(LOCAL_BACKEND_ORIGIN)
    clearRetryTimer()
  } catch {
    await showOfflineWorkbench('本地服务连接中断，正在重试')
  }
}

async function showOfflineWorkbench(detail: string): Promise<void> {
  if (window === null) return
  try {
    if (!window.webContents.getURL().startsWith('data:text/html')) await window.loadURL(offlineScreenUrl(detail))
    if (retryTimer === null) retryTimer = setInterval(() => { void refreshConnection().catch(() => undefined) }, 3_000)
  } catch {
    if (window === null) return
  }
}

async function refreshConnection(): Promise<void> {
  if (refreshInFlight || window === null) return
  refreshInFlight = true
  try {
    const health = await probeBackend(fetch, readinessUrl)
    if (health.available) {
      await showConnectedWorkbench()
      return
    }
    await showOfflineWorkbench(health.detail)
  } finally {
    refreshInFlight = false
  }
}

async function createWindow(): Promise<void> {
  window = new BrowserWindow(desktopWindowOptions(preloadPath))
  window.on('closed', () => {
    window = null
    clearRetryTimer()
  })
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
  window.webContents.on('will-navigate', (event, url) => {
    if (!isAllowedNavigation(url)) event.preventDefault()
  })
  window.webContents.on('will-redirect', (event, url) => {
    if (!isAllowedNavigation(url)) event.preventDefault()
  })
  window.once('ready-to-show', () => window?.show())
  await refreshConnection().catch(() => undefined)
}

function registerIpc(): void {
  ipcMain.handle('agent4j:select-project-archive', async (event) => {
    if (event.senderFrame === null || !isAllowedDesktopBridgeOrigin(event.senderFrame.url)) {
      throw new Error('桌面目录导入仅允许由本地 Agent4J 工作台调用')
    }
    const selected = await dialog.showOpenDialog({ properties: ['openDirectory'] })
    if (selected.canceled || selected.filePaths.length !== 1) return null
    try {
      return await archiveProjectDirectory(selected.filePaths[0])
    } catch {
      throw new Error(desktopImportErrorMessage())
    }
  })
}

app.whenReady().then(async () => {
  registerIpc()
  await createWindow()
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) void createWindow() })
})

app.on('window-all-closed', () => {
  clearRetryTimer()
  if (process.platform !== 'darwin') app.quit()
})
