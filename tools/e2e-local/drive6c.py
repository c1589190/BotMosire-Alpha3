import json
import threading
import time
import urllib.request

from mcp import Mcp

APP_LOG = '/tmp/bma3-e2e-004/app.log'
INJECT = 'http://127.0.0.1:46701/inject'
TOKEN = 'e2e-mcp-token'
PRIVATE = 132843233

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


def wait(predicate, timeout=20.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            value = predicate()
            if value:
                return value
        except Exception:
            pass
        time.sleep(0.05)
    return None


def private(message_id, text):
    return {
        'post_type': 'message', 'message_type': 'private', 'sub_type': 'friend',
        'self_id': 10001, 'user_id': PRIVATE, 'message_id': message_id,
        'time': int(time.time()),
        'message': [{'type': 'text', 'data': {'text': text}}],
        'sender': {'nickname': '南沐秋'},
    }


def group(message_id, text, group_id):
    return {
        'post_type': 'message', 'message_type': 'group', 'self_id': 10001,
        'group_id': group_id, 'user_id': 20002, 'message_id': message_id,
        'time': int(time.time()),
        'message': [{'type': 'text', 'data': {'text': text}}],
        'sender': {'nickname': '南沐秋', 'card': '群名片小王', 'role': 'admin'},
    }


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

# ---------------------------------------------------------------- hot reload actually applies
client.call('inbox_ack')
client.call('set_config', {'patch': {
    'weighting.importantDelaySeconds': 4,
    'inbox.display.maxCharsPerItem': 80,
}})
time.sleep(0.3)
before = client.call('inbox_status')['pushRevision']
started = time.time()
inject(private(int(time.time() * 1000) % 10**9, '长'.join(['长文本'] * 400)))
advanced = wait(lambda: client.call('inbox_status')['pushRevision'] > before, 15)
elapsed = time.time() - started
check('important delay hot reload takes effect (>=3.5s)', advanced and elapsed >= 3.5,
      f'{elapsed:.2f}s')
panel = client.call('inbox_list')
truncated = [item for item in panel['items'] if item.get('truncatedChars', 0) > 0]
check('maxCharsPerItem truncates the panel item', bool(truncated),
      [{k: item.get(k) for k in ('source', 'truncatedChars')} for item in panel['items']])

# ---------------------------------------------------------------- same-source debounce resets
client.call('inbox_ack')
client.call('set_config', {'patch': {'weighting.normalDelaySeconds': 3}})
time.sleep(0.3)
before = client.call('inbox_status')['pushRevision']
inject(group(int(time.time() * 1000) % 10**9 + 1, 'debounce-1', 123451))
time.sleep(2.0)
second_at = time.time()
inject(group(int(time.time() * 1000) % 10**9 + 2, 'debounce-2', 123451))
advanced = wait(lambda: client.call('inbox_status')['pushRevision'] > before, 15)
elapsed = time.time() - second_at
check('same-source append resets the debounce deadline (>=2.5s)', advanced and elapsed >= 2.5,
      f'{elapsed:.2f}s')

# ---------------------------------------------------------------- minPriority is ignored
client.call('set_config', {'patch': {
    'inbox.push.minPriority': 300,
    'weighting.normalDelaySeconds': 2,
}})
time.sleep(0.3)
client.call('inbox_ack')
before = client.call('inbox_status')['pushRevision']
inject(group(int(time.time() * 1000) % 10**9 + 3, '低于300也要推', 123452))
low_advanced = wait(lambda: client.call('inbox_status')['pushRevision'] > before, 12)
check('low-priority item still pushes when minPriority=300 (field ignored)',
      bool(low_advanced), before)
client.call('set_config', {'patch': {'inbox.push.minPriority': -1000}})

# ---------------------------------------------------------------- HTTP dismiss + plugin bridge
request = urllib.request.Request(
    f'http://127.0.0.1:{INBOX_PORT}/v1/inboxes/bma3/dismiss',
    data=json.dumps({'scope': 'visible', 'dryRun': True}).encode(), method='POST')
request.add_header('Content-Type', 'application/json')
request.add_header('Authorization', 'Bearer ' + TOKEN)
with urllib.request.urlopen(request, timeout=5) as response:
    dismiss_body = json.loads(response.read().decode())
    check('HTTP /dismiss works and honours dryRun',
          response.status == 200 and dismiss_body['dismissed'] == 0, dismiss_body)

heartbeat = {
    'sessionId': 'session-e2e-004', 'bound': True, 'sse': 'open',
    'lastPushRevision': client.call('inbox_status')['pushRevision'],
    'lastMcpStartId': 'mcp-e2e', 'lastError': '', 'appliedControlId': '',
}
request = urllib.request.Request(
    f'http://127.0.0.1:{INBOX_PORT}/v1/inboxes/bma3/plugin/heartbeat',
    data=json.dumps(heartbeat).encode(), method='POST')
request.add_header('Content-Type', 'application/json')
request.add_header('Authorization', 'Bearer ' + TOKEN)
with urllib.request.urlopen(request, timeout=5) as response:
    body = json.loads(response.read().decode())
    check('plugin heartbeat is accepted', response.status == 200 and 'mcpStartId' in body, body)

remediated = client.call('bma3_watchdog_remediate', {'component': 'dshPlugin'})
check('watchdog can queue a plugin restore control',
      remediated.get('changed') is True, remediated)
request = urllib.request.Request(
    f'http://127.0.0.1:{INBOX_PORT}/v1/inboxes/bma3/plugin/heartbeat',
    data=json.dumps(heartbeat).encode(), method='POST')
request.add_header('Content-Type', 'application/json')
request.add_header('Authorization', 'Bearer ' + TOKEN)
with urllib.request.urlopen(request, timeout=5) as response:
    body = json.loads(response.read().decode())
    control = body.get('control')
    check('heartbeat response carries the restore control',
          control and control.get('action') == 'restore'
          and control.get('sessionId') == 'session-e2e-004', control)
if control:
    applied = dict(heartbeat, appliedControlId=control['id'])
    request = urllib.request.Request(
        f'http://127.0.0.1:{INBOX_PORT}/v1/inboxes/bma3/plugin/heartbeat',
        data=json.dumps(applied).encode(), method='POST')
    request.add_header('Content-Type', 'application/json')
    request.add_header('Authorization', 'Bearer ' + TOKEN)
    with urllib.request.urlopen(request, timeout=5) as response:
        after = json.loads(response.read().decode())
        check('applied control id clears the pending control',
              after.get('control') is None, after)

client.call('set_config', {'patch': {
    'weighting.importantDelaySeconds': 1,
    'weighting.normalDelaySeconds': 2,
    'inbox.display.maxCharsPerItem': 1200,
}})

print('\n=== SUMMARY ===')
print(f'{sum(report)}/{len(report)} PASS')
raise SystemExit(0 if all(report) else 1)
