import json
import threading
import time
import urllib.request

from mcp import Mcp

APP_LOG = '/tmp/bma3-e2e-004/app.log'
INJECT = 'http://127.0.0.1:46701/inject'
TOKEN = 'e2e-mcp-token'
PRIVATE = 132843233
STATE = '/tmp/bma3-e2e-004/restart-state.json'

saved = json.load(open(STATE))
line = [l for l in open(APP_LOG) if 'started mcp=' in l][-1]
MCP_PORT = int(line.split('mcp=127.0.0.1:')[1].split()[0])
INBOX_PORT = int(line.split('inboxHttp=127.0.0.1:')[1].split()[0])

report = []


def check(name, ok, detail=''):
    report.append(bool(ok))
    print(('PASS  ' if ok else 'FAIL  ') + name + (f'  | {detail}' if detail else ''), flush=True)


def inject(payload):
    request = urllib.request.Request(INJECT, data=json.dumps(payload, ensure_ascii=False).encode(),
                                     method='POST')
    request.add_header('Content-Type', 'application/json')
    with urllib.request.urlopen(request, timeout=5) as response:
        if response.status != 200:
            raise RuntimeError(f'inject rejected: {response.status}')


def wait(predicate, timeout=15.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            value = predicate()
            if value:
                return value
        except Exception:
            pass
        time.sleep(0.1)
    return None


client = Mcp(f'http://127.0.0.1:{MCP_PORT}', TOKEN)
client.initialize()

stop = threading.Event()


def sse_reader():
    while not stop.is_set():
        try:
            request = urllib.request.Request(
                f'http://127.0.0.1:{INBOX_PORT}/v1/inboxes/bma3/events')
            request.add_header('Authorization', 'Bearer ' + TOKEN)
            request.add_header('Accept', 'text/event-stream')
            with urllib.request.urlopen(request, timeout=30) as response:
                for _ in response:
                    if stop.is_set():
                        return
        except Exception:
            time.sleep(0.2)


threading.Thread(target=sse_reader, daemon=True).start()
time.sleep(0.3)

status = client.call('inbox_status')
check('pushRevision survives a Java restart',
      status['pushRevision'] == saved['pushRevision'],
      (saved['pushRevision'], status['pushRevision']))
check('frozen unacked items are not pending after restart',
      status['pendingPush'] == 0, status)
items = client.call('inbox_items')
check('frozen items are still visible to tools',
      len(items['items']) == saved['available'],
      (saved['available'], len(items['items'])))
time.sleep(2.5)
status2 = client.call('inbox_status')
check('no automatic replay of already-pushed items',
      status2['pushRevision'] == saved['pushRevision'], status2)

before = status2['pushRevision']
inject({
    'post_type': 'message', 'message_type': 'private', 'sub_type': 'friend',
    'self_id': 10001, 'user_id': PRIVATE, 'message_id': int(time.time() * 1000) % 10**9,
    'time': int(time.time()),
    'message': [{'type': 'text', 'data': {'text': '重启后的新消息'}}],
    'sender': {'nickname': '南沐秋'},
})
advanced = wait(lambda: client.call('inbox_status')['pushRevision'] > before, 10)
check('new messages still advance the push revision after restart', bool(advanced), before)

print('\n=== SUMMARY ===')
print(f'{sum(report)}/{len(report)} PASS')
raise SystemExit(0 if all(report) else 1)
