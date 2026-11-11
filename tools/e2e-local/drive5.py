import json, threading, time, sys, urllib.request
from mcp import Mcp

line = [l for l in open('/tmp/bma3-e2e/app.log') if 'started mcp=' in l][-1]
MCP_PORT = int(line.split('mcp=127.0.0.1:')[1].split()[0])
INBOX_PORT = int(line.split('inboxHttp=127.0.0.1:')[1].split()[0])
TOKEN = 'e2e-mcp-token'; INJECT = 'http://127.0.0.1:46701/inject'; GROUP = 955432692
RUN = (int(time.time()) % 1_000_000) * 100
events, stop, report = [], threading.Event(), []
def check(n, ok, d=''):
    report.append(ok); print(('PASS  ' if ok else 'FAIL  ') + n + ('  | ' + str(d) if d else ''))
def listener():
    while not stop.is_set():
        try:
            _sse_once()
        except Exception:
            time.sleep(0.2)


def _sse_once():
    req = urllib.request.Request(f'http://127.0.0.1:{INBOX_PORT}/v1/inboxes/bma3/events')
    req.add_header('Authorization', 'Bearer ' + TOKEN); req.add_header('Accept', 'text/event-stream')
    with urllib.request.urlopen(req, timeout=45) as resp:
        ev = None
        for raw in resp:
            t = raw.decode('utf-8', 'replace').rstrip('\n')
            if t.startswith('event:'): ev = t[6:].strip()
            elif t.startswith('data:'): events.append((time.time(), ev, t[5:].strip()))
            if stop.is_set(): return
def inject(p):
    data = json.dumps(p, ensure_ascii=False).encode()
    req = urllib.request.Request(INJECT, data=data, method='POST')
    req.add_header('Content-Type', 'application/json')
    with urllib.request.urlopen(req, timeout=5) as resp:
        if resp.status != 200:
            raise RuntimeError(f'inject rejected: {resp.status}')
def gmsg(mid, text, reply_to=None):
    segs = []
    if reply_to: segs.append({'type': 'reply', 'data': {'id': reply_to}})
    segs.append({'type': 'text', 'data': {'text': text}})
    return {'post_type': 'message', 'message_type': 'group', 'self_id': 10001,
            'group_id': GROUP, 'user_id': 20002, 'message_id': mid,
            'time': int(time.time()), 'message': segs, 'sender': {'nickname': 'LvtN'}}
def wait_panel(since, timeout=10):
    end = time.time() + timeout
    while time.time() < end:
        for t, e, d in events:
            if e == 'panel' and t >= since:
                p = json.loads(d)
                if p.get('items'): return t, p
        time.sleep(0.05)
    return None, None

c = Mcp(f'http://127.0.0.1:{MCP_PORT}', TOKEN); c.initialize()
threading.Thread(target=listener, daemon=True).start(); time.sleep(0.4)
c.call('inbox_ack')

# 先让 bot 发一条，拿到真实平台 message_id
sent = c.call('qq_reply', {'source': f'qq_group/bot-1/{GROUP}', 'text': '我说一句'})
hist = c.call('qq_conversation_history', {'source': f'qq_group/bot-1/{GROUP}', 'limit': 20})
bot_msg_id = [m for m in hist['messages'] if m['self']][-1]['externalMessageId']
check('bot 出站消息有平台 id', bot_msg_id.isdigit(), bot_msg_id)
c.call('inbox_ack')

# 引用 bot 自己的话 -> 200 + 100 = 300 -> 立即
t1 = time.time(); inject(gmsg(RUN + 1, '引用你的话', reply_to=bot_msg_id))
t_p1, p1 = wait_panel(t1)
l1 = None if t_p1 is None else round(t_p1 - t1, 2)
check('引用 bot 自己的消息 -> 立即推送', t_p1 is not None and l1 <= 2.0, f'latency={l1}s')
c.call('inbox_ack')

# 引用别人的话 -> 200 -> 批次
t2 = time.time(); inject(gmsg(RUN + 2, '引用别人的话', reply_to=str(RUN + 1)))
t_p2, p2 = wait_panel(t2)
l2 = None if t_p2 is None else round(t_p2 - t2, 2)
check('引用别人（非 bot）-> 只按批次', t_p2 is not None and l2 >= 1.6, f'latency={l2}s')
c.call('inbox_ack')

print(); print('SUMMARY', sum(1 for ok in report if ok), '/', len(report), 'passed')
stop.set(); sys.exit(0 if all(report) else 1)
