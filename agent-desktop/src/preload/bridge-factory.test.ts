import { describe, expect, it } from 'vitest'

import { createDesktopBridge, createDesktopBridgeFromInvoker, installDesktopBridge } from './bridge-factory.js'
import type { Agent4jDesktopBridge } from '../shared/desktop-bridge.js'

describe('createDesktopBridge', () => {
  it('exposes only project archive selection', async () => {
    const bridge = createDesktopBridge(async () => null)
    expect(Object.keys(bridge)).toEqual(['selectProjectArchive'])
    await expect(bridge.selectProjectArchive()).resolves.toBeNull()
  })

  it('uses the fixed archive channel without exposing a generic invoker', async () => {
    const calls: string[] = []
    const bridge = createDesktopBridgeFromInvoker(async (channel) => { calls.push(channel); return null })
    await bridge.selectProjectArchive()
    expect(calls).toEqual(['agent4j:select-project-archive'])
    expect(Object.keys(bridge)).toEqual(['selectProjectArchive'])
  })

  it('installs only the narrow bridge in the main world', async () => {
    let exposedKey = ''
    let exposedBridge: Agent4jDesktopBridge | undefined
    const calls: string[] = []
    installDesktopBridge({
      exposeInMainWorld: (key, value) => { exposedKey = key; exposedBridge = value },
    }, {
      invoke: async (channel) => { calls.push(channel); return null },
    })
    expect(exposedKey).toBe('agent4jDesktop')
    expect(Object.keys(exposedBridge ?? {})).toEqual(['selectProjectArchive'])
    await exposedBridge?.selectProjectArchive()
    expect(calls).toEqual(['agent4j:select-project-archive'])
  })
})
