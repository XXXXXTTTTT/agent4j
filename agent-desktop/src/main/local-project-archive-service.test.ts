import { unzipSync } from 'fflate'
import { describe, expect, it } from 'vitest'

import { archiveProjectDirectory, MAX_ARCHIVE_FILES, MAX_SINGLE_FILE_BYTES, MAX_TOTAL_FILE_BYTES, type ArchiveFileSystem } from './local-project-archive-service.js'

const ordinaryDirectory = { isDirectory: () => true, isFile: () => false, isSymbolicLink: () => false, size: 0 }
const ordinaryFile = { isDirectory: () => false, isFile: () => true, isSymbolicLink: () => false, size: 1 }
const symbolicLink = { isDirectory: () => false, isFile: () => false, isSymbolicLink: () => true, size: 0 }

describe('archiveProjectDirectory', () => {
  it('archives ordinary files with relative POSIX paths and no host path disclosure', async () => {
    const fileSystem: ArchiveFileSystem = {
      lstat: async (target) => {
        if (target.endsWith('src') || target.endsWith('demo')) return ordinaryDirectory
        return { ...ordinaryFile, size: target.endsWith('App.java') ? 12 : 7 }
      },
      readdir: async (directory) => directory.endsWith('src') ? [{ name: 'App.java', ...ordinaryFile }] : [{ name: 'src', ...ordinaryDirectory }, { name: 'README.md', ...ordinaryFile }],
      readFile: async (file) => new TextEncoder().encode(file.endsWith('App.java') ? 'class App {}' : 'project'),
    }
    const result = await archiveProjectDirectory('C:\\work\\demo', fileSystem)
    expect(Object.keys(unzipSync(result.archive)).sort()).toEqual(['README.md', 'src/App.java'])
    expect(JSON.stringify(result)).not.toContain('C:\\work\\demo')
    expect(result.fileCount).toBe(2)
  })

  it('rejects symbolic links before reading their target', async () => {
    const fileSystem: ArchiveFileSystem = {
      lstat: async (target) => target.endsWith('demo') ? ordinaryDirectory : { ...ordinaryFile, size: MAX_SINGLE_FILE_BYTES },
      readdir: async () => [{ name: 'linked', ...symbolicLink }],
      readFile: async () => { throw new Error('must not read link') },
    }
    await expect(archiveProjectDirectory('C:\\work\\demo', fileSystem)).rejects.toThrow('链接')
  })

  it('rejects a file replaced by a link after directory enumeration', async () => {
    let lstatCalls = 0
    const fileSystem: ArchiveFileSystem = {
      lstat: async () => {
        lstatCalls++
        return lstatCalls === 1 ? ordinaryDirectory : symbolicLink
      },
      readdir: async () => [{ name: 'secret.txt', ...ordinaryFile }],
      readFile: async () => { throw new Error('must not read replaced link') },
    }
    await expect(archiveProjectDirectory('C:\\work\\demo', fileSystem)).rejects.toThrow('不安全变化')
  })

  it('rejects a file larger than the single-file limit', async () => {
    const fileSystem: ArchiveFileSystem = {
      lstat: async (target) => target.endsWith('demo') ? ordinaryDirectory : { ...ordinaryFile, size: MAX_SINGLE_FILE_BYTES + 1 },
      readdir: async () => [{ name: 'large.bin', ...ordinaryFile }],
      readFile: async () => { throw new Error('超过上限时不得读取文件') },
    }
    await expect(archiveProjectDirectory('C:\\work\\demo', fileSystem)).rejects.toThrow('单文件上限')
  })

  it('does not disclose a host path when the file system operation fails', async () => {
    const fileSystem: ArchiveFileSystem = {
      lstat: async () => { throw new Error('ENOENT: C:\\private\\source') },
      readdir: async () => [],
      readFile: async () => new Uint8Array(),
    }
    await expect(archiveProjectDirectory('C:\\work\\demo', fileSystem)).rejects.toThrow('项目归档失败')
    await expect(archiveProjectDirectory('C:\\work\\demo', fileSystem)).rejects.not.toThrow('C:\\private\\source')
  })

  it('rejects an archive whose total file bytes exceed the total limit', async () => {
    const fileNames = Array.from({ length: Math.floor(MAX_TOTAL_FILE_BYTES / MAX_SINGLE_FILE_BYTES) + 1 }, (_, index) => ({ name: `part-${index}.bin`, ...ordinaryFile }))
    const fileSystem: ArchiveFileSystem = {
      lstat: async (target) => target.endsWith('demo') ? ordinaryDirectory : { ...ordinaryFile, size: MAX_SINGLE_FILE_BYTES },
      readdir: async () => fileNames,
      readFile: async () => new Uint8Array(MAX_SINGLE_FILE_BYTES),
    }
    await expect(archiveProjectDirectory('C:\\work\\demo', fileSystem)).rejects.toThrow('总大小')
  })

  it('rejects an archive whose file count exceeds the import limit', async () => {
    const names = Array.from({ length: MAX_ARCHIVE_FILES + 1 }, (_, index) => ({ name: `file-${index}.txt`, ...ordinaryFile }))
    const fileSystem: ArchiveFileSystem = {
      lstat: async (target) => target.endsWith('demo') ? ordinaryDirectory : ordinaryFile,
      readdir: async () => names,
      readFile: async () => new Uint8Array([1]),
    }
    await expect(archiveProjectDirectory('C:\\work\\demo', fileSystem)).rejects.toThrow('文件数量')
  })

  it('rejects Windows-style absolute and traversal archive entries', async () => {
    const fileSystem: ArchiveFileSystem = {
      lstat: async (target) => target.endsWith('demo') ? ordinaryDirectory : ordinaryFile,
      readdir: async () => [{ name: '..\\outside.txt', ...ordinaryFile }],
      readFile: async () => new Uint8Array([1]),
    }
    await expect(archiveProjectDirectory('C:\\work\\demo', fileSystem)).rejects.toThrow('非法归档条目')
  })
})
