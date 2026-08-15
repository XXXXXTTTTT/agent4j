import { readFile } from 'node:fs/promises'
import { describe, expect, it } from 'vitest'

describe('themed native controls', () => {
  it('keeps remaining native selects and command scrollbars aligned with the active color mode', async () => {
    const stylesheet = await readFile('src/styles.css', 'utf8')

    expect(stylesheet).toContain('html[data-color-mode="DARK"] select { color-scheme: dark; }')
    expect(stylesheet).toContain('html[data-color-mode="LIGHT"] select { color-scheme: light; }')
    expect(stylesheet).toContain('.cli-command-menu::-webkit-scrollbar-thumb, .themed-select-menu::-webkit-scrollbar-thumb')
  })
})
