-- Protocol-v1 SDK for Lua 5.1-5.4 and LuaJ. stdout belongs to the protocol; log diagnostics to stderr.
--
-- Programs can call each other: handle(name, fn) exposes a method, call(target, name, ...) waits for
-- the string result, publish(topic, ...) / subscribe(topic, fn) broadcast messages. Call call() from
-- handlers or the main chunk; the SDK reads stdin there. Failed calls raise a CallError (see is_call_error).
local M = {}

local handlers, methods, topics = {}, {}, {}
local backlog, replies = {}, {}
local next_id = 0

--- This program's ID as the server knows it (directory name or "@root").
M.program_id = os.getenv("TRANFORLANGUAGE_PROGRAM_ID") or ""

-- JSON -----------------------------------------------------------------------------------------

local escapes = { ['"'] = '\\"', ['\\'] = '\\\\', ['\b'] = '\\b', ['\f'] = '\\f', ['\n'] = '\\n', ['\r'] = '\\r', ['\t'] = '\\t' }

local function encode_string(value)
  return '"' .. value:gsub('[%c"\\]', function(c)
    return escapes[c] or string.format('\\u%04x', c:byte())
  end) .. '"'
end

local function encode(value)
  local kind = type(value)
  if kind == "string" then return encode_string(value) end
  if kind == "number" or kind == "boolean" then return tostring(value) end
  if kind == "table" then
    if value[1] ~= nil or next(value) == nil and getmetatable(value) == M.array then
      local parts = {}
      for i = 1, #value do parts[i] = encode(value[i]) end
      return "[" .. table.concat(parts, ",") .. "]"
    end
    local parts, keys = {}, {}
    for key in pairs(value) do keys[#keys + 1] = key end
    table.sort(keys)
    for i, key in ipairs(keys) do parts[i] = encode_string(key) .. ":" .. encode(value[key]) end
    return "{" .. table.concat(parts, ",") .. "}"
  end
  return "null"
end

local function utf8_char(code)
  if code < 0x80 then return string.char(code) end
  if code < 0x800 then return string.char(0xC0 + math.floor(code / 0x40), 0x80 + code % 0x40) end
  if code < 0x10000 then
    return string.char(0xE0 + math.floor(code / 0x1000), 0x80 + math.floor(code / 0x40) % 0x40, 0x80 + code % 0x40)
  end
  return string.char(0xF0 + math.floor(code / 0x40000), 0x80 + math.floor(code / 0x1000) % 0x40,
    0x80 + math.floor(code / 0x40) % 0x40, 0x80 + code % 0x40)
end

local decode_value
local simple = { b = '\b', f = '\f', n = '\n', r = '\r', t = '\t', ['"'] = '"', ['\\'] = '\\', ['/'] = '/' }

local function skip(text, i) return text:find("[^ \t\r\n]", i) or #text + 1 end

local function decode_string(text, i)
  local out, j = {}, i + 1
  while true do
    local c = text:sub(j, j)
    if c == "" then error("unterminated string") end
    if c == '"' then return table.concat(out), j + 1 end
    if c == "\\" then
      local e = text:sub(j + 1, j + 1)
      if e == "u" then
        local code = tonumber(text:sub(j + 2, j + 5), 16)
        j = j + 6
        if code >= 0xD800 and code <= 0xDBFF and text:sub(j, j + 1) == "\\u" then
          local low = tonumber(text:sub(j + 2, j + 5), 16)
          code = 0x10000 + (code - 0xD800) * 0x400 + (low - 0xDC00)
          j = j + 6
        end
        out[#out + 1] = utf8_char(code)
      else
        out[#out + 1] = simple[e] or e
        j = j + 2
      end
    else
      local stop = text:find('["\\]', j) or #text + 1
      out[#out + 1] = text:sub(j, stop - 1)
      j = stop
    end
  end
end

decode_value = function(text, i)
  i = skip(text, i)
  local c = text:sub(i, i)
  if c == '"' then return decode_string(text, i) end
  if c == "{" then
    local object = {}
    i = skip(text, i + 1)
    if text:sub(i, i) == "}" then return object, i + 1 end
    while true do
      local key
      key, i = decode_string(text, skip(text, i))
      i = skip(text, i) + 1 -- ':'
      object[key], i = decode_value(text, i)
      i = skip(text, i)
      local d = text:sub(i, i)
      i = i + 1
      if d == "}" then return object, i end
    end
  end
  if c == "[" then
    local array = setmetatable({}, M.array)
    i = skip(text, i + 1)
    if text:sub(i, i) == "]" then return array, i + 1 end
    while true do
      array[#array + 1], i = decode_value(text, i)
      i = skip(text, i)
      local d = text:sub(i, i)
      i = i + 1
      if d == "]" then return array, i end
    end
  end
  if text:sub(i, i + 3) == "true" then return true, i + 4 end
  if text:sub(i, i + 4) == "false" then return false, i + 5 end
  if text:sub(i, i + 3) == "null" then return nil, i + 4 end
  local number = text:match("^-?%d+%.?%d*[eE]?[-+]?%d*", i)
  if number and number ~= "" then return tonumber(number), i + #number end
  error("invalid JSON at " .. i)
end

M.array = {}
--- Encodes a Lua value as JSON (tables with [1] are arrays). Useful for structured call arguments.
M.json_encode = encode
--- Decodes JSON text into Lua values.
function M.json_decode(text) return (decode_value(text, 1)) end

-- Protocol ---------------------------------------------------------------------------------------

local CallError = {}
CallError.__index = CallError
CallError.__tostring = function(e) return e.message end

--- True when err was raised by a failed call (unknown program or method, remote error, timeout, shutdown).
function M.is_call_error(err) return getmetatable(err) == CallError end

local function call_error(message) return setmetatable({ message = message }, CallError) end

local function strings(...)
  local out = setmetatable({}, M.array)
  for i = 1, select("#", ...) do out[i] = tostring((select(i, ...))) end
  return out
end

local function send(action, fields)
  fields.action = action
  io.stdout:write(encode(fields), "\n")
  io.stdout:flush()
end

function M.broadcast(message) send("broadcast", { message = tostring(message) }) end
function M.send_message(player, message) send("sendMessage", { player = tostring(player), message = tostring(message) }) end
function M.console(message) send("console", { message = tostring(message) }) end
function M.dispatch_command(command) send("executeCommand", { command = tostring(command) }) end

--- Registers a game-event handler: on("PlayerJoin", function(player) ... end).
function M.on(event, handler)
  handlers[event] = handlers[event] or {}
  table.insert(handlers[event], handler)
end

--- Exposes a method to other programs; its return value is sent back as a string.
function M.handle(method, handler) methods[method] = handler end

--- Receives publish(topic, ...) from other programs: handler(sender, ...).
function M.subscribe(topic, handler)
  topics[topic] = topics[topic] or {}
  table.insert(topics[topic], handler)
end

local function read()
  while true do
    local line = io.stdin:read("*l")
    if line == nil then return nil end
    if line:find("%S") then return M.json_decode(line) end
  end
end

local function unpack_args(list) return (table.unpack or unpack)(list or {}, 1, #(list or {})) end

local function serve(message)
  local handler = methods[message.method]
  local ok, result
  if handler == nil then
    ok, result = false, "unknown method " .. tostring(message.method)
  else
    ok, result = pcall(handler, unpack_args(message.args))
  end
  if message.id == nil then
    if not ok then io.stderr:write("tranforlanguage: notification failed: ", tostring(result), "\n") end
    return
  end
  if ok then
    send("reply", { id = message.id, result = result == nil and "" or tostring(result) })
  else
    send("reply", { id = message.id, error = tostring(result) })
  end
end

--- Calls method on program target and returns its string result; raises a CallError on failure.
function M.call(target, method, ...)
  next_id = next_id + 1
  local id = tostring(next_id)
  send("call", { target = tostring(target), method = tostring(method), args = strings(...), id = id })
  while replies[id] == nil do
    local message = read()
    if message == nil then error(call_error("input closed while waiting for " .. tostring(target)), 0) end
    if message.event == "reply" then
      replies[tostring(message.id)] = message
    elseif message.event == "call" then
      serve(message) -- answer nested calls (A -> B -> A) instead of deadlocking
    else
      backlog[#backlog + 1] = message
      if message.event == "shutdown" then error(call_error("server is shutting down"), 0) end
    end
  end
  local reply = replies[id]
  replies[id] = nil
  if not reply.ok then error(call_error(reply.error or "call failed"), 0) end
  return reply.result or ""
end

--- Calls a game action on the server, e.g. server("teleport", "Alex", 0, 64, 0).
function M.server(method, ...) return M.call("@server", method, ...) end

--- One-way call; no reply and no waiting.
function M.notify(target, method, ...)
  send("call", { target = tostring(target), method = tostring(method), args = strings(...) })
end

function M.publish(topic, ...) send("publish", { topic = tostring(topic), args = strings(...) }) end

local function dispatch(list, ...)
  for _, handler in ipairs(list or {}) do
    local ok, failure = pcall(handler, ...)
    if not ok then io.stderr:write("tranforlanguage: handler failed: ", tostring(failure), "\n") end
  end
end

--- Reads events until shutdown, answering calls; handlers also see "message" and "shutdown".
function M.run()
  while true do
    local event
    if #backlog > 0 then event = table.remove(backlog, 1) else event = read() end
    if event == nil then break end
    local kind = event.event
    if kind == "call" then
      serve(event)
    elseif kind ~= "reply" then -- a late reply after a timeout is dropped
      if kind == "message" then dispatch(topics[event.topic], event.from or "", unpack_args(event.args)) end
      dispatch(handlers[kind], unpack_args(event.args))
      if kind == "shutdown" then break end
    end
  end
end

M.sendMessage, M.sendMsg, M.dispatchCommand, M.programId = M.send_message, M.send_message, M.dispatch_command, M.program_id
return M
