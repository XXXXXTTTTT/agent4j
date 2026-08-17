import { readFile } from 'node:fs/promises'
import { describe, expect, it } from 'vitest'

describe('mobile workbench layout', () => {
  it('overrides the collapsed desktop inspector grid with one mobile column', async () => {
    const stylesheet = await readFile('src/styles.css', 'utf8')

    expect(stylesheet).toContain(
      '.workbench-shell[data-inspector-open="false"] .agent-layout.has-conversation-sidebar { grid-template-columns: minmax(0,1fr); }',
    )
  })
})

describe('workbench scrolling and theme tokens', () => {
  it('defines a shared scrollbar treatment for every native scroll region', async () => {
    const stylesheet = await readFile('src/styles.css', 'utf8')

    expect(stylesheet).toContain('*::-webkit-scrollbar-thumb')
    expect(stylesheet).toContain('scrollbar-color: var(--scrollbar-thumb) transparent')
    expect(stylesheet).toContain('overscroll-behavior: contain')
  })

  it('keeps sidebar regions independently sized and removes hard-coded blue selection colors', async () => {
    const stylesheet = await readFile('src/styles.css', 'utf8')

    expect(stylesheet).toMatch(/\.conversation-sidebar\s*\{\s*display:\s*grid;\s*grid-template-rows:\s*auto auto minmax\(0,\s*1fr\) auto;/)
    expect(stylesheet).toContain('.conversation-sidebar-header')
    expect(stylesheet).toContain('.conversation-sidebar-tools')
    expect(stylesheet).toContain('.conversation-sidebar-footer')
    expect(stylesheet).not.toContain('rgba(138, 180, 255')
    expect(stylesheet).not.toContain('rgba(138,180,255')
  })
})

describe('center editor and live dock sizing', () => {
  it('keeps the file editor independent from the project sidebar', async () => {
    const stylesheet = await readFile('src/styles.css', 'utf8')

    expect(stylesheet).toContain('.workbench-dockview-host')
    expect(stylesheet).toContain('.workspace-editor-panel')
    expect(stylesheet).toContain('.workspace-editor-content')
    expect(stylesheet).toMatch(/\.workspace-explorer\s*\{[^}]*grid-template-rows:\s*auto minmax\(0,\s*1fr\) auto;/)
  })
})
