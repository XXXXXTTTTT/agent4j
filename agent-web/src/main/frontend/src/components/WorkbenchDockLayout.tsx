import {
  DockviewReact,
  type DockviewApi,
  type DockviewReadyEvent,
  type IDockviewPanelProps,
  type SerializedDockview,
  themeDark,
  themeLight,
} from 'dockview-react'
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'

import {
  parsePersistedWorkbenchDockLayout,
  registerCompactWorkbenchPanels,
  registerDefaultWorkbenchPanels,
  serializeWorkbenchDockLayout,
  WORKBENCH_DOCK_LAYOUT_STORAGE_KEY,
  type WorkbenchDockPanelId,
} from './workbenchDockLayoutPersistence'

import 'dockview/dist/styles/dockview.css'
import { useAppearance } from '../appearance/AppearanceProvider'

interface WorkbenchDockLayoutProps {
  panels: Record<WorkbenchDockPanelId, ReactNode>
  inspectorOpen: boolean
  onInspectorOpenChange(open: boolean): void
}

interface PanelContextValue {
  panels: Record<WorkbenchDockPanelId, ReactNode>
}

const PanelContext = createContext<PanelContextValue | null>(null)

function useCompactDockLayout(): boolean {
  const query = '(max-width: 760px)'
  const [compact, setCompact] = useState(() =>
    typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(query).matches,
  )

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return undefined
    const media = window.matchMedia(query)
    const update = () => setCompact(media.matches)
    update()
    media.addEventListener('change', update)
    return () => media.removeEventListener('change', update)
  }, [])

  return compact
}

function WorkbenchDockPanel({ params }: IDockviewPanelProps<{ panelId: WorkbenchDockPanelId }>) {
  const context = useContext(PanelContext)
  if (context === null) return null
  return (
    <div className={`dockview-workbench-panel dockview-workbench-panel-${params.panelId}`}>
      {context.panels[params.panelId]}
    </div>
  )
}

/** Dockview 外壳：管理四个工作区面板，业务状态由 Workbench 继续持有。 */
export function WorkbenchDockLayout({ panels, inspectorOpen, onInspectorOpenChange }: WorkbenchDockLayoutProps) {
  const { resolvedColorMode } = useAppearance()
  const apiRef = useRef<DockviewApi | null>(null)
  const [api, setApi] = useState<DockviewApi | null>(null)
  const compactLayout = useCompactDockLayout()
  const compactLayoutRef = useRef(compactLayout)
  const previousCompactLayoutRef = useRef(compactLayout)
  const contextValue = useMemo(() => ({ panels }), [panels])
  const dockTheme = resolvedColorMode === 'DARK' ? themeDark : themeLight

  const persist = useCallback((nextApi: DockviewApi) => {
    if (typeof window === 'undefined' || compactLayoutRef.current) return
    try {
      window.localStorage.setItem(WORKBENCH_DOCK_LAYOUT_STORAGE_KEY, serializeWorkbenchDockLayout(nextApi.toJSON()))
    } catch {
      // 隐私模式或受限 iframe 可能禁用 localStorage，布局仍可在当前页面使用。
    }
  }, [])

  useEffect(() => {
    compactLayoutRef.current = compactLayout
  }, [compactLayout])

  const registerPanels = useCallback((nextApi: DockviewApi, compact: boolean) => {
    const register = compact ? registerCompactWorkbenchPanels : registerDefaultWorkbenchPanels
    register({
      addPanel: (options) => nextApi.addPanel({
        ...options,
        component: 'workbench-panel',
        params: { panelId: options.id },
        renderer: 'always',
        floating: false,
      }),
    })
  }, [])

  const syncInspectorPanel = useCallback((nextApi: DockviewApi, open: boolean, compact: boolean) => {
    const existingPanel = nextApi.getPanel('inspector')
    if (!open) {
      existingPanel?.api.close()
      return
    }
    if (existingPanel !== undefined) return
    nextApi.addPanel({
      id: 'inspector',
      component: 'workbench-panel',
      title: '执行详情',
      initialWidth: compact ? undefined : 480,
      minimumWidth: compact ? undefined : 320,
      maximumWidth: compact ? undefined : 480,
      inactive: true,
      position: { referencePanel: 'conversation', direction: compact ? 'within' : 'right' },
      params: { panelId: 'inspector' },
      renderer: 'always',
      floating: false,
    })
  }, [])

  const onReady = useCallback(({ api: readyApi }: DockviewReadyEvent) => {
    apiRef.current = readyApi
    setApi(readyApi)
    let saved: SerializedDockview | null = null
    if (typeof window !== 'undefined') {
      try {
        saved = parsePersistedWorkbenchDockLayout(window.localStorage.getItem(WORKBENCH_DOCK_LAYOUT_STORAGE_KEY))
      } catch {
        saved = null
      }
    }
    if (saved !== null && !compactLayout) {
      readyApi.fromJSON(saved, { reuseExistingPanels: false })
    } else {
      registerPanels(readyApi, compactLayout)
    }
    syncInspectorPanel(readyApi, inspectorOpen, compactLayout)
    onInspectorOpenChange(readyApi.getPanel('inspector') !== undefined)
    persist(readyApi)
  }, [compactLayout, inspectorOpen, onInspectorOpenChange, persist, registerPanels, syncInspectorPanel])

  useEffect(() => {
    if (api === null) return undefined
    const disposable = api.onDidMutateLayout(() => persist(api))
    return () => disposable.dispose()
  }, [api, persist])

  useEffect(() => {
    if (api === null) return undefined
    syncInspectorPanel(api, inspectorOpen, compactLayout)
    persist(api)
    return undefined
  }, [api, compactLayout, inspectorOpen, persist, syncInspectorPanel])

  useEffect(() => {
    if (api === null || previousCompactLayoutRef.current === compactLayout) return
    previousCompactLayoutRef.current = compactLayout
    api.clear()
    if (!compactLayout) {
      let saved: SerializedDockview | null = null
      try {
        saved = parsePersistedWorkbenchDockLayout(window.localStorage.getItem(WORKBENCH_DOCK_LAYOUT_STORAGE_KEY))
      } catch {
        saved = null
      }
      if (saved !== null) api.fromJSON(saved, { reuseExistingPanels: false })
      else registerPanels(api, false)
    } else {
      registerPanels(api, true)
    }
    syncInspectorPanel(api, inspectorOpen, compactLayout)
    persist(api)
  }, [api, compactLayout, inspectorOpen, persist, registerPanels, syncInspectorPanel])

  useEffect(() => {
    if (api === null) return undefined
    const disposable = api.onDidRemovePanel((panel) => {
      if (panel.id === 'inspector') onInspectorOpenChange(false)
    })
    return () => disposable.dispose()
  }, [api, onInspectorOpenChange])

  const restoreDefaultLayout = useCallback(() => {
    const nextApi = apiRef.current
    if (nextApi === null) return
    nextApi.clear()
    registerPanels(nextApi, compactLayout)
    onInspectorOpenChange(true)
    persist(nextApi)
  }, [compactLayout, onInspectorOpenChange, persist, registerPanels])

  // Dockview relies on browser canvas/animation primitives that jsdom does not provide.
  // Keep component tests deterministic while production browsers use the real dock.
  if (typeof navigator !== 'undefined' && /jsdom/i.test(navigator.userAgent)) {
    return (
      <section className="workbench-dock-layout" data-testid="workbench-dock-layout">
        <div className="workbench-dock-toolbar">
          <span className="workbench-dock-toolbar-label">工作台布局</span>
          <button type="button" className="subtle-button">恢复默认布局</button>
        </div>
        <div className="workbench-dockview-fallback">
          {panels.activity}
          {panels.sidebar}
          {panels.conversation}
          {panels.inspector}
        </div>
      </section>
    )
  }

  return (
    <section className="workbench-dock-layout" data-testid="workbench-dock-layout">
      <div className="workbench-dock-toolbar">
        <span className="workbench-dock-toolbar-label">工作台布局</span>
        <button type="button" className="subtle-button" onClick={restoreDefaultLayout}>
          恢复默认布局
        </button>
      </div>
      <PanelContext.Provider value={contextValue}>
        <DockviewReact
          className="workbench-dockview"
          components={{ 'workbench-panel': WorkbenchDockPanel }}
          onReady={onReady}
          theme={dockTheme}
          disableAutoResizing
          keyboardNavigation
          layoutHistory={{ enabled: true, undoableProgrammaticMutations: true }}
          dndCompass
          getAnnouncement={(event) => {
            const panelTitle = event.panel.api.title
            if (event.kind === 'open') return `${panelTitle} 已打开`
            if (event.kind === 'close') return `${panelTitle} 已关闭`
            return undefined
          }}
        />
      </PanelContext.Provider>
    </section>
  )
}
