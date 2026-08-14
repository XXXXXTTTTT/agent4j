import { createContext, type ReactNode, useContext, useEffect, useMemo, useState } from 'react'

import { APPEARANCE_STORAGE_KEY, DEFAULT_APPEARANCE, type AppearancePreferences, normalizeAppearancePreferences } from './appearancePreferences'

interface AppearanceContextValue {
  preferences: AppearancePreferences
  resolvedColorMode: 'LIGHT' | 'DARK'
  updatePreferences(update: Partial<AppearancePreferences>): void
  resetPreferences(): void
}

const AppearanceContext = createContext<AppearanceContextValue>({
  preferences: DEFAULT_APPEARANCE,
  resolvedColorMode: 'LIGHT',
  updatePreferences: () => undefined,
  resetPreferences: () => undefined,
})

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
    root.dataset.contentWidth = preferences.contentWidth
    if (preferences.accentColor === null) root.style.removeProperty('--accent')
    else root.style.setProperty('--accent', preferences.accentColor)
    window.localStorage.setItem(APPEARANCE_STORAGE_KEY, JSON.stringify(preferences))
  }, [preferences, systemMode])
  const value = useMemo<AppearanceContextValue>(() => ({
    preferences,
    resolvedColorMode: preferences.colorMode === 'SYSTEM' ? systemMode : preferences.colorMode,
    updatePreferences: (update) => setPreferences((current) => ({ ...current, ...update })),
    resetPreferences: () => setPreferences(DEFAULT_APPEARANCE),
  }), [preferences, systemMode])
  return <AppearanceContext.Provider value={value}>{children}</AppearanceContext.Provider>
}

export function useAppearance(): AppearanceContextValue {
  return useContext(AppearanceContext)
}
