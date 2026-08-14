import { describe, expect, it } from 'vitest'

import { getTerminalTheme } from './terminalTheme'

describe('getTerminalTheme', () => {
  it('uses readable background and foreground pairs in both modes', () => {
    expect(getTerminalTheme('LIGHT', 'GRAPHITE')).toMatchObject({ background: '#f8fafc', foreground: '#17212e' })
    expect(getTerminalTheme('DARK', 'GRAPHITE')).toMatchObject({ background: '#11120f', foreground: '#f1f0e9' })
  })

  it('uses the selected preset accent for the terminal cursor and blue channel', () => {
    expect(getTerminalTheme('DARK', 'PINE')).toMatchObject({ cursor: '#4fb38d', blue: '#4fb38d' })
    expect(getTerminalTheme('LIGHT', 'SIGNAL')).toMatchObject({ cursor: '#ef715d', blue: '#ef715d' })
  })

  it('gives an explicit custom accent precedence over the preset accent', () => {
    expect(getTerminalTheme('DARK', 'PINE', '#d97757')).toMatchObject({ cursor: '#d97757', blue: '#d97757' })
  })
})
