export interface TerminalTheme {
  background: string
  foreground: string
  cursor: string
  black: string
  brightBlack: string
  red: string
  brightRed: string
  green: string
  brightGreen: string
  yellow: string
  brightYellow: string
  blue: string
  brightBlue: string
  magenta: string
  brightMagenta: string
  cyan: string
  brightCyan: string
  white: string
  brightWhite: string
}

export function getTerminalTheme(colorMode: 'LIGHT' | 'DARK'): TerminalTheme {
  if (colorMode === 'LIGHT') return { background: '#f8fafc', foreground: '#17212e', cursor: '#2468d8', black: '#f1f5f9', brightBlack: '#64748b', red: '#bd3343', brightRed: '#bd3343', green: '#167449', brightGreen: '#167449', yellow: '#9a5d09', brightYellow: '#9a5d09', blue: '#2468d8', brightBlue: '#2468d8', magenta: '#8b4faf', brightMagenta: '#8b4faf', cyan: '#167d92', brightCyan: '#167d92', white: '#435267', brightWhite: '#17212e' }
  return { background: '#11120f', foreground: '#f1f0e9', cursor: '#8ab4ff', black: '#171814', brightBlack: '#4a4d43', red: '#ef8991', brightRed: '#ef8991', green: '#71c58c', brightGreen: '#71c58c', yellow: '#e3b86b', brightYellow: '#e3b86b', blue: '#8ab4ff', brightBlue: '#8ab4ff', magenta: '#c6a0dc', brightMagenta: '#c6a0dc', cyan: '#8fbfc1', brightCyan: '#8fbfc1', white: '#bebdb4', brightWhite: '#f1f0e9' }
}
