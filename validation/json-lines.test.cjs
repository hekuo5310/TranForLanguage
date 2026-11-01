'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { completeLines, parseEvents } = require('./json-lines.cjs')
const first = '{"event":"PlayerJoin","args":["测试"]}\n'

test('empty and unfinished records wait for a newline', () => {
  assert.deepEqual(parseEvents(''), [])
  assert.deepEqual(parseEvents('{"event":"Play'), [])
})
test('a concurrent partial append does not discard complete records', () => {
  const partial = first + '{"eve'
  assert.throws(() => partial.split('\n').filter(Boolean).map(JSON.parse))
  assert.deepEqual(parseEvents(partial), [{ event: 'PlayerJoin', args: ['测试'] }])
})
test('the completed append is read on the next poll', () => {
  assert.deepEqual(parseEvents(first + '{"event":"PlayerQuit","args":["测试"]}\n', 1),
    [{ event: 'PlayerQuit', args: ['测试'] }])
})
test('malformed completed records still fail', () => {
  assert.throws(() => parseEvents(first + '{broken}\n'), SyntaxError)
})
test('a partial UTF-8 tail is not parsed as a record', () => {
  assert.equal(completeLines(first + '{"event":"PlayerChat","args":["�').length, 1)
  assert.deepEqual(parseEvents(first + '{"event":"PlayerChat","args":["�'), parseEvents(first))
})
