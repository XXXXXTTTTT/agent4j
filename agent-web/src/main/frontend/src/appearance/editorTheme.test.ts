import { describe, expect, it } from 'vitest'

import { getEditorTheme } from './editorTheme'

describe('getEditorTheme', () => {
  it('selects the matching Monaco theme for resolved color modes', () => {
    expect(getEditorTheme('LIGHT')).toBe('vs')
    expect(getEditorTheme('DARK')).toBe('vs-dark')
  })
})
