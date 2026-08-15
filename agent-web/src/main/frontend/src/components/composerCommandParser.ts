export type ComposerCommand =
  | { kind: 'message' }
  | { kind: 'slash'; commandName: string; input: string; arguments: string[] }
  | { kind: 'cli'; commandName: string; arguments: string[] }
  | { kind: 'invalid'; message: string }

/** 解析 Composer 开头的命令，普通文本保持在会话路径。 */
export function parseComposerCommand(input: string): ComposerCommand {
  if (!input.startsWith('/')) return { kind: 'message' }
  const tokens = tokenize(input.slice(1))
  if (tokens.length === 0) return { kind: 'invalid', message: '请输入命令名称' }
  const [commandName, ...argumentsList] = tokens
  if (commandName === 'cli') {
    const [cliCommandName, ...cliArguments] = argumentsList
    if (cliCommandName === undefined) {
      return { kind: 'invalid', message: '/cli 后必须提供受治理命令名称' }
    }
    return { kind: 'cli', commandName: cliCommandName, arguments: cliArguments }
  }
  return { kind: 'slash', commandName, input, arguments: argumentsList }
}

function tokenize(value: string): string[] {
  const tokens: string[] = []
  let token = ''
  let quoted = false
  let escaped = false
  for (const character of value) {
    if (escaped) {
      token += character
      escaped = false
      continue
    }
    if (character === '\\') {
      escaped = true
      continue
    }
    if (character === '"') {
      quoted = !quoted
      continue
    }
    if (/\s/.test(character) && !quoted) {
      if (token.length > 0) {
        tokens.push(token)
        token = ''
      }
      continue
    }
    token += character
  }
  if (escaped) token += '\\'
  if (token.length > 0) tokens.push(token)
  return tokens
}
