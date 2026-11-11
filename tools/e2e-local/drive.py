import json, os, threading, time, urllib.request, sys
from mcp import Mcp

MCP_PORT = int([l for l in open('/tmp/bma3-e2e/app.log') if 'started mcp=' in l][-1]
               .split('mcp=127.0.0.1:')[1].split()[0])
INBOX_PORT = int([l for l in open('/tmp/bma3-e2e/app.log') if 'started mcp=' in l][-1]
                 .split('inboxHttp=127.0.0.1:')[1].split()[0])
TOKEN = 'e2e-mcp-token'
INBOX = f'http://127.0.0.1:{INBOX_PORT}'
INJECT = 'http://127.0.0.1:46701/inject'
GROUP = 955432692
NEW_GROUP = 888888

events = []
stop = threading.Event()

def sse_listener():
    while not stop.is_set():
        try:
            _sse_once()
        except Exception:
            time.sleep(0.2)


def _sse_once():
    req = urllib.request.Request(f'{INBOX}/v1/inboxes/bma3/events')
    req.add_header('Authorization', 'Bearer ' + TOKEN)
    req.add_header('Accept', 'text/event-stream')
    with urllib.request.urlopen(req, timeout=45) as resp:
        event = None
        for line in resp:
            line = line.decode('utf-8', 'replace').rstrip('\n')
            if line.startswith('event:'):
                event = line[6:].strip()
            elif line.startswith('data:'):
                events.append((time.time(), event, line[5:].strip()))
            if stop.is_set():
                return

def inject(payload):
    data = json.dumps(payload, ensure_ascii=False).encode()
    req = urllib.request.Request(INJECT, data=data, method='POST')
    req.add_header('Content-Type', 'application/json')
    with urllib.request.urlopen(req, timeout=5) as resp:
        if resp.status != 200:
            raise RuntimeError(f'inject rejected: {resp.status}')

def msg(mid, text, group=None, user=20002, at_bot=False, image_url=None):
    segments = []
    if at_bot:
        segments.append({'type': 'at', 'data': {'qq': '10001'}})
    if image_url:
        segments.append({'type': 'image', 'data': {'file': 'e2e.png', 'url': image_url}})
    segments.append({'type': 'text', 'data': {'text': text}})
    payload = {'post_type': 'message', 'message_type': 'group' if group else 'private',
               'self_id': 10001, 'user_id': user, 'message_id': mid,
               'time': int(time.time()), 'message': segments,
               'sender': {'nickname': 'LvtN'}}
    if group:
        payload['group_id'] = group
    return payload

def panel_events():
    return [(t, json.loads(d)) for (t, e, d) in events if e == 'panel']

def wait_panel(since, timeout=12):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for t, data in panel_events():
            if t >= since and data.get('items'):
                return t, data
        time.sleep(0.05)
    return None, None

RUN = (int(time.time()) % 1_000_000) * 100
report = []
def check(name, ok, detail=''):
    report.append((name, bool(ok), detail))
    print(('PASS  ' if ok else 'FAIL  ') + name + ('  | ' + str(detail) if detail else ''))

client = Mcp(f'http://127.0.0.1:{MCP_PORT}', TOKEN)
init = client.initialize()
check('MCP initialize', init.get('result', {}).get('serverInfo', {}).get('name') == 'bma3',
      init.get('result', {}).get('serverInfo'))

cfg = client.call('get_config')
check('get_config 返回来源权重与推送筛选',
      cfg['weighting']['sourcePriority'].get('qq_group') == 200
      and cfg['inbox'].get('push', {}).get('minPriority') == -1000,
      {'sourcePriority': cfg['weighting']['sourcePriority'],
       'push': cfg['inbox'].get('push')})

accounts = client.call('qq_account_list')
acc = accounts['accounts'][0]
check('qq_account_list: connected + 自动学到的 self',
      acc['connected'] is True and acc.get('self', {}).get('nickname') == 'BMA3E2E', acc)

threading.Thread(target=sse_listener, daemon=True).start()
time.sleep(0.5)
client.call('inbox_ack')   # 清掉上一次运行留下的未 ack 条目，保证计数断言稳定

# 1) 群普通消息 -> 应该等 normalDelaySeconds(2s) 才推
t0 = time.time()
inject(msg(RUN + 1, '群里普通消息', group=GROUP))
t_panel, panel = wait_panel(t0)
latency = None if t_panel is None else round(t_panel - t0, 2)
check('群普通消息按 normalDelay 批量推送', t_panel is not None and 1.5 <= latency <= 4.0,
      f'latency={latency}s items={len(panel["items"]) if panel else 0}')

# 2) 私聊 -> 300 -> 1s 立即；应把 pending 一起带走
t1 = time.time()
inject(msg(RUN + 2, '私聊急事'))
t_panel2, panel2 = wait_panel(t1)
latency2 = None if t_panel2 is None else round(t_panel2 - t1, 2)
check('私聊按重要档立即推送', t_panel2 is not None and latency2 <= 2.0, f'latency={latency2}s')

items = client.call('inbox_items')
check('inbox_items 看到两个来源', items['total'] == 2,
      [(i['index'], i['sourceLabel'], i['priority']) for i in items['items']])

first_id = items['items'][0]['id']
detail = client.call('inbox_get', {'itemId': first_id})
check('inbox_get 取回窗口 entries', len(detail['entries']) >= 1,
      detail['entries'][0]['line'])

# 3) 按序号逐条 ack（带 revision 校验）
rev = items['revision']
acked = client.call('inbox_ack', {'indices': [1], 'revision': rev})
check('逐条 ack（序号 + revision）', acked['remaining'] == 1, acked)

stale = None
try:
    client.call('inbox_ack', {'indices': [1], 'revision': rev})
except RuntimeError as failure:
    stale = str(failure)
check('过期 revision 被拒绝', stale is not None and 'REVISION_CONFLICT' in stale, stale)

# 4) inbox_wait：无新条目时超时返回 changed=false
waited = client.call('inbox_wait', {'timeoutMs': 1500})
check('inbox_wait 超时返回 changed=false', waited.get('changed') is False, waited)

# 5) set_config 热重载：把新群压到 -301 -> 消息不落库
loaded = client.call('set_config', {'patch': {f'weighting.sourcePriority.qq_group/{NEW_GROUP}': -301}})
check('set_config 生效并热重载', loaded.get('applied') and loaded.get('reloaded'), loaded.get('effective', {}).get('weighting', {}).get('sourcePriority'))

inject(msg(RUN + 3, '这条应该被丢弃', group=NEW_GROUP))
time.sleep(1.2)
dropped = client.call('qq_conversation_history', {'source': f'qq_group/bot-1/{NEW_GROUP}', 'limit': 50})
seen = [m['messageId'] for m in dropped['messages']]
check('低于 dropBelow 的消息不落库', str(RUN + 3) not in seen, seen)

# 6) 把该群抬到 350 -> 立即推送并落库
client.call('set_config', {'patch': {f'weighting.sourcePriority.qq_group/{NEW_GROUP}': 350}})
t2 = time.time()
inject(msg(RUN + 4, '这个群现在很重要', group=NEW_GROUP))
t_panel3, panel3 = wait_panel(t2)
latency3 = None if t_panel3 is None else round(t_panel3 - t2, 2)
check('具体来源规则抬到 350 后立即推送', t_panel3 is not None and latency3 <= 2.0,
      f'latency={latency3}s')

history = client.call('qq_conversation_history',
                      {'source': f'qq_group/bot-1/{NEW_GROUP}', 'limit': 50})
row = [m for m in history['messages']
       if m['externalMessageId'] == str(RUN + 4)][0]
check('历史带发送人与平台消息 id',
      row['sender']['id'] == '20002' and row['sender']['name'] == 'LvtN'
      and row['externalMessageId'] == str(RUN + 4) and row['self'] is False, row['sender'])

print()
print('SUMMARY', sum(1 for _, ok, _ in report if ok), '/', len(report), 'passed')
stop.set()
sys.exit(0 if all(ok for _, ok, _ in report) else 1)
