import type { SerializedDockview } from 'dockview-react'

export const WORKBENCH_DOCK_LAYOUT_STORAGE_KEY = 'agent4j.dockview.layout.v1'
export const WORKBENCH_DOCK_LAYOUT_VERSION = 1

export const WORKBENCH_DOCK_PANEL_IDS = ['activity', 'sidebar', 'conversation', 'inspector'] as const

export type WorkbenchDockPanelId = typeof WORKBENCH_DOCK_PANEL_IDS[number]

interface DockPanelRegistration {
  id: WorkbenchDockPanelId
  component: WorkbenchDockPanelId
  title: string
  initialWidth?: number
  minimumWidth?: number
  maximumWidth?: number
  inactive?: boolean
  position?: {
    referencePanel: WorkbenchDockPanelId
    direction: 'right' | 'within'
  }
}

interface DockPanelRegistrar {
  addPanel(options: DockPanelRegistration): unknown
}

interface PersistedDockviewLayout {
  version: number
  layout: SerializedDockview
}

/** 以固定顺序注册 VS Code 风格的四个默认区域。 */
export function registerDefaultWorkbenchPanels(api: DockPanelRegistrar): void {
  api.addPanel({
    id: 'activity',
    component: 'activity',
    title: '活动',
    initialWidth: 56,
    minimumWidth: 48,
    maximumWidth: 56,
  })
  api.addPanel({
    id: 'sidebar',
    component: 'sidebar',
    title: '项目与会话',
    initialWidth: 320,
    minimumWidth: 280,
    maximumWidth: 380,
    inactive: true,
    position: { referencePanel: 'activity', direction: 'right' },
  })
  api.addPanel({
    id: 'conversation',
    component: 'conversation',
    title: '对话',
    inactive: true,
    position: { referencePanel: 'sidebar', direction: 'right' },
  })
  api.addPanel({
    id: 'inspector',
    component: 'inspector',
    title: '执行详情',
    initialWidth: 480,
    minimumWidth: 320,
    maximumWidth: 480,
    inactive: true,
    position: { referencePanel: 'conversation', direction: 'right' },
  })
}

/** 窄屏以同一标签组呈现区域，防止桌面多列压缩为不可操作的横向内容。 */
export function registerCompactWorkbenchPanels(api: DockPanelRegistrar): void {
  api.addPanel({ id: 'conversation', component: 'conversation', title: '对话' })
  api.addPanel({
    id: 'sidebar',
    component: 'sidebar',
    title: '项目与会话',
    inactive: true,
    position: { referencePanel: 'conversation', direction: 'within' },
  })
  api.addPanel({
    id: 'inspector',
    component: 'inspector',
    title: '执行详情',
    inactive: true,
    position: { referencePanel: 'conversation', direction: 'within' },
  })
  api.addPanel({
    id: 'activity',
    component: 'activity',
    title: '活动',
    inactive: true,
    position: { referencePanel: 'conversation', direction: 'within' },
  })
}

/** 只接受本工作台写出的、没有未知面板的 Dockview 结构。 */
export function parsePersistedWorkbenchDockLayout(raw: string | null): SerializedDockview | null {
  if (raw === null) return null
  try {
    const persisted: unknown = JSON.parse(raw)
    if (!isPersistedDockviewLayout(persisted)) return null
    return persisted.layout
  } catch {
    return null
  }
}

/** 将 Dockview 序列化结果加入显式版本，以便今后安全演进。 */
export function serializeWorkbenchDockLayout(layout: SerializedDockview): string {
  return JSON.stringify({
    version: WORKBENCH_DOCK_LAYOUT_VERSION,
    layout,
  } satisfies PersistedDockviewLayout)
}

function isPersistedDockviewLayout(value: unknown): value is PersistedDockviewLayout {
  if (!isRecord(value) || value.version !== WORKBENCH_DOCK_LAYOUT_VERSION || !isRecord(value.layout)) return false
  if (!isRecord(value.layout.grid) || !isRecord(value.layout.panels)) return false
  return Object.keys(value.layout.panels).every((panelId) => isWorkbenchDockPanelId(panelId))
}

function isWorkbenchDockPanelId(value: string): value is WorkbenchDockPanelId {
  return WORKBENCH_DOCK_PANEL_IDS.includes(value as WorkbenchDockPanelId)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
