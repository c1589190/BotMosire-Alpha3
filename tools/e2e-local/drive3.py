import json, threading, time, sys, urllib.request
from mcp import Mcp

line = [l for l in open('/tmp/bma3-e2e/app.log') if 'started mcp=' in l][-1]
MCP_PORT = int(line.split('mcp=127.0.0.1:')[1].split()[0])
TOKEN = 'e2e-mcp-token'
INJECT = 'http://127.0.0.1:46701/inject'
GROUP = 955432692
RUN = (int(time.time()) % 1_000_000) * 100
report = []
def check(name, ok, detail=''):
    report.append(ok)
    print(('PASS  ' if ok else 'FAIL  ') + name + ('  | ' + str(detail) if detail else ''))

def inject(payload):
    data = json.dumps(payload, ensure_ascii=False).encode()
    req = urllib.request.Request(INJECT, data=data, method='POST')
    req.add_header('Content-Type', 'application/json')
    with urllib.request.urlopen(req, timeout=5) as resp:
        if resp.status != 200:
            raise RuntimeError(f'inject rejected: {resp.status}')

def msg(mid, text):
    return {'post_type': 'message', 'message_type': 'group', 'self_id': 10001,
            'group_id': GROUP, 'user_id': 20002, 'message_id': mid,
            'time': int(time.time()),
            'message': [{'type': 'text', 'data': {'text': text}}],
            'sender': {'nickname': 'LvtN'}}

c = Mcp(f'http://127.0.0.1:{MCP_PORT}', TOKEN)
c.initialize()

# 1) 失败账号必须出现在列表里并带 hint
accounts = {a['accountId']: a for a in c.call('qq_account_list')['accounts']}
bad = accounts.get('bot-http')
check('失败账号出现在 qq_account_list 且带 lastError',
      bad is not None and bad.get('connected') is False
      and 'HTTP 服务' in bad.get('lastError', {}).get('hint', ''),
      (bad or {}).get('lastError', {}).get('hint'))
check('同一个列表里正常账号仍然 connected',
      accounts.get('bot-1', {}).get('connected') is True,
      accounts.get('bot-1', {}).get('self'))

# 2) 没有 SSE 消费者时，inbox_wait 才是唯一的 flush 驱动：应真的阻塞到点
c.call('inbox_ack')
inject(msg(RUN + 1, '等着被等到'))
started = time.time()
waited = c.call('inbox_wait', {'timeoutMs': 8000})
elapsed = round(time.time() - started, 2)
check('inbox_wait 在无 SSE 消费者时阻塞到批次到点',
      waited.get('changed') is True and 1.0 <= elapsed <= 4.0,
      f'等了 {elapsed}s items={len(waited.get("panel", {}).get("items", []))}')

# 3) 超时：没有新条目时应返回 changed=false，且不 ack
started = time.time()
again = c.call('inbox_wait', {'timeoutMs': 1200})
elapsed2 = round(time.time() - started, 2)
panel = c.call('inbox_list')
check('inbox_wait 超时 changed=false 且不动 ack',
      again.get('changed') is False and len(panel['items']) == 1,
      f'{elapsed2}s items={len(panel["items"])}')

c.call('inbox_ack')
print()
print('SUMMARY', sum(1 for ok in report if ok), '/', len(report), 'passed')
sys.exit(0 if all(report) else 1)
