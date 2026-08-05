import { useCallback, useEffect, useRef, useState } from 'react'

import type {
  AgentState,
  ApprovalCommand,
  RunView,
  TraceEvent,
} from '../api/contracts'
import {
  RunApiError,
  createRun,
  decideRun,
  decodeTraceFrame,
  getRun,
  getRunHistory,
} from '../api/runApi'
import {
  TerminalSession,
  type SocketLike,
  type WebSocketFactory,
  workbenchWebSocketUrl,
} from '../terminal/TerminalSession'

export interface WorkbenchConnectionState {
  trace: number | null
  terminal: number | null
}

export interface UseRunWorkbenchOptions {
  fetcher?: typeof fetch
  webSocketFactory?: WebSocketFactory
  onTerminalReset(): void
  onTerminalData(text: string): void
}

export interface UseRunWorkbenchResult {
  run: RunView | null
  history: RunView[]
  traceEvents: TraceEvent[]
  connectionState: WorkbenchConnectionState
  error: Error | null
  start(graphId: string, initialState: AgentState): Promise<void>
  reload(): Promise<void>
  decide(command: ApprovalCommand): Promise<void>
}

const TERMINAL_TRACE_TYPES = new Set<TraceEvent['type']>([
  'COMPLETED',
  'FAILED',
  'REJECTED',
])

function defaultWebSocketFactory(url: string): SocketLike {
  return new WebSocket(url)
}

function asError(value: unknown): Error {
  return value instanceof Error ? value : new Error(String(value))
}

/** 协调权威 Run 快照、历史记录和两条实时 WebSocket。 */
export function useRunWorkbench(
  options: UseRunWorkbenchOptions,
): UseRunWorkbenchResult {
  const [run, setRun] = useState<RunView | null>(null)
  const [history, setHistory] = useState<RunView[]>([])
  const [traceEvents, setTraceEvents] = useState<TraceEvent[]>([])
  const [connectionState, setConnectionState] = useState<WorkbenchConnectionState>({
    trace: null,
    terminal: null,
  })
  const [error, setError] = useState<Error | null>(null)
  const optionsRef = useRef(options)
  const runRef = useRef<RunView | null>(null)
  const traceSocketRef = useRef<SocketLike | null>(null)
  const terminalSessionRef = useRef<TerminalSession | null>(null)
  const mountedRef = useRef(true)
  const operationRef = useRef(0)
  optionsRef.current = options
  runRef.current = run

  const fetcher = useCallback(() => optionsRef.current.fetcher ?? globalThis.fetch, [])
  const socketFactory = useCallback(
    () => optionsRef.current.webSocketFactory ?? defaultWebSocketFactory,
    [],
  )

  const closeConnections = useCallback(() => {
    const traceSocket = traceSocketRef.current
    traceSocketRef.current = null
    if (traceSocket !== null) {
      traceSocket.onopen = null
      traceSocket.onmessage = null
      traceSocket.onerror = null
      traceSocket.onclose = null
      if (traceSocket.readyState < 2) traceSocket.close()
    }
    terminalSessionRef.current?.close()
    terminalSessionRef.current = null
  }, [])

  const refreshRun = useCallback(
    async (runId: string, includeHistory: boolean): Promise<void> => {
      const currentOperation = operationRef.current
      const [latest, loadedHistory] = await Promise.all([
        getRun(runId, fetcher()),
        includeHistory ? getRunHistory(runId, fetcher()) : Promise.resolve(null),
      ])
      if (!mountedRef.current || currentOperation !== operationRef.current) return
      if (runRef.current !== null && runRef.current.runId !== runId) return
      setRun(latest)
      runRef.current = latest
      if (loadedHistory !== null) setHistory(loadedHistory)
    },
    [fetcher],
  )

  const connect = useCallback(
    (runId: string): void => {
      closeConnections()
      setTraceEvents([])
      setConnectionState({ trace: null, terminal: null })

      const traceSocket = socketFactory()(workbenchWebSocketUrl(`/ws/runs/${runId}/trace`))
      traceSocketRef.current = traceSocket
      setConnectionState((state) => ({ ...state, trace: traceSocket.readyState }))
      traceSocket.onopen = () =>
        setConnectionState((state) => ({ ...state, trace: traceSocket.readyState }))
      traceSocket.onclose = () =>
        setConnectionState((state) => ({ ...state, trace: traceSocket.readyState }))
      traceSocket.onerror = () => setError(new Error('Trace WebSocket 连接失败'))
      traceSocket.onmessage = (event) => {
        try {
          const frame = decodeTraceFrame(JSON.parse(String(event.data)) as unknown)
          if (frame.kind === 'SNAPSHOT') {
            if (frame.run.runId !== runId) {
              throw new Error(`Trace run.runId 不匹配: ${frame.run.runId}`)
            }
            setRun(frame.run)
            runRef.current = frame.run
            return
          }
          if (frame.event.runId !== runId) {
            throw new Error(`Trace event.runId 不匹配: ${frame.event.runId}`)
          }
          setTraceEvents((events) => [...events, frame.event])
          if (TERMINAL_TRACE_TYPES.has(frame.event.type)) {
            void refreshRun(runId, true).catch((failure) => setError(asError(failure)))
          }
        } catch (failure) {
          setError(asError(failure))
          traceSocket.close()
        }
      }

      terminalSessionRef.current = new TerminalSession(runId, socketFactory(), {
        onReset: () => optionsRef.current.onTerminalReset(),
        onData: (text) => optionsRef.current.onTerminalData(text),
        onStateChange: (readyState) =>
          setConnectionState((state) => ({ ...state, terminal: readyState })),
        onError: (failure) => setError(failure),
      })
    },
    [closeConnections, refreshRun, socketFactory],
  )

  const start = useCallback(
    async (graphId: string, initialState: AgentState): Promise<void> => {
      const operation = ++operationRef.current
      setError(null)
      try {
        const created = await createRun(graphId, initialState, fetcher())
        const loadedHistory = await getRunHistory(created.runId, fetcher())
        if (!mountedRef.current || operation !== operationRef.current) return
        setRun(created)
        runRef.current = created
        setHistory(loadedHistory)
        connect(created.runId)
      } catch (failure) {
        if (mountedRef.current && operation === operationRef.current) {
          setError(asError(failure))
        }
        throw failure
      }
    },
    [connect, fetcher],
  )

  const reload = useCallback(async (): Promise<void> => {
    const current = runRef.current
    if (current === null) throw new Error('当前没有 Run')
    setError(null)
    try {
      await refreshRun(current.runId, true)
    } catch (failure) {
      setError(asError(failure))
      throw failure
    }
  }, [refreshRun])

  const decide = useCallback(
    async (command: ApprovalCommand): Promise<void> => {
      const current = runRef.current
      if (current === null) throw new Error('当前没有 Run')
      setError(null)
      try {
        const decided = await decideRun(current.runId, command, fetcher())
        const loadedHistory = await getRunHistory(current.runId, fetcher())
        if (!mountedRef.current || runRef.current?.runId !== current.runId) return
        setRun(decided)
        runRef.current = decided
        setHistory(loadedHistory)
      } catch (failure) {
        if (failure instanceof RunApiError && failure.status === 409) {
          try {
            const latest = await getRun(current.runId, fetcher())
            if (mountedRef.current && runRef.current?.runId === current.runId) {
              setRun(latest)
              runRef.current = latest
            }
            return
          } catch (reloadFailure) {
            if (mountedRef.current) setError(asError(reloadFailure))
            throw reloadFailure
          }
        }
        setError(asError(failure))
        throw failure
      }
    },
    [fetcher],
  )

  useEffect(
    () => {
      mountedRef.current = true
      return () => {
        mountedRef.current = false
        operationRef.current += 1
        closeConnections()
      }
    },
    [closeConnections],
  )

  return {
    run,
    history,
    traceEvents,
    connectionState,
    error,
    start,
    reload,
    decide,
  }
}
