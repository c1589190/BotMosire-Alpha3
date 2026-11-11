import json, threading, time, sys, urllib.request, urllib.request
from mcp import Mcp

line = [l for l in open('/tmp/bma3-e2e/app.log') if 'started mcp=' in l][-1]
MCP_PORT = int(line.split('mcp=127.0.0.1:')[1].split()[0])
INBOX_PORT = int(line.split('inboxHttp=127.0.0.1:')[1].split()[0])
TOKEN = 'e2e-mcp-token'
INJECT = 'http://127.0.0.1:46701/inject'
GROUP = 955432692
events, stop = [], threading.Event()
RUN = (int(time.time()) % 1_000_000) * 100
report = []
def check(name, ok, detail=''):
    report.append(ok)
    print(('PASS  ' if ok else 'FAIL  ') + name + ('  | ' + str(detail) if detail else ''))

def listener():
    while not stop.is_set():
        try:
            _sse_once()
        except Exception:
            time.sleep(0.2)


def _sse_once():
    req = urllib.request.Request(f'http://127.0.0.1:{INBOX_PORT}/v1/inboxes/bma3/events')
    req.add_header('Authorization', 'Bearer ' + TOKEN)
    req.add_header('Accept', 'text/event-stream')
    with urllib.request.urlopen(req, timeout=45) as resp:
        event = None
        for raw in resp:
            text = raw.decode('utf-8', 'replace').rstrip('\n')
            if text.startswith('event:'):
                event = text[6:].strip()
            elif text.startswith('data:'):
                events.append((time.time(), event, text[5:].strip()))
            if stop.is_set():
                return

def inject(payload):
    data = json.dumps(payload, ensure_ascii=False).encode()
    req = urllib.request.Request(INJECT, data=data, method='POST')
    req.add_header('Content-Type', 'application/json')
    with urllib.request.urlopen(req, timeout=5) as resp:
        if resp.status != 200:
            raise RuntimeError(f'inject rejected: {resp.status}')

def group_msg(mid, text, at=None):
    segments = []
    if at == 'bot':
        segments.append({'type': 'at', 'data': {'qq': '10001'}})
    elif at == 'all':
        segments.append({'type': 'at', 'data': {'qq': 'all'}})
    segments.append({'type': 'text', 'data': {'text': text}})
    return {'post_type': 'message', 'message_type': 'group', 'self_id': 10001,
            'group_id': GROUP, 'user_id': 20002, 'message_id': mid,
            'time': int(time.time()), 'message': segments, 'sender': {'nickname': 'LvtN'}}

def private_msg(mid, text):
    return {'post_type': 'message', 'message_type': 'private', 'self_id': 10001,
            'user_id': 132843233, 'message_id': mid, 'time': int(time.time()),
            'message': [{'type': 'text', 'data': {'text': text}}],
            'sender': {'nickname': '南沐秋'}}

def wait_panel(since, timeout=10):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for t, e, d in events:
            if e == 'panel' and t >= since:
                panel = json.loads(d)
                if panel.get('items'):
                    return t, panel
        time.sleep(0.05)
    return None, None

c = Mcp(f'http://127.0.0.1:{MCP_PORT}', TOKEN)
c.initialize()
threading.Thread(target=listener, daemon=True).start()
time.sleep(0.4)
c.call('inbox_ack')

# 1) 群普通(2s) + 0.4s 后私聊(1s) -> 私聊到点时把两条一起带走
t0 = time.time()
inject(group_msg(RUN + 1, '群里先来一条'))
time.sleep(0.4)
inject(private_msg(RUN + 2, '私聊随后到'))
t_panel, panel = wait_panel(t0)
latency = None if t_panel is None else round(t_panel - t0, 2)
sources = sorted(i['sourceLabel'] for i in panel['items']) if panel else []
check('立即项到点带走全部 pending',
      t_panel is not None and latency <= 2.2 and len(sources) == 2,
      f'latency={latency}s sources={sources}')
c.call('inbox_ack')

# 2) 群里 @bot = 300 -> 立即；@全体 = 220 -> 等批次
t1 = time.time()
inject(group_msg(RUN + 3, '艾特机器人', at='bot'))
t_panel2, panel2 = wait_panel(t1)
lat1 = None if t_panel2 is None else round(t_panel2 - t1, 2)
check('群里 @bot 立即推送', t_panel2 is not None and lat1 <= 2.0, f'latency={lat1}s')
c.call('inbox_ack')

t2 = time.time()
inject(group_msg(RUN + 4, '全体注意', at='all'))
t_panel3, panel3 = wait_panel(t2)
lat2 = None if t_panel3 is None else round(t_panel3 - t2, 2)
check('@全体 只按批次推（不立即）', t_panel3 is not None and 1.6 <= lat2 <= 3.5,
      f'latency={lat2}s')
c.call('inbox_ack')

# 3) read + ack 游标链
inject(group_msg(RUN + 5, '游标测试'))
time.sleep(0.5)
read1 = c.call('qq_conversation_read', {'source': f'qq_group/bot-1/{GROUP}', 'limit': 20})
unread = read1['messages']
check('qq_conversation_read 带回发送人', unread and unread[-1]['sender']['name'] == 'LvtN',
      unread[-1]['sender'] if unread else None)
c.call('qq_conversation_ack', {'source': f'qq_group/bot-1/{GROUP}', 'seq': read1['lastSeq']})
read2 = c.call('qq_conversation_read', {'source': f'qq_group/bot-1/{GROUP}', 'limit': 20})
check('ack 后游标前进，read 不再返回同一条', read2['messages'] == [], read2['readSeq'])

# 4) 引用回复 -> 出站行回写平台 message_id
reply = c.call('qq_reply', {'source': f'qq_group/bot-1/{GROUP}',
                            'text': '收到', 'replyToMessageId': str(RUN + 5)})
state = reply[0]['state'] if isinstance(reply, list) else reply
history = c.call('qq_conversation_history', {'source': f'qq_group/bot-1/{GROUP}', 'limit': 50})
outbound = [m for m in history['messages'] if m['direction'] == 'OUT']
check('出站发送成功后回写平台 message_id + self',
      outbound and outbound[-1]['self'] is True and outbound[-1]['externalMessageId'].isdigit(),
      {'state': state, 'externalMessageId': outbound[-1]['externalMessageId'] if outbound else None})
c.call('inbox_ack')
print()
print('SUMMARY', sum(1 for ok in report if ok), '/', len(report), 'passed')
stop.set()
sys.exit(0 if all(report) else 1)
