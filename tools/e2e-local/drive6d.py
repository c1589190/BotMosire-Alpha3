import json
import time
import urllib.request

from mcp import Mcp

APP_LOG = '/tmp/bma3-e2e-004/app.log'
DROP = 'http://127.0.0.1:46701/drop'
TOKEN = 'e2e-mcp-token'

line = [l for l in open(APP_LOG) if 'started mcp=' in l][-1]
MCP_PORT = int(line.split('mcp=127.0.0.1:')[1].split()[0])

report = []


def check(name, ok, detail=''):
    report.append(bool(ok))
    print(('PASS  ' if ok else 'FAIL  ') + name + (f'  | {detail}' if detail else ''), flush=True)


def wait(predicate, timeout=20.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            value = predicate()
            if value:
                return value
        except Exception:
            pass
        time.sleep(0.2)
    return None


client = Mcp(f'http://127.0.0.1:{MCP_PORT}', TOKEN)
client.initialize()

patched = client.call('set_config', {'patch': {
    'deployer.qq': '132843233',
    'deployer.enabled': True,
}})
check('deployer alert config is writable by the Agent',
      patched.get('applied') is True, patched)

request = urllib.request.Request(DROP, data=b'{}', method='POST')
with urllib.request.urlopen(request, timeout=5) as response:
    dropped = json.loads(response.read().decode())
check('socket dropped for the alert test', dropped.get('dropped') is True, dropped)

reconnected = wait(lambda: any(account.get('connected')
                               for account in client.call('qq_account_list')['accounts']), 20)
check('account reconnected before the alert check', bool(reconnected))

alert = wait(lambda: next(
    (message for message in client.call('qq_conversation_history', {
        'source': 'qq_private/bot-1/132843233', 'limit': 30,
    })['messages']
     if message.get('direction') == 'OUT'
     and '[BMA3 watchdog]' in message.get('text', '')), None), 15)
check('deployer receives a watchdog private alert', bool(alert),
      alert.get('text') if alert else None)
if alert:
    check('alert names the failed component and code',
          'qqAccount' in alert['text'] and 'DOWN' in alert['text'], alert['text'])

client.call('set_config', {'patch': {'deployer.enabled': False}})

print('\n=== SUMMARY ===')
print(f'{sum(report)}/{len(report)} PASS')
raise SystemExit(0 if all(report) else 1)
