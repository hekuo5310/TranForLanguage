'use strict'

// A concurrent reader may observe only a prefix of the writer's final line.
// Completed malformed records still fail JSON.parse; only the unfinished tail waits.
function completeLines(text) {
  const last = text.lastIndexOf('\n')
  return last < 0 ? [] : text.slice(0, last).split('\n').filter(Boolean)
}
function parseEvents(text, offset = 0) {
  return completeLines(text).slice(offset).map(line => JSON.parse(line))
}
module.exports = { completeLines, parseEvents }
