import { describe, expect, it } from 'vitest'

import { getTerminalTheme } from './terminalTheme'

describe('getTerminalTheme', () => {
  it('uses readable background and foreground pairs in both modes', () => {
    expect(getTerminalTheme('LIGHT')).toMatchObject({ background: '#f8fafc', foreground: '#17212e' })
    expect(getTerminalTheme('DARK')).toMatchObject({ background: '#11120f', foreground: '#f1f0e9' })
  })
})
