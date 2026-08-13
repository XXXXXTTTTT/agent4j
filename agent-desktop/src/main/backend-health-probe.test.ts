import { describe, expect, it } from 'vitest'

import { probeBackend } from './backend-health-probe.js'

describe('probeBackend', () => {
  it('accepts only HTTP 200 with exact UP status', async () => {
    const health = await probeBackend(async () => new Response(JSON.stringify({ status: 'UP' }), { status: 200 }), 'http://127.0.0.1:8080/actuator/health/readiness')
    expect(health.available).toBe(true)
  })

  it('rejects non-UP, malformed, non-200 and network failures', async () => {
    await expect(probeBackend(async () => new Response(JSON.stringify({ status: 'DOWN' }), { status: 200 }), 'http://127.0.0.1:8080/actuator/health/readiness')).resolves.toMatchObject({ available: false })
    await expect(probeBackend(async () => new Response('not-json', { status: 200 }), 'http://127.0.0.1:8080/actuator/health/readiness')).resolves.toMatchObject({ available: false })
    await expect(probeBackend(async () => new Response(JSON.stringify({ status: 'UP' }), { status: 503 }), 'http://127.0.0.1:8080/actuator/health/readiness')).resolves.toMatchObject({ available: false })
    await expect(probeBackend(async () => { throw new Error('offline') }, 'http://127.0.0.1:8080/actuator/health/readiness')).resolves.toMatchObject({ available: false, detail: 'offline' })
  })

  it('returns an unavailable result when readiness does not settle before the timeout', async () => {
    const health = await probeBackend(
      async (_input, init) => new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new Error('readiness timed out')))
      }),
      'http://127.0.0.1:8080/actuator/health/readiness',
      1,
    )
    expect(health).toMatchObject({ available: false, detail: '本地服务 readiness 请求超时' })
  })
})
