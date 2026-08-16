import { describe, expect, it, vi } from 'vitest'

import {
  parsePersistedWorkbenchDockLayout,
  registerCompactWorkbenchPanels,
  registerDefaultWorkbenchPanels,
  serializeWorkbenchDockLayout,
  WORKBENCH_DOCK_LAYOUT_VERSION,
  WORKBENCH_DOCK_PANEL_IDS,
} from './workbenchDockLayoutPersistence'

function serializedLayout(panelIds: readonly string[]) {
  return {
    grid: { root: {}, height: 800, width: 1200, orientation: 'horizontal' },
    panels: Object.fromEntries(panelIds.map((id) => [id, { id }])),
  }
}

describe('workbench Dockview layout', () => {
  it('按稳定面板 ID 注册 VS Code 风格的默认四栏布局', () => {
    const addPanel = vi.fn()

    registerDefaultWorkbenchPanels({ addPanel })

    expect(addPanel).toHaveBeenCalledTimes(4)
    expect(addPanel.mock.calls.map(([options]) => options.id)).toEqual(WORKBENCH_DOCK_PANEL_IDS)
    expect(addPanel.mock.calls[1][0].position).toEqual({ referencePanel: 'activity', direction: 'right' })
    expect(addPanel.mock.calls[2][0].position).toEqual({ referencePanel: 'sidebar', direction: 'right' })
    expect(addPanel.mock.calls[3][0].position).toEqual({ referencePanel: 'conversation', direction: 'right' })
    expect(addPanel.mock.calls[0][0]).toMatchObject({ minimumWidth: 48, maximumWidth: 56 })
    expect(addPanel.mock.calls[1][0]).toMatchObject({ minimumWidth: 280, maximumWidth: 380 })
    expect(addPanel.mock.calls[3][0]).toMatchObject({ minimumWidth: 320, maximumWidth: 480 })
  })

  it('将窄屏工作台初始化为同一标签组而不是压缩的四列', () => {
    const addPanel = vi.fn()

    registerCompactWorkbenchPanels({ addPanel })

    expect(addPanel.mock.calls.map(([options]) => options.id)).toEqual(['conversation', 'sidebar', 'inspector', 'activity'])
    expect(addPanel.mock.calls.slice(1).every(([options]) => options.position?.direction === 'within')).toBe(true)
  })

  it('序列化后只恢复匹配当前版本及已知面板的布局', () => {
    const layout = serializedLayout(['activity', 'sidebar', 'conversation'])
    const raw = serializeWorkbenchDockLayout(layout as never)

    expect(JSON.parse(raw)).toMatchObject({ version: WORKBENCH_DOCK_LAYOUT_VERSION })
    expect(parsePersistedWorkbenchDockLayout(raw)).toEqual(layout)
    expect(parsePersistedWorkbenchDockLayout(JSON.stringify({ version: 99, layout }))).toBeNull()
    expect(parsePersistedWorkbenchDockLayout(JSON.stringify({
      version: WORKBENCH_DOCK_LAYOUT_VERSION,
      layout: serializedLayout(['unknown-panel']),
    }))).toBeNull()
  })

  it('保留关闭后的有效布局，并能由恢复默认操作重新注册所有面板', () => {
    const closedInspector = serializedLayout(['activity', 'sidebar', 'conversation'])
    expect(parsePersistedWorkbenchDockLayout(serializeWorkbenchDockLayout(closedInspector as never))).toEqual(closedInspector)

    const addPanel = vi.fn()
    registerDefaultWorkbenchPanels({ addPanel })
    expect(addPanel.mock.calls.map(([options]) => options.id)).toContain('inspector')
  })
})
