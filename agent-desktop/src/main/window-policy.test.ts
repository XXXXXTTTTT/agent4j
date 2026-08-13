import { describe, expect, it } from 'vitest'

import { desktopWindowOptions, isAllowedDesktopBridgeOrigin, isAllowedNavigation, offlineScreenUrl } from './window-policy.js'

describe('desktop window policy', () => {
  it('disables renderer Node privileges and keeps isolation enabled', () => {
    const options = desktopWindowOptions('C:/app/preload.js')
    expect(options.webPreferences).toMatchObject({ contextIsolation: true, sandbox: true, nodeIntegration: false, webSecurity: true })
  })

  it('allows only the exact local Agent4J origin', () => {
    expect(isAllowedNavigation('http://127.0.0.1:8080/')).toBe(true)
    expect(isAllowedNavigation('http://localhost:8080/')).toBe(false)
    expect(isAllowedNavigation('https://example.com/')).toBe(false)
  })

  it('limits desktop IPC to the trusted workbench origin and keeps offline page data-only', () => {
    expect(isAllowedDesktopBridgeOrigin('http://127.0.0.1:8080/workbench')).toBe(true)
    expect(isAllowedDesktopBridgeOrigin('http://127.0.0.1:8081/')).toBe(false)
    expect(offlineScreenUrl('<script>alert(1)</script>')).toMatch(/^data:text\/html/)
    expect(decodeURIComponent(offlineScreenUrl('<script>alert(1)</script>'))).not.toContain('<script>alert(1)</script>')
  })
})
