"""Protocol-v1 helpers. stdout belongs to the protocol; log diagnostics to stderr."""
import json
import sys
import threading

_lock = threading.Lock()
_handlers = {}


def _send(action, **fields):
    with _lock:
        sys.stdout.write(json.dumps(dict(action=action, **fields), ensure_ascii=False) + "\n")
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
    def register(handler):
        _handlers.setdefault(event, []).append(handler)
        return handler
    return register


def run():
    for line in sys.stdin:
        event = json.loads(line)
        for handler in _handlers.get(event["event"], ()):
            handler(*event["args"])
        if event["event"] == "shutdown":
            break


sendMessage = sendMsg = send_message
dispatchCommand = dispatch_command
