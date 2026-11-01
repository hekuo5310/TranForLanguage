#!/usr/bin/env node
'use strict'
// Run only against a disposable directory created by prepare.py. No production server is touched.
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const { spawn } = require('node:child_process')
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')
const { completeLines, parseEvents } = require('./json-lines.cjs')
const args = process.argv.slice(2)
const split = args.indexOf('--')
if (split < 1) throw new Error('Usage: node validation/run.cjs <server-dir> [--no-player] -- <java> <server args...>')
const directory = path.resolve(args[0])
assert(fs.existsSync(path.join(directory, '.tranforlanguage-smoke')), 'Refusing an unmarked server directory')
const config = JSON.parse(fs.readFileSync(path.join(directory, 'smoke-config.json')))
const noPlayer = args.slice(1, split).includes('--no-player')
const languages = ['cpp', 'c', 'python', 'rust']
const dirs = Object.fromEntries(languages.map(id => [id, id === 'cpp' ? config.programs : path.join(config.programs, id)]))
const offsets = Object.fromEntries(languages.map(id => {
  const file = path.join(dirs[id], 'events.jsonl')
  return [id, fs.existsSync(file) ? completeLines(fs.readFileSync(file, 'utf8')).length : 0]
}))
const events = id => {
  const file = path.join(dirs[id], 'events.jsonl')
  if (!fs.existsSync(file)) return []
  return parseEvents(fs.readFileSync(file, 'utf8'), offsets[id])
}
const pid = id => Number(fs.readFileSync(path.join(dirs[id], 'pid'), 'utf8'))
const counts = (id, name) => events(id).filter(e => e.event === name).length
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
let output = ''
let exited = false
let bot
const log = fs.createWriteStream(path.join(directory, 'smoke-server.log'))
const server = spawn(args[split + 1], args.slice(split + 2), { cwd: directory, stdio: ['pipe', 'pipe', 'pipe'] })
let exitCode
server.on('exit', code => { exited = true; exitCode = code })
server.on('error', error => { output += '\n' + error.stack; exited = true })
for (const stream of [server.stdout, server.stderr]) stream.on('data', data => {
  log.write(data)
  output = (output + data.toString()).slice(-4_000_000)
})
const command = text => { assert(!exited, 'Server exited'); server.stdin.write(text + '\n') }
async function until(predicate, label, timeout = 30000, allowExit = false) {
  const end = Date.now() + timeout
  while (!predicate()) {
    if (!allowExit && exited) throw new Error('Server exited waiting for ' + label + '\n' + output.slice(-16000))
    if (Date.now() > end) throw new Error('Timeout: ' + label + '\n' + output.slice(-8000))
    await delay(100)
  }
}
async function timed(promise, label, timeout = 15000) {
  let timer
  try { return await Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Action timed out: ' + label)), timeout) })]) }
  finally { clearTimeout(timer) }
}
async function event(name, action) {
  const before = Object.fromEntries(languages.map(id => [id, counts(id, name)]))
  await timed(Promise.resolve().then(action), name)
  await until(() => languages.every(id => counts(id, name) > before[id]), name)
  console.log('PASS event ' + name)
}
async function allEvent(name) {
  await until(() => languages.every(id => counts(id, name) > 0), name)
  console.log('PASS event ' + name)
}
const alive = p => { if (!Number.isSafeInteger(p) || p <= 1) return false; try { process.kill(p, 0); return true } catch (e) { if (e.code === 'ESRCH') return false; throw e } }
const results = { loader: config.loader, mode: noPlayer ? 'server-only' : 'player', startedAt: new Date().toISOString(), assertions: [] }
async function main() {
  await until(() => /Done \(/.test(output), 'server startup', 300000)
  await until(() => languages.every(id => output.includes('TFL_READY_' + id) && output.includes('TFL_ACTION_' + id)), 'four real language programs', 330000)
  assert(!/Mixin apply failed|InvalidInjectionException|InjectionError/.test(output), 'Mixin application failed')
  console.log('PASS server startup, SDK actions, C/C++ without a separate SDK header, Python and Rust')
  results.assertions.push('startup', 'four-language-sdk-actions')
  const distribution = path.join(directory, 'smoke-distribution.json')
  const game = fs.existsSync(distribution) ? JSON.parse(fs.readFileSync(distribution, 'utf8')).minecraft : ''
  const modernRules = game.startsWith('26.') || game === '1.21.11'
  command(modernRules ? 'gamerule minecraft:spawn_mobs false' : 'gamerule doMobSpawning false')
  command(modernRules ? 'gamerule minecraft:fire_spread_radius_around_player 0' : 'gamerule doFireTick false')
  command('forceload add -5 -5 5 5')
  const areaReady = () => output.includes('[Server] TFL_AREA_READY')
  for (let attempt = 0; attempt < 150 && !areaReady(); attempt++) {
    command('execute if loaded -5 80 -5 if loaded 5 80 5 if loaded -5 80 5 if loaded 5 80 -5 run say TFL_AREA_READY')
    await delay(200)
  }
  await until(areaReady, 'loaded test chunks', 1000)
  command('weather clear')
  command('kill @e[type=!player]')
  command('fill -5 80 -5 5 85 5 air')
  command('fill -5 79 -5 5 79 5 stone')
  await delay(500)
  await event('ServerCommand', async () => command('say TFL_COMMAND_PROBE'))
  await event('WeatherChange', async () => command('weather rain'))
  await event('WeatherChange', async () => command('weather clear'))
  if (config.loader !== 'spigot') {
    await event('BlockIgnite', async () => command('setblock 3 80 0 fire'))
    // Changing fire age is not a second ignition.
    const ignitions = Object.fromEntries(languages.map(id => [id, counts(id, 'BlockIgnite')]))
    command('setblock 3 80 0 fire[age=1]')
    await delay(500)
    for (const id of languages) assert.equal(counts(id, 'BlockIgnite'), ignitions[id], 'Fire age update emitted BlockIgnite')
  }
  await event('EntitySpawn', async () => {
    command('summon pig 4 80 0 {NoAI:1b}')
    command('tag @e[type=pig,sort=nearest,limit=1] add tfl_smoke_pig')
  })
  await event('EntityDamage', async () => command('damage @e[tag=tfl_smoke_pig,limit=1] 1'))
  await event('EntityDeath', async () => command('kill @e[tag=tfl_smoke_pig]'))
  command('summon item_frame 1 80 1 {Facing:1b}')
  await event('HangingBreak', async () => command('damage @e[type=item_frame,limit=1] 1'))
  results.assertions.push('server-events', 'fire-age-not-ignition')
  if (!noPlayer) {
    const messages = []
    bot = mineflayer.createBot({ host: '127.0.0.1', port: config.port, username: 'TFLProbe', auth: 'offline', version: false })
    let botError
    bot.on('error', error => { botError = error })
    bot.on('messagestr', message => messages.push(message))
    await until(() => !!bot.entity || !!botError, 'player spawn', 45000)
    if (botError) throw botError
    await allEvent('PlayerJoin')
    await until(() => languages.every(id => messages.some(m => m.includes('TFL_PRIVATE_' + id)) && messages.some(m => m.includes('TFL_BROADCAST_' + id))), 'four-language private messages and broadcasts')
    command('clear TFLProbe')
    command('gamemode survival TFLProbe')
    command('tp TFLProbe 0.5 80 0.5')
    await delay(1000)
    await event('PlayerChat', async () => bot.chat('TFL_CHAT_中文'))
    await event('PlayerMove', async () => {
      bot.setControlState('forward', true); await delay(600); bot.setControlState('forward', false)
    })
    command('tp TFLProbe 0.5 80 0.5'); await delay(800)
    // Rotation must not trigger movement notifications.
    const moved = Object.fromEntries(languages.map(id => [id, counts(id, 'PlayerMove')]))
    await bot.look(Math.PI / 2, 0); await delay(500)
    for (const id of languages) assert.equal(counts(id, 'PlayerMove'), moved[id], 'Rotation-only movement emitted PlayerMove')
    command('give TFLProbe stone 8')
    await until(() => bot.inventory.items().some(i => i.name === 'stone'), 'stone in inventory')
    await event('PlayerDropItem', async () => bot.tossStack(bot.inventory.items().find(i => i.name === 'stone')))
    await delay(2200)
    command('execute at TFLProbe run tp TFLProbe @e[type=item,sort=nearest,limit=1,distance=..10]')
    await allEvent('PlayerPickupItem')
    // Toss velocity varies slightly; pickup can leave the player outside interaction reach.
    command('tp TFLProbe 0.5 80 0.5')
    await until(() => bot.entity.position.distanceTo(new Vec3(0.5, 80, 0.5)) < 0.2, 'return from pickup')
    command('setblock 1 80 0 chest')
    await delay(500)
    const chest = bot.blockAt(new Vec3(1, 80, 0))
    await event('InventoryOpen', async () => { await bot.openContainer(chest) })
    await allEvent('PlayerInteract')
    await event('InventoryClick', async () => { await bot.clickWindow(0, 0, 0) })
    await event('InventoryClose', async () => bot.closeWindow(bot.currentWindow))
    command('give TFLProbe flint_and_steel')
    await until(() => bot.inventory.items().some(i => i.name === 'flint_and_steel'), 'flint and steel')
    await bot.equip(bot.inventory.items().find(i => i.name === 'flint_and_steel'), 'hand')
    await event('BlockIgnite', async () => bot.activateBlock(bot.blockAt(new Vec3(2, 79, 0)), new Vec3(0, 1, 0)))
    command('give TFLProbe stone 8'); await delay(500)
    await bot.equip(bot.inventory.items().find(i => i.name === 'stone'), 'hand')
    command('setblock 0 80 2 stone'); await delay(500)
    const reference = bot.blockAt(new Vec3(0, 80, 2))
    await event('BlockPlace', async () => bot.placeBlock(reference, new Vec3(0, 1, 0)))
    await event('BlockBreak', async () => bot.dig(bot.blockAt(new Vec3(0, 81, 2))))
    await event('PlayerDeath', async () => command('kill TFLProbe'))
    await allEvent('PlayerRespawn')
    await event('PlayerQuit', async () => bot.quit())
    bot = null
    results.assertions.push('player-events', 'rotation-not-movement', 'private-messages-and-broadcasts')
  }
  const original = Object.fromEntries(languages.map(id => [id, pid(id)]))
  command('tranforlanguage stop c')
  await until(() => !alive(original.c), 'independent C stop')
  for (const id of ['cpp', 'python', 'rust']) assert.equal(pid(id), original[id])
  const stopped = events('c').length
  command('say TFL_WITHOUT_C'); await delay(500)
  assert.equal(events('c').length, stopped, 'Stopped plugin received an event')
  command('tranforlanguage reload @root')
  await until(() => pid('cpp') !== original.cpp && alive(pid('cpp')), 'legacy root reload')
  for (const id of ['python', 'rust']) assert.equal(pid(id), original[id], 'Reload restarted another plugin')
  command('tranforlanguage start c')
  await until(() => pid('c') !== original.c && alive(pid('c')), 'C restart')
  await event('ServerCommand', async () => command('say TFL_ALL_FOUR_AGAIN'))
  const added = path.join(config.programs, '新 插件')
  fs.mkdirSync(added)
  fs.writeFileSync(path.join(added, 'main.py'), 'from tranforcpp import console,run\nconsole("TFL_ADDED_READY")\nrun()\n')
  command('tranforlanguage start 新 插件')
  await until(() => output.includes('TFL_ADDED_READY'), 'Unicode and space ID discovery')
  command('tranforlanguage stop 新 插件')
  const bad = path.join(config.programs, 'broken'); fs.mkdirSync(bad)
  fs.writeFileSync(path.join(bad, 'main.c'), 'invalid C source for failure isolation\n')
  command('tranforlanguage start broken')
  await until(() => /Program broken failed/.test(output), 'broken plugin rejection')
  for (const id of languages) assert(alive(pid(id)), 'Healthy plugin stopped on another failure')
  results.assertions.push('independent-stop-reload-start', 'legacy-root', 'new-unicode-space-id', 'failure-isolation')
  command('stop')
  await until(() => exited, 'clean server shutdown', 45000, true)
  assert.equal(exitCode, 0, 'Server shutdown failed')
  for (const id of languages) {
    assert(!alive(pid(id)), 'Orphan language process: ' + id)
    assert.equal(events(id).at(-1).event, 'shutdown', 'Missing shutdown event: ' + id)
    for (const e of events(id)) assert(e.args.every(a => typeof a === 'string'), 'Protocol argument is not a string')
  }
  results.assertions.push('clean-shutdown-no-orphans', 'string-protocol-arguments')
  results.events = Object.fromEntries(languages.map(id => [id, [...new Set(events(id).map(e => e.event))]]))
  results.success = true
  console.log('PASS real-server validation: ' + config.loader)
}
main().catch(error => {
  results.success = false; results.error = error.stack
  console.error(error)
  process.exitCode = 1
}).finally(async () => {
  if (bot) bot.quit()
  if (!exited) {
    try { command('stop') } catch {}
    await Promise.race([new Promise(resolve => server.once('exit', resolve)), delay(15000)])
    if (!exited) server.kill('SIGKILL')
  }
  results.finishedAt = new Date().toISOString()
  fs.writeFileSync(path.join(directory, 'smoke-results.json'), JSON.stringify(results, null, 2) + '\n')
  log.end()
})
