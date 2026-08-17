import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createElement, useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AppearanceProvider } from '../appearance/AppearanceProvider'
import { WorkbenchDockLayout } from './WorkbenchDockLayout'
import {
  parsePersistedWorkbenchDockLayout,
  registerCompactWorkbenchPanels,
  registerDefaultWorkbenchPanels,
  serializeWorkbenchDockLayout,
  WORKBENCH_DOCK_LAYOUT_STORAGE_KEY,
  WORKBENCH_DOCK_LAYOUT_VERSION,
  WORKBENCH_DOCK_PANEL_IDS,
} from './workbenchDockLayoutPersistence'

const dockview = vi.hoisted(() => ({ api: null as ReturnType<typeof createDockviewApi> | null }))

vi.mock('dockview-react', async () => {
  const React = await import('react')
  return {
    DockviewReact: ({ onReady }: { onReady(event: { api: ReturnType<typeof createDockviewApi> }): void }) => {
      React.useEffect(() => {
        if (dockview.api !== null) onReady({ api: dockview.api })
        // Dockview 仅在实例首次就绪时触发一次 ready，后续属性变更不会重复初始化。
        // eslint-disable-next-line react-hooks/exhaustive-deps
      }, [])
      return React.createElement('div', { 'data-testid': 'dockview-react' })
    },
    themeDark: {},
    themeLight: {},
  }
})

function serializedLayout(panelIds: readonly string[]) {
  return {
    grid: { root: {}, height: 800, width: 1200, orientation: 'horizontal' },
    panels: Object.fromEntries(panelIds.map((id) => [id, { id }])),
  }
}

function createDockviewApi() {
  const panels = new Map<string, { id: string; api: { close(): void; setActive(): void; title: string } }>()
  const closedPanelIds: string[] = []
  const mutationListeners = new Set<() => void>()
  const removeListeners = new Set<(panel: { id: string }) => void>()
  let layout = serializedLayout([])

  const notifyMutation = () => mutationListeners.forEach((listener) => listener())
  const updateLayoutFromPanels = () => {
    layout = serializedLayout(Array.from(panels.keys()))
  }
  const addPanelRecord = (id: string, title = id) => {
    const panel = {
      id,
      api: {
        close: () => {
          closedPanelIds.push(id)
          const removedPanel = panels.get(id)
          panels.delete(id)
          updateLayoutFromPanels()
          if (removedPanel !== undefined) removeListeners.forEach((listener) => listener(removedPanel))
          notifyMutation()
        },
        setActive: vi.fn(),
        title,
      },
    }
    panels.set(id, panel)
    return panel
  }
  const api = {
    activePanel: undefined,
    get panels() {
      return Array.from(panels.values())
    },
    getPanel(id: string) {
      return panels.get(id)
    },
    addPanel: vi.fn((options: { id: string; title: string }) => {
      const panel = addPanelRecord(options.id, options.title)
      updateLayoutFromPanels()
      notifyMutation()
      return panel
    }),
    clear: vi.fn(() => {
      const removedPanels = Array.from(panels.values())
      panels.clear()
      updateLayoutFromPanels()
      removedPanels.forEach((panel) => removeListeners.forEach((listener) => listener(panel)))
      notifyMutation()
    }),
    fromJSON: vi.fn((nextLayout: ReturnType<typeof serializedLayout>) => {
      panels.clear()
      Object.keys(nextLayout.panels).forEach((id) => addPanelRecord(id))
      layout = nextLayout
      notifyMutation()
    }),
    toJSON: vi.fn(() => layout),
    onDidMutateLayout: vi.fn((listener: () => void) => {
      mutationListeners.add(listener)
      return { dispose: () => mutationListeners.delete(listener) }
    }),
    onDidRemovePanel: vi.fn((listener: (panel: { id: string }) => void) => {
      removeListeners.add(listener)
      return { dispose: () => removeListeners.delete(listener) }
    }),
    emitMutation: notifyMutation,
    closedPanelIds,
  }
  return api
}

function installCompactMediaQuery(initialMatches: boolean) {
  let matches = initialMatches
  const listeners = new Set<() => void>()
  const originalMatchMedia = window.matchMedia
  window.matchMedia = vi.fn((query: string) => ({
    get matches() {
      return query === '(max-width: 760px)' ? matches : false
    },
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: (_type: string, listener: () => void) => {
      if (query === '(max-width: 760px)') listeners.add(listener)
    },
    removeEventListener: (_type: string, listener: () => void) => listeners.delete(listener),
    dispatchEvent: () => false,
  })) as typeof window.matchMedia

  return {
    setMatches(nextMatches: boolean) {
      matches = nextMatches
      listeners.forEach((listener) => listener())
    },
    restore() {
      window.matchMedia = originalMatchMedia
    },
  }
}

describe('workbench Dockview layout', () => {
  const originalUserAgent = navigator.userAgent

  beforeEach(() => {
    Object.defineProperty(navigator, 'userAgent', { configurable: true, value: 'test-browser' })
    window.localStorage.clear()
    dockview.api = createDockviewApi()
  })

  afterEach(() => {
    Object.defineProperty(navigator, 'userAgent', { configurable: true, value: originalUserAgent })
    dockview.api = null
  })

  it('按稳定面板 ID 注册 VS Code 风格的默认五栏布局', () => {
    const addPanel = vi.fn()

    registerDefaultWorkbenchPanels({ addPanel })

    expect(addPanel).toHaveBeenCalledTimes(5)
    expect(addPanel.mock.calls.map(([options]) => options.id)).toEqual(WORKBENCH_DOCK_PANEL_IDS)
    expect(addPanel.mock.calls[1][0].position).toEqual({ referencePanel: 'activity', direction: 'right' })
    expect(addPanel.mock.calls[2][0].position).toEqual({ referencePanel: 'sidebar', direction: 'right' })
    expect(addPanel.mock.calls[3][0].position).toEqual({ referencePanel: 'editor', direction: 'right' })
    expect(addPanel.mock.calls[0][0]).toMatchObject({ minimumWidth: 136, maximumWidth: 190 })
    expect(addPanel.mock.calls[1][0]).toMatchObject({ minimumWidth: 280, maximumWidth: 380 })
    expect(addPanel.mock.calls[4][0]).toMatchObject({ minimumWidth: 320, maximumWidth: 480 })
    expect(addPanel.mock.calls[2][0].id).toBe('editor')
  })

  it('将窄屏工作台初始化为同一标签组而不是压缩的四列', () => {
    const addPanel = vi.fn()

    registerCompactWorkbenchPanels({ addPanel })

    expect(addPanel.mock.calls.map(([options]) => options.id)).toEqual(['conversation', 'sidebar', 'inspector', 'editor', 'activity'])
    expect(addPanel.mock.calls.slice(1).every(([options]) => options.position?.direction === 'within')).toBe(true)
  })

  it('序列化后只恢复匹配当前版本及已知面板的布局', () => {
    const layout = serializedLayout(['activity', 'sidebar', 'conversation', 'editor'])
    const raw = serializeWorkbenchDockLayout(layout as never)

    expect(JSON.parse(raw)).toMatchObject({ version: WORKBENCH_DOCK_LAYOUT_VERSION })
    expect(parsePersistedWorkbenchDockLayout(raw)).toEqual(layout)
    expect(parsePersistedWorkbenchDockLayout(JSON.stringify({
      version: WORKBENCH_DOCK_LAYOUT_VERSION,
      layout: serializedLayout([]),
    }))).toBeNull()
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

  it('恢复默认布局后关闭已重新注册的编辑器面板且保留检查器面板', async () => {
    const user = userEvent.setup()
    const onInspectorOpenChange = vi.fn()
    const onEditorOpenChange = vi.fn()
    render(createElement(
      AppearanceProvider,
      null,
      createElement(WorkbenchDockLayout, {
        inspectorOpen: true,
        onInspectorOpenChange,
        editorOpen: true,
        onEditorOpenChange,
        panels: { activity: null, sidebar: null, editor: null, conversation: null, inspector: null },
      }),
    ))

    await screen.findByTestId('dockview-react')
    await user.click(screen.getByRole('button', { name: '恢复默认布局' }))

    expect(dockview.api?.closedPanelIds).toContain('editor')
    expect(dockview.api?.getPanel('editor')).toBeUndefined()
    expect(dockview.api?.getPanel('inspector')).toBeDefined()
    expect(onInspectorOpenChange).toHaveBeenLastCalledWith(true)
    expect(onEditorOpenChange).toHaveBeenLastCalledWith(false)
  })

  it('从窄屏返回桌面时恢复切换前保存的桌面自定义布局', async () => {
    const media = installCompactMediaQuery(false)
    const desktopLayout = serializedLayout(['sidebar', 'editor', 'conversation'])
    window.localStorage.setItem(WORKBENCH_DOCK_LAYOUT_STORAGE_KEY, serializeWorkbenchDockLayout(desktopLayout as never))

    try {
      render(createElement(
        AppearanceProvider,
        null,
        createElement(WorkbenchDockLayout, {
          inspectorOpen: false,
          onInspectorOpenChange: vi.fn(),
          editorOpen: true,
          onEditorOpenChange: vi.fn(),
          panels: { activity: null, sidebar: null, editor: null, conversation: null, inspector: null },
        }),
      ))
      await screen.findByTestId('dockview-react')

      act(() => media.setMatches(true))
      act(() => media.setMatches(false))

      expect(dockview.api?.fromJSON).toHaveBeenLastCalledWith(desktopLayout, { reuseExistingPanels: false })
      expect(dockview.api?.fromJSON).toHaveBeenCalledTimes(2)
      expect(parsePersistedWorkbenchDockLayout(
        window.localStorage.getItem(WORKBENCH_DOCK_LAYOUT_STORAGE_KEY),
      )).toEqual(desktopLayout)
    } finally {
      media.restore()
    }
  })

  it('响应式重建不向受控父组件泄漏面板关闭事件', async () => {
    const media = installCompactMediaQuery(false)
    const desktopLayout = serializedLayout(['activity', 'sidebar', 'editor', 'conversation', 'inspector'])
    const onInspectorOpenChange = vi.fn()
    const onEditorOpenChange = vi.fn()
    window.localStorage.setItem(WORKBENCH_DOCK_LAYOUT_STORAGE_KEY, serializeWorkbenchDockLayout(desktopLayout as never))

    function ControlledWorkbenchDockLayout() {
      const [inspectorOpen, setInspectorOpen] = useState(true)
      const [editorOpen, setEditorOpen] = useState(true)
      return createElement(WorkbenchDockLayout, {
        inspectorOpen,
        onInspectorOpenChange: (open: boolean) => {
          onInspectorOpenChange(open)
          setInspectorOpen(open)
        },
        editorOpen,
        onEditorOpenChange: (open: boolean) => {
          onEditorOpenChange(open)
          setEditorOpen(open)
        },
        panels: { activity: null, sidebar: null, editor: null, conversation: null, inspector: null },
      })
    }

    try {
      render(createElement(AppearanceProvider, null, createElement(ControlledWorkbenchDockLayout)))
      await screen.findByTestId('dockview-react')
      onInspectorOpenChange.mockClear()
      onEditorOpenChange.mockClear()

      act(() => media.setMatches(true))

      expect(onInspectorOpenChange).not.toHaveBeenCalled()
      expect(onEditorOpenChange).not.toHaveBeenCalled()

      act(() => media.setMatches(false))

      expect(dockview.api?.getPanel('inspector')).toBeDefined()
      expect(dockview.api?.getPanel('editor')).toBeDefined()
      expect(parsePersistedWorkbenchDockLayout(
        window.localStorage.getItem(WORKBENCH_DOCK_LAYOUT_STORAGE_KEY),
      )).toEqual(desktopLayout)
    } finally {
      media.restore()
    }
  })

  it('compact 布局不写入桌面快照且返回桌面后恢复正常持久化', async () => {
    const media = installCompactMediaQuery(false)
    const setItem = vi.spyOn(Storage.prototype, 'setItem')

    try {
      render(createElement(
        AppearanceProvider,
        null,
        createElement(WorkbenchDockLayout, {
          inspectorOpen: true,
          onInspectorOpenChange: vi.fn(),
          editorOpen: true,
          onEditorOpenChange: vi.fn(),
          panels: { activity: null, sidebar: null, editor: null, conversation: null, inspector: null },
        }),
      ))
      await screen.findByTestId('dockview-react')
      setItem.mockClear()

      act(() => media.setMatches(true))
      expect(setItem).not.toHaveBeenCalled()

      act(() => dockview.api?.emitMutation())
      expect(setItem).not.toHaveBeenCalled()

      act(() => media.setMatches(false))
      setItem.mockClear()
      act(() => dockview.api?.emitMutation())

      expect(setItem).toHaveBeenCalledTimes(1)
      expect(setItem).toHaveBeenLastCalledWith(
        WORKBENCH_DOCK_LAYOUT_STORAGE_KEY,
        serializeWorkbenchDockLayout(dockview.api?.toJSON() as never),
      )
    } finally {
      setItem.mockRestore()
      media.restore()
    }
  })

  it('用户关闭面板时仍向受控父组件发送关闭状态', async () => {
    const onEditorOpenChange = vi.fn()
    render(createElement(
      AppearanceProvider,
      null,
      createElement(WorkbenchDockLayout, {
        inspectorOpen: true,
        onInspectorOpenChange: vi.fn(),
        editorOpen: true,
        onEditorOpenChange,
        panels: { activity: null, sidebar: null, editor: null, conversation: null, inspector: null },
      }),
    ))
    await screen.findByTestId('dockview-react')
    onEditorOpenChange.mockClear()

    act(() => dockview.api?.getPanel('editor')?.api.close())

    expect(onEditorOpenChange).toHaveBeenCalledTimes(1)
    expect(onEditorOpenChange).toHaveBeenLastCalledWith(false)
  })

  it('jsdom 降级布局仍渲染包含编辑器的五个工作区面板', () => {
    Object.defineProperty(navigator, 'userAgent', { configurable: true, value: 'jsdom' })

    render(createElement(
      AppearanceProvider,
      null,
      createElement(WorkbenchDockLayout, {
        inspectorOpen: true,
        onInspectorOpenChange: vi.fn(),
        editorOpen: true,
        onEditorOpenChange: vi.fn(),
        panels: {
          activity: createElement('div', { 'data-testid': 'fallback-activity' }),
          sidebar: createElement('div', { 'data-testid': 'fallback-sidebar' }),
          editor: createElement('div', { 'data-testid': 'fallback-editor' }),
          conversation: createElement('div', { 'data-testid': 'fallback-conversation' }),
          inspector: createElement('div', { 'data-testid': 'fallback-inspector' }),
        },
      }),
    ))

    expect(screen.getAllByTestId(/^fallback-/)).toHaveLength(5)
    expect(screen.getByTestId('fallback-editor')).toBeVisible()
  })
})
