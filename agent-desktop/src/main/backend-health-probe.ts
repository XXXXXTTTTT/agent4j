export interface BackendHealth {
  available: boolean
  detail: string
}

/** 只接受本机 Spring Boot readiness 的精确 UP 响应。 */
export async function probeBackend(fetcher: typeof fetch, readinessUrl: string, timeoutMs = 5_000): Promise<BackendHealth> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetcher(readinessUrl, { method: 'GET', redirect: 'error', signal: controller.signal })
    if (response.status !== 200) return { available: false, detail: `服务返回 HTTP ${response.status}` }
    const body: unknown = await response.json()
    if (body === null || typeof body !== 'object' || Array.isArray(body)) return { available: false, detail: '服务 readiness 响应不是对象' }
    const status = (body as Record<string, unknown>).status
    return status === 'UP'
      ? { available: true, detail: '本地 Agent4J 服务已就绪' }
      : { available: false, detail: '服务 readiness 状态不是 UP' }
  } catch (error) {
    if (controller.signal.aborted) return { available: false, detail: '本地服务 readiness 请求超时' }
    return { available: false, detail: error instanceof Error ? error.message : String(error) }
  } finally {
    clearTimeout(timeout)
  }
}
