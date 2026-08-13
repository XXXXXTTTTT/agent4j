import type { Agent4jDesktopBridge, ProjectArchive } from '../shared/desktop-bridge.js'

export interface ContextBridgeLike {
  exposeInMainWorld(key: string, value: Agent4jDesktopBridge): void
}

export interface IpcRendererLike {
  invoke(channel: 'agent4j:select-project-archive'): Promise<ProjectArchive | null>
}

/** 在 preload 中构造唯一允许暴露给渲染进程的桥。 */
export function createDesktopBridge(selectProjectArchive: () => Promise<ProjectArchive | null>): Agent4jDesktopBridge {
  return { selectProjectArchive }
}

/** 将固定 channel 的调用包装成唯一可公开的桥函数。 */
export function createDesktopBridgeFromInvoker(invoke: (channel: 'agent4j:select-project-archive') => Promise<ProjectArchive | null>): Agent4jDesktopBridge {
  return createDesktopBridge(() => invoke('agent4j:select-project-archive'))
}

/** 安装唯一的 contextBridge 暴露项，便于在 Electron 入口外验证白名单。 */
export function installDesktopBridge(contextBridge: ContextBridgeLike, ipcRenderer: IpcRendererLike): void {
  contextBridge.exposeInMainWorld('agent4jDesktop', createDesktopBridgeFromInvoker((channel) => ipcRenderer.invoke(channel)))
}
