import { describe, expect, it } from 'vitest'

import { DEFAULT_APPEARANCE, normalizeAppearancePreferences } from './appearancePreferences'

describe('appearancePreferences', () => {
  it('restores a complete valid appearance selection', () => {
    expect(normalizeAppearancePreferences({
      colorMode: 'LIGHT', themePreset: 'HARBOR', font: 'SERIF', density: 'COMFORTABLE', radius: 'ROUND', accentColor: '#d97757',
    })).toEqual({
      colorMode: 'LIGHT', themePreset: 'HARBOR', font: 'SERIF', density: 'COMFORTABLE', radius: 'ROUND', accentColor: '#d97757',
    })
  })

  it('falls back to defaults when persisted values are unknown or incomplete', () => {
    expect(normalizeAppearancePreferences({ colorMode: 'MIDNIGHT', themePreset: 'UNKNOWN' })).toEqual(DEFAULT_APPEARANCE)
    expect(normalizeAppearancePreferences(null)).toEqual(DEFAULT_APPEARANCE)
  })
})
