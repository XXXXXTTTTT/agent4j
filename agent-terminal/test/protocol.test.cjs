const test = require('node:test')
const assert = require('node:assert/strict')

const { decodeClientMessage, resolveWorkspaceCwd, isAllowedOrigin, isContainedPath } = require('../src/protocol.cjs')

test('解码输入和尺寸控制帧', () => {
  assert.deepEqual(decodeClientMessage('{"type":"input","data":"Get-ChildItem\\r"}'), {
    type: 'input', data: 'Get-ChildItem\r',
  })
  assert.deepEqual(decodeClientMessage('{"type":"resize","cols":120,"rows":32}'), {
    type: 'resize', cols: 120, rows: 32,
  })
})

test('拒绝未知控制帧和越界终端尺寸', () => {
  assert.throws(() => decodeClientMessage('{"type":"exec","command":"whoami"}'), /未知/)
  assert.throws(() => decodeClientMessage('{"type":"resize","cols":1,"rows":0}'), /尺寸/)
})

test('仅将容器工作区根下的路径映射到宿主根目录', () => {
  assert.equal(
    resolveWorkspaceCwd('D:/agent4j', '/agent-workspace', '/agent-workspace/demo'),
    'D:\\agent4j\\demo',
  )
  assert.throws(
    () => resolveWorkspaceCwd('D:/agent4j', '/agent-workspace', '/agent-workspace/../Windows'),
    /工作区根目录/,
  )
})

test('只接受明确配置的浏览器来源', () => {
  assert.equal(isAllowedOrigin('http://localhost:8080', 'http://localhost:8080,http://127.0.0.1:8080'), true)
  assert.equal(isAllowedOrigin('http://evil.example', 'http://localhost:8080,http://127.0.0.1:8080'), false)
  assert.equal(isAllowedOrigin(undefined, 'http://localhost:8080'), false)
})

test('真实路径必须位于工作区根目录内', () => {
  assert.equal(isContainedPath('D:\\agent4j', 'D:\\agent4j\\project'), true)
  assert.equal(isContainedPath('D:\\agent4j', 'D:\\other'), false)
})
