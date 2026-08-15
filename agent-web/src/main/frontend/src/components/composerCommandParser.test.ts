import { describe, expect, it } from 'vitest'

import { parseComposerCommand } from './composerCommandParser'

describe('parseComposerCommand', () => {
  it('keeps ordinary text as a conversation message', () => {
    expect(parseComposerCommand('修复登录流程')).toEqual({ kind: 'message' })
  })

  it('keeps a slash command and its prompt in the same input', () => {
    expect(parseComposerCommand('/plan 修复登录流程')).toEqual({
      kind: 'slash', commandName: 'plan', input: '/plan 修复登录流程', arguments: ['修复登录流程'],
    })
  })

  it('preserves chained skills and trailing prompt arguments', () => {
    expect(parseComposerCommand('/write-tests /review "修复 登录"')).toEqual({
      kind: 'slash', commandName: 'write-tests', input: '/write-tests /review "修复 登录"',
      arguments: ['/review', '修复 登录'],
    })
  })

  it('parses governed cli arguments from the same input', () => {
    expect(parseComposerCommand('/cli test.maven -q -DskipTests')).toEqual({
      kind: 'cli', commandName: 'test.maven', arguments: ['-q', '-DskipTests'],
    })
  })

  it('rejects a cli invocation without a catalog command name', () => {
    expect(parseComposerCommand('/cli')).toEqual({
      kind: 'invalid', message: '/cli 后必须提供受治理命令名称',
    })
  })

  it('does not treat a slash inside ordinary text as a command', () => {
    expect(parseComposerCommand('请查看 /plan 的行为')).toEqual({ kind: 'message' })
  })
})
