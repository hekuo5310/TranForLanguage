local t = require("tranforlanguage")
local me = t.program_id

t.handle("echo", function(value) return me .. ":" .. (value or "") end)
t.handle("bounce", function(target, value) return t.call(target, "echo", value) end)

t.on("Ping", function(source, target)
  if source == me then t.broadcast(me .. "->" .. t.call(target, "echo", me)) end
end)

t.on("Nested", function(source, via)
  if source == me then t.broadcast("nested " .. me .. "=" .. t.call(via, "bounce", me, me)) end
end)

t.on("Missing", function(source)
  if source == me then
    local ok, err = pcall(t.call, "nobody", "echo")
    if not ok and t.is_call_error(err) then t.broadcast("missing " .. me .. ":" .. tostring(err)) end
  end
end)

t.on("Server", function(source)
  if source == me then t.broadcast("server " .. me .. "=" .. t.server("ping", me)) end
end)

t.on("Announce", function(source)
  if source == me then t.publish("greet", "hi") end
end)

t.subscribe("greet", function(sender, text)
  t.broadcast("greet " .. me .. " from " .. sender .. ": " .. text)
end)

t.run()
