export function getEditorTheme(colorMode: 'LIGHT' | 'DARK'): 'vs' | 'vs-dark' {
  return colorMode === 'LIGHT' ? 'vs' : 'vs-dark'
}
