export const APPEARANCE_STORAGE_KEY = 'agent4j.appearance.preferences'

export const COLOR_MODES = ['SYSTEM', 'LIGHT', 'DARK'] as const
export const THEME_PRESETS = ['GRAPHITE', 'PAPER', 'PINE', 'SIGNAL', 'HARBOR'] as const
export const UI_FONTS = ['SANS', 'SERIF', 'MONO'] as const
export const UI_DENSITIES = ['COMPACT', 'STANDARD', 'COMFORTABLE'] as const
export const UI_RADII = ['SHARP', 'SOFT', 'ROUND'] as const

export type ColorMode = typeof COLOR_MODES[number]
export type ThemePreset = typeof THEME_PRESETS[number]
export type UiFont = typeof UI_FONTS[number]
export type UiDensity = typeof UI_DENSITIES[number]
export type UiRadius = typeof UI_RADII[number]

export interface AppearancePreferences {
  colorMode: ColorMode
  themePreset: ThemePreset
  font: UiFont
  density: UiDensity
  radius: UiRadius
  accentColor: string | null
}

export const DEFAULT_APPEARANCE: AppearancePreferences = {
  colorMode: 'SYSTEM',
  themePreset: 'GRAPHITE',
  font: 'SANS',
  density: 'STANDARD',
  radius: 'SOFT',
  accentColor: null,
}

function includes<T extends readonly string[]>(values: T, value: unknown): value is T[number] {
  return typeof value === 'string' && values.includes(value)
}

export function normalizeAppearancePreferences(value: unknown): AppearancePreferences {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return DEFAULT_APPEARANCE
  const record = value as Record<string, unknown>
  if (!includes(COLOR_MODES, record.colorMode) || !includes(THEME_PRESETS, record.themePreset) || !includes(UI_FONTS, record.font) || !includes(UI_DENSITIES, record.density) || !includes(UI_RADII, record.radius) || (record.accentColor !== null && (typeof record.accentColor !== 'string' || !/^#[0-9a-fA-F]{6}$/.test(record.accentColor)))) return DEFAULT_APPEARANCE
  return { colorMode: record.colorMode, themePreset: record.themePreset, font: record.font, density: record.density, radius: record.radius, accentColor: record.accentColor }
}
