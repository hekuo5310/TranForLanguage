from tranforcpp import CallError, broadcast, call, handle, on, program_id as me, publish, run, subscribe


@handle("echo")
def echo(value=""):
    return me + ":" + value


@handle("bounce")
def bounce(target, value):
    return call(target, "echo", value)


@on("Ping")
def ping(source, target):
    if source == me:
        broadcast(me + "->" + call(target, "echo", me))


@on("Nested")
def nested(source, via):
    if source == me:
        broadcast("nested " + me + "=" + call(via, "bounce", me, me))


@on("Missing")
def missing(source):
    if source == me:
        try:
            call("nobody", "echo")
        except CallError as error:
            broadcast("missing " + me + ":" + str(error))


@on("Announce")
def announce(source):
    if source == me:
        publish("greet", "hi")


@subscribe("greet")
def greet(sender, text):
    broadcast("greet " + me + " from " + sender + ": " + text)


run()
