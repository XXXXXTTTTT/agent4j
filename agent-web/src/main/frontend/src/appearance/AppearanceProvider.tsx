import { createContext, type ReactNode, useContext, useEffect, useMemo, useState } from 'react'

import { APPEARANCE_STORAGE_KEY, DEFAULT_APPEARANCE, type AppearancePreferences, normalizeAppearancePreferences } from './appearancePreferences'

interface AppearanceContextValue {
  preferences: AppearancePreferences
  updatePreferences(update: Partial<AppearancePreferences>): void
  resetPreferences(): void
}

const AppearanceContext = createContext<AppearanceContextValue | null>(null)

function readPreferences(): AppearancePreferences {
  try { return normalizeAppearancePreferences(JSON.parse(window.localStorage.getItem(APPEARANCE_STORAGE_KEY) ?? 'null')) } catch { return DEFAULT_APPEARANCE }
}

function resolveColorMode(preference: AppearancePreferences['colorMode']): 'LIGHT' | 'DARK' {
  if (preference !== 'SYSTEM') return preference
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'DARK' : 'LIGHT'
}

export function AppearanceProvider({ children }: { children: ReactNode }) {
  const [preferences, setPreferences] = useState<AppearancePreferences>(readPreferences)
  const [systemMode, setSystemMode] = useState<'LIGHT' | 'DARK'>(() => resolveColorMode('SYSTEM'))
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return undefined
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const update = () => setSystemMode(media.matches ? 'DARK' : 'LIGHT')
    media.addEventListener('change', update)
    return () => media.removeEventListener('change', update)
  }, [])
  useEffect(() => {
    const root = document.documentElement
    root.dataset.colorMode = preferences.colorMode === 'SYSTEM' ? systemMode : preferences.colorMode
    root.dataset.themePreset = preferences.themePreset
    root.dataset.uiFont = preferences.font
    root.dataset.uiDensity = preferences.density
    root.dataset.uiRadius = preferences.radius
    window.localStorage.setItem(APPEARANCE_STORAGE_KEY, JSON.stringify(preferences))
  }, [preferences, systemMode])
  const value = useMemo<AppearanceContextValue>(() => ({
    preferences,
    updatePreferences: (update) => setPreferences((current) => ({ ...current, ...update })),
    resetPreferences: () => setPreferences(DEFAULT_APPEARANCE),
  }), [preferences])
  return <AppearanceContext.Provider value={value}>{children}</AppearanceContext.Provider>
}

export function useAppearance(): AppearanceContextValue {
  const value = useContext(AppearanceContext)
  if (value === null) throw new Error('外观设置必须在 AppearanceProvider 内使用')
  return value
}
