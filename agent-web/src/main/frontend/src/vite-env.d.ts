/// <reference types="vite/client" />

declare var MonacoEnvironment:
  | import('monaco-editor/esm/vs/editor/editor.api').Environment
  | undefined

interface Agent4jDesktopProjectArchive {
  archive: Uint8Array
  fileCount: number
  totalBytes: number
  suggestedDisplayName: string
}

interface Window {
  agent4jDesktop?: {
    selectProjectArchive(): Promise<Agent4jDesktopProjectArchive | null>
  }
}
