"""Protocol-v1 helpers. stdout belongs to the protocol; log diagnostics to stderr.

Programs can call each other: ``handle(name)`` exposes a method, ``call(target, name, *args)``
waits for the string result, ``publish(topic, *args)`` / ``subscribe(topic)`` broadcast messages.
Call ``call`` from handlers or the main thread; the SDK reads stdin on that thread.
"""
import collections
import itertools
import json
import os
import sys
import threading

_lock = threading.Lock()
_handlers = {}
_methods = {}
_topics = {}
_backlog = collections.deque()
_replies = {}
_ids = itertools.count(1)

#: This program's ID as the server knows it (directory name or "@root").
program_id = os.environ.get("TRANFORLANGUAGE_PROGRAM_ID", "")


class CallError(Exception):
    """Raised when a call fails: unknown program or method, remote error, timeout, shutdown."""


def _send(action, **fields):
    line = json.dumps(dict(action=action, **fields), ensure_ascii=False) + "\n"
    with _lock:
        sys.stdout.write(line)
        sys.stdout.flush()


def broadcast(message):
    _send("broadcast", message=str(message))


def send_message(player, message):
    _send("sendMessage", player=str(player), message=str(message))


def console(message):
    _send("console", message=str(message))


def dispatch_command(command):
    _send("executeCommand", command=str(command))


def on(event):
    """Register a game-event handler: ``@on("PlayerJoin") def f(player): ...``."""
    def register(handler):
        _handlers.setdefault(event, []).append(handler)
        return handler
    return register


def handle(method):
    """Expose a method to other programs. Its return value is sent back as a string."""
    def register(handler):
        _methods[method] = handler
        return handler
    return register


def subscribe(topic):
    """Receive ``publish(topic, ...)`` from other programs: ``handler(sender, *args)``."""
    def register(handler):
        _topics.setdefault(topic, []).append(handler)
        return handler
    return register


def call(target, method, *args):
    """Call ``method`` on program ``target`` and return its string result."""
    call_id = str(next(_ids))
    _send("call", target=str(target), method=str(method), args=[str(a) for a in args], id=call_id)
    while call_id not in _replies:
        message = _read()
        if message is None:
            raise CallError("input closed while waiting for " + str(target))
        kind = message["event"]
        if kind == "reply":
            _replies[str(message.get("id"))] = message
        elif kind == "call":
            _serve(message)  # answer nested calls (A -> B -> A) instead of deadlocking
        else:
            _backlog.append(message)
            if kind == "shutdown":
                raise CallError("server is shutting down")
    reply = _replies.pop(call_id)
    if not reply.get("ok"):
        raise CallError(reply.get("error", "call failed"))
    return reply.get("result", "")


def notify(target, method, *args):
    """One-way call; no reply and no waiting."""
    _send("call", target=str(target), method=str(method), args=[str(a) for a in args])


def publish(topic, *args):
    _send("publish", topic=str(topic), args=[str(a) for a in args])


def _read():
    line = sys.stdin.readline()
    while line and not line.strip():
        line = sys.stdin.readline()
    return json.loads(line) if line else None


def _serve(message):
    handler = _methods.get(message.get("method"))
    try:
        if handler is None:
            raise CallError("unknown method " + str(message.get("method")))
        result, error = handler(*message.get("args", ())), None
    except Exception as failure:  # reported to the caller, the program keeps running
        result, error = None, "%s: %s" % (type(failure).__name__, failure)
    if "id" not in message:
        if error:
            print("tranforlanguage: notification failed: " + error, file=sys.stderr)
        return
    if error is None:
        _send("reply", id=message["id"], result="" if result is None else str(result))
    else:
        _send("reply", id=message["id"], error=error)


def run():
    while True:
        event = _backlog.popleft() if _backlog else _read()
        if event is None:
            break
        kind = event["event"]
        if kind == "call":
            _serve(event)
            continue
        if kind == "reply":
            continue  # a late reply after a timeout
        if kind == "message":
            for handler in _topics.get(event.get("topic"), ()):
                handler(event.get("from", ""), *event["args"])
        for handler in _handlers.get(kind, ()):
            handler(*event["args"])
        if kind == "shutdown":
            break


sendMessage = sendMsg = send_message
dispatchCommand = dispatch_command
