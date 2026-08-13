/** 桌面端向受信任渲染页面公开的唯一文件导入能力。 */
export interface ProjectArchive {
  archive: Uint8Array
  fileCount: number
  totalBytes: number
  suggestedDisplayName: string
}

export interface Agent4jDesktopBridge {
  selectProjectArchive(): Promise<ProjectArchive | null>
}
