'use strict';
// Protocol-v1 SDK for Node.js. stdout belongs to the protocol; log diagnostics with console.error.
// call() returns a Promise, so programs can call each other without blocking the event loop.
const readline = require('readline');

const handlers = new Map();
const methods = new Map();
const topics = new Map();
const waiting = new Map();
let nextId = 0;
let reader = null;
let finished = false;

class CallError extends Error {}

/** This program's ID as the server knows it (directory name or "@root"). */
const programId = process.env.TRANFORLANGUAGE_PROGRAM_ID || '';

function send(action, fields) {
  process.stdout.write(JSON.stringify(Object.assign({ action }, fields)) + '\n');
}
const strings = (args) => args.map(String);

const broadcast = (message) => send('broadcast', { message: String(message) });
const sendMessage = (player, message) => send('sendMessage', { player: String(player), message: String(message) });
const log = (message) => send('console', { message: String(message) });
const dispatchCommand = (command) => send('executeCommand', { command: String(command) });

/** Register a game-event handler: on('PlayerJoin', (player) => ...). */
function on(event, handler) {
  if (!handlers.has(event)) handlers.set(event, []);
  handlers.get(event).push(handler);
}
/** Expose a method; the (possibly async) return value is sent back as a string. */
function handle(method, handler) { methods.set(method, handler); }
/** Receive publish(topic, ...) from other programs: handler(sender, ...args). */
function subscribe(topic, handler) {
  if (!topics.has(topic)) topics.set(topic, []);
  topics.get(topic).push(handler);
}

/** Call method on program target; resolves to the string result, rejects with CallError. */
function call(target, method, ...args) {
  start();
  if (finished) return Promise.reject(new CallError('server is shutting down'));
  const id = String(++nextId);
  return new Promise((resolve, reject) => {
    waiting.set(id, { resolve, reject });
    send('call', { target: String(target), method: String(method), args: strings(args), id });
  });
}
const notify = (target, method, ...args) => send('call', { target: String(target), method: String(method), args: strings(args) });
const publish = (topic, ...args) => send('publish', { topic: String(topic), args: strings(args) });

async function serve(message) {
  try {
    const handler = methods.get(message.method);
    if (!handler) throw new CallError('unknown method ' + message.method);
    const result = await handler(...message.args);
    if (message.id !== undefined) send('reply', { id: message.id, result: result === undefined || result === null ? '' : String(result) });
  } catch (error) {
    const text = (error && error.name ? error.name + ': ' : '') + (error && error.message !== undefined ? error.message : String(error));
    if (message.id !== undefined) send('reply', { id: message.id, error: text });
    else console.error('tranforlanguage: notification failed: ' + text);
  }
}

function failAll(reason) {
  for (const pending of waiting.values()) pending.reject(new CallError(reason));
  waiting.clear();
}

async function dispatch(message) {
  switch (message.event) {
    case 'call': return serve(message);
    case 'reply': {
      const pending = waiting.get(String(message.id));
      if (!pending) return undefined; // a late reply after a timeout
      waiting.delete(String(message.id));
      return message.ok ? pending.resolve(message.result || '') : pending.reject(new CallError(message.error || 'call failed'));
    }
    case 'message':
      for (const handler of topics.get(message.topic) || []) await handler(message.from || '', ...message.args);
      break;
    default:
  }
  for (const handler of handlers.get(message.event) || []) await handler(...(message.args || []));
  if (message.event === 'shutdown') {
    finished = true;
    failAll('server is shutting down');
    reader.close();
    process.exit(0);
  }
  return undefined;
}

function start() {
  if (reader) return;
  reader = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
  reader.on('line', (line) => {
    if (!line.trim()) return;
    let message;
    try { message = JSON.parse(line); } catch (error) { console.error('tranforlanguage: invalid protocol line'); return; }
    // Calls run concurrently so a handler awaiting another program never blocks events.
    Promise.resolve(dispatch(message)).catch((error) => console.error(error && error.stack ? error.stack : String(error)));
  });
  reader.on('close', () => { finished = true; failAll('input closed'); });
}

/** Start reading events; the process exits after the shutdown event. */
function run() { start(); }

module.exports = {
  CallError, programId, broadcast, sendMessage, sendMsg: sendMessage, console: log, log, dispatchCommand,
  executeCommand: dispatchCommand, on, handle, subscribe, call, notify, publish, run,
};
