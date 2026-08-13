import { lstat, readdir, readFile } from 'node:fs/promises'
import path from 'node:path'

import { zipSync } from 'fflate'

import type { ProjectArchive } from '../shared/desktop-bridge.js'

export interface ArchiveFileSystem {
  lstat(path: string): Promise<{ isDirectory(): boolean; isFile(): boolean; isSymbolicLink(): boolean; size: number }>
  readdir(path: string, options: { withFileTypes: true }): Promise<Array<{ name: string; isDirectory(): boolean; isFile(): boolean; isSymbolicLink(): boolean }>>
  readFile(path: string): Promise<Uint8Array>
}

const localFileSystem: ArchiveFileSystem = { lstat, readdir, readFile }
export const MAX_ARCHIVE_FILES = 10_000
export const MAX_SINGLE_FILE_BYTES = 32 * 1024 * 1024
export const MAX_TOTAL_FILE_BYTES = 50 * 1024 * 1024
export const MAX_ARCHIVE_BYTES = 50 * 1024 * 1024

class ProjectArchiveError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ProjectArchiveError'
  }
}

/** 将用户明确选择的目录转换为不含主机路径的 ZIP 字节。 */
export async function archiveProjectDirectory(directory: string, fileSystem: ArchiveFileSystem = localFileSystem): Promise<ProjectArchive> {
  try {
    const root = path.resolve(directory)
    const rootStat = await fileSystem.lstat(root)
    if (!rootStat.isDirectory() || rootStat.isSymbolicLink()) throw new ProjectArchiveError('所选项目必须是普通目录')
    const files: Record<string, Uint8Array> = {}
    let totalBytes = 0

    async function walk(current: string, relative: string): Promise<void> {
      const currentStat = await fileSystem.lstat(current)
      if (!currentStat.isDirectory() || currentStat.isSymbolicLink()) throw new ProjectArchiveError('项目目录在归档期间发生了不安全变化')
      const entries = await fileSystem.readdir(current, { withFileTypes: true })
      const afterEnumeration = await fileSystem.lstat(current)
      if (!afterEnumeration.isDirectory() || afterEnumeration.isSymbolicLink() || afterEnumeration.size !== currentStat.size) {
        throw new ProjectArchiveError('项目目录在归档期间发生了不安全变化')
      }
      const knownFiles = entries.filter((entry) => entry.isFile()).length
      if (Object.keys(files).length + knownFiles > MAX_ARCHIVE_FILES) throw new ProjectArchiveError('项目文件数量超过导入上限')
      for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
        const childRelative = relative.length === 0 ? entry.name : `${relative}/${entry.name}`
        const normalizedRelative = childRelative.replaceAll('\\', '/')
        if (path.posix.isAbsolute(normalizedRelative)
            || normalizedRelative.split('/').includes('..')
            || /^[A-Za-z]:\//.test(normalizedRelative)
            || normalizedRelative.startsWith('//')) throw new ProjectArchiveError('项目路径包含非法归档条目')
        const childPath = path.join(current, entry.name)
        if (entry.isSymbolicLink()) throw new ProjectArchiveError(`项目包含不允许的链接: ${childRelative}`)
        const childStat = await fileSystem.lstat(childPath)
        if (childStat.isSymbolicLink()
            || childStat.isDirectory() !== entry.isDirectory()
            || childStat.isFile() !== entry.isFile()) {
          throw new ProjectArchiveError(`项目文件在归档期间发生了不安全变化: ${childRelative}`)
        }
        if (childStat.isDirectory()) {
          await walk(childPath, childRelative)
        } else if (childStat.isFile()) {
          if (childStat.size > MAX_SINGLE_FILE_BYTES) throw new ProjectArchiveError(`项目文件超过单文件上限: ${childRelative}`)
          if (Object.keys(files).length >= MAX_ARCHIVE_FILES) throw new ProjectArchiveError('项目文件数量超过导入上限')
          if (totalBytes + childStat.size > MAX_TOTAL_FILE_BYTES) throw new ProjectArchiveError('项目总大小超过导入上限')
          const bytes = await fileSystem.readFile(childPath)
          const afterRead = await fileSystem.lstat(childPath)
          if (!afterRead.isFile() || afterRead.isSymbolicLink() || afterRead.size !== childStat.size || bytes.byteLength !== childStat.size) {
            throw new ProjectArchiveError(`项目文件在归档期间发生了不安全变化: ${childRelative}`)
          }
          files[childRelative] = bytes
          totalBytes += bytes.byteLength
        } else {
          throw new ProjectArchiveError(`项目包含不允许的文件类型: ${childRelative}`)
        }
      }
    }

    await walk(root, '')
    if (Object.keys(files).length === 0) throw new ProjectArchiveError('所选项目不包含可导入文件')
    const result = {
      archive: zipSync(files, { level: 6 }),
      fileCount: Object.keys(files).length,
      totalBytes,
      suggestedDisplayName: path.basename(root),
    }
    if (result.archive.byteLength > MAX_ARCHIVE_BYTES) throw new ProjectArchiveError('项目归档大小超过导入上限')
    return result
  } catch (error) {
    if (error instanceof ProjectArchiveError) throw error
    throw new ProjectArchiveError('项目归档失败')
  }
}
