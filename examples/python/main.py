"""Minimal protocol-v1 program. Copy with program.json to the program directory."""
import json
import sys

for line in sys.stdin:
    event = json.loads(line)
    if event['event'] == 'shutdown':
        break
    if event['event'] == 'PlayerJoin':
        print(json.dumps({'action': 'sendMessage', 'player': event['args'][0],
                          'message': 'Welcome from Python!'}, ensure_ascii=False), flush=True)
