import json
import os
import threading
import time
import urllib.request

from mcp import Mcp

APP_LOG = '/tmp/bma3-e2e-004/app.log'
INJECT = 'http://127.0.0.1:46701/inject'
DROP = 'http://127.0.0.1:46701/drop'
TOKEN = 'e2e-mcp-token'
GROUP = 955432692
PRIVATE = 132843233
DATA = '/tmp/bma3-e2e-004/data'

line = [l for l in open(APP_LOG) if 'started mcp=' in l][-1]
MCP_PORT = int(line.split('mcp=127.0.0.1:')[1].split()[0])
HEALTH_PORT = int(line.split('health=127.0.0.1:')[1].split()[0])
INBOX_PORT = int(line.split('inboxHttp=127.0.0.1:')[1].split()[0])

report = []


def check(name, ok, detail=''):
    report.append(bool(ok))
    print(('PASS  ' if ok else 'FAIL  ') + name + (f'  | {detail}' if detail else ''), flush=True)


def inject(payload):
    data = json.dumps(payload, ensure_ascii=False).encode()
    request = urllib.request.Request(INJECT, data=data, method='POST')
    request.add_header('Content-Type', 'application/json')
    with urllib.request.urlopen(request, timeout=5) as response:
        if response.status != 200:
            raise RuntimeError(f'inject rejected: {response.status}')


def drop_connection():
    request = urllib.request.Request(DROP, data=b'{}', method='POST')
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.loads(response.read().decode())


def get(path, token=None):
    request = urllib.request.Request(f'http://127.0.0.1:{HEALTH_PORT}{path}')
    if token:
        request.add_header('Authorization', 'Bearer ' + token)
    with urllib.request.urlopen(request, timeout=5) as response:
        return response.status, response.read().decode()


def wait(predicate, timeout=15.0, interval=0.1):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            value = predicate()
            if value:
                return value
        except Exception:
            pass
        time.sleep(interval)
    return None


def group_message(message_id, text, sender=None, segments=None, group=GROUP):
    payload = {
        'post_type': 'message', 'message_type': 'group', 'self_id': 10001,
        'group_id': group, 'user_id': 20002, 'message_id': message_id,
        'time': int(time.time()), 'message': segments or [
            {'type': 'text', 'data': {'text': text}}],
        'sender': sender or {'nickname': '南沐秋', 'card': '群名片小王', 'role': 'admin'},
    }
    return payload


def private_message(message_id, text, sender=None):
    return {
        'post_type': 'message', 'message_type': 'private', 'sub_type': 'friend',
        'self_id': 10001, 'user_id': PRIVATE, 'message_id': message_id,
        'time': int(time.time()),
        'message': [{'type': 'text', 'data': {'text': text}}],
        'sender': sender or {'nickname': '南沐秋'},
    }


def clear_inbox(client):
    client.call('inbox_ack')
    time.sleep(0.3)


def items_of(client, source_prefix):
    items = client.call('inbox_items')
    return [item for item in items['items'] if item['source'].startswith(source_prefix)]


client = Mcp(f'http://127.0.0.1:{MCP_PORT}', TOKEN)
client.initialize()

# The SSE connection is the waiter that drives awaitPush/flush; without it pushRevision never
# advances (the inbox is pull-driven by design).
stop_sse = threading.Event()


def sse_reader():
    while not stop_sse.is_set():
        try:
            request = urllib.request.Request(
                f'http://127.0.0.1:{INBOX_PORT}/v1/inboxes/bma3/events')
            request.add_header('Authorization', 'Bearer ' + TOKEN)
            request.add_header('Accept', 'text/event-stream')
            with urllib.request.urlopen(request, timeout=30) as response:
                for _ in response:
                    if stop_sse.is_set():
                        return
        except Exception:
            time.sleep(0.2)


threading.Thread(target=sse_reader, daemon=True).start()
time.sleep(0.3)

# ---------------------------------------------------------------- watchdog
probe = client.call('bma3_watchdog_probe', {'component': 'qqAccount'})
check('watchdog qqAccount probe is UP',
      isinstance(probe, list) and probe and probe[0]['health'] == 'UP', probe)
status = client.call('bma3_watchdog_status')
components = status['components']
check('watchdog exposes the service components',
      {'mcp', 'inboxHttp', 'qqAccount', 'db', 'dshPlugin', 'disk', 'process'} <= set(components),
      sorted(components))
check('watchdog overall not DOWN', status['overall'] != 'DOWN', status['overall'])
ready_code, ready_body = get('/readyz')
check('readyz 200 while critical components are up', ready_code == 200, ready_body)
watchdog_code, watchdog_body = get('/watchdog', TOKEN)
check('watchdog endpoint is Bearer protected', watchdog_code == 200 and 'components' in watchdog_body,
      watchdog_code)

# ---------------------------------------------------------------- directory / card / title
client.call('qq_group_list')
time.sleep(0.3)
clear_inbox(client)
inject(group_message(int(time.time() * 1000) % 10**9, '群目录消息'))
created = wait(lambda: items_of(client, f'qq_group/bot-1/{GROUP}'), 8)
check('group message lands in inbox', bool(created), created)
if created:
    detail = client.call('inbox_get', {'itemId': created[0]['id']})
    check('inbox source label uses the group name',
          'E2E群' in detail['sourceLabel'], detail['sourceLabel'])
    entry = detail['entries'][-1]
    check('inbox entry uses the group card', '群名片小王' in entry['line'], entry['line'])
    check('inbox entry keeps the nickname marker',
          any('昵称' in marker for marker in entry['markers']), entry['markers'])

members = client.call('qq_group_members', {'source': f'qq_group/bot-1/{GROUP}', 'refresh': True})
target_member = next((m for m in members.get('members', []) if m['qq'] == '20002'), None)
check('qq_group_members returns card/title',
      members.get('total', 0) >= 1 and target_member is not None
      and target_member['title'] == '大肥鱼' and target_member['card'] == '群名片小王',
      members.get('members'))
member = client.call('qq_group_member_info',
                     {'source': f'qq_group/bot-1/{GROUP}', 'qq': '20002'})
check('qq_group_member_info returns title', member.get('title') == '大肥鱼', member.get('title'))
inject(group_message(int(time.time() * 1000) % 10**9 + 1, '群头衔消息'))
titled = wait(lambda: items_of(client, f'qq_group/bot-1/{GROUP}'), 8)
if titled:
    detail = client.call('inbox_get', {'itemId': titled[-1]['id']})
    check('inbox entry carries the cached title',
          any('头衔' in marker for marker in detail['entries'][-1]['markers']),
          detail['entries'][-1]['markers'])

# ---------------------------------------------------------------- 1B: two items per source
clear_inbox(client)
before = client.call('inbox_status')['pushRevision']
inject(private_message(int(time.time() * 1000) % 10**9 + 10, '同源第一条'))
pushed = wait(lambda: client.call('inbox_status')['pushRevision'] > before, 10)
check('first private item is pushed (pushRevision advances)', bool(pushed), before)
inject(private_message(int(time.time() * 1000) % 10**9 + 11, '同源第二条'))
two = wait(lambda: len(items_of(client, f'qq_private/bot-1/{PRIVATE}')) >= 2, 8)
same = items_of(client, f'qq_private/bot-1/{PRIVATE}')
check('same source can hold two unacked items', bool(two), len(same))
check('the second item is marked active（消息更新活跃）',
      any(item.get('continued') for item in same), [item.get('continued') for item in same])
if len(same) >= 2:
    first_id, second_id = same[0]['id'], same[1]['id']
    ack_one = client.call('inbox_ack', {'itemIds': [first_id]})
    ack_two = client.call('inbox_ack', {'itemIds': [first_id]})
    check('ack is idempotent for an already-acked item',
          ack_one['remaining'] >= 0 and ack_two['remaining'] >= 0, (ack_one, ack_two))
    dismissed = client.call('inbox_dismiss', {'scope': 'ids', 'itemIds': [second_id], 'allowImportant': True})
    check('dismiss clears an item without reading it', dismissed['dismissed'] == 1, dismissed)

# ---------------------------------------------------------------- dismiss filters
clear_inbox(client)
inject(group_message(int(time.time() * 1000) % 10**9 + 20, '低优先清理', group=123450))
wait(lambda: items_of(client, 'qq_group/bot-1/123450'), 8)
dry = client.call('inbox_dismiss',
                  {'scope': 'filter', 'priorityBelow': 300, 'dryRun': True})
check('dismiss dryRun matches without changing state',
      dry['matched'] >= 1 and dry['dismissed'] == 0, dry)
applied = client.call('inbox_dismiss', {'scope': 'filter', 'priorityBelow': 300})
check('dismiss filter clears low-priority items', applied['dismissed'] >= 1, applied)

# ---------------------------------------------------------------- cards / forward / invites
clear_inbox(client)
card_payload = json.dumps({
    'title': '群邀请', 'desc': '来玩', 'bizsrc': 'qun.invite',
    'jumpUrl': 'https://example.com/invite',
    'url': 'mqqapi://group/invite_join?groupcode=123456&msgseq=777&token=SECRET',
    'meta': {'news': {'title': '群邀请', 'desc': '来玩'}},
}, ensure_ascii=False)
inject(group_message(
    int(time.time() * 1000) % 10**9 + 30, '',
    segments=[
        {'type': 'json', 'data': {'data': card_payload}},
        {'type': 'forward', 'data': {'id': 'fwd-1', 'count': 2, 'title': '转发'}},
    ]))
wait(lambda: items_of(client, f'qq_group/bot-1/{GROUP}'), 8)
history = client.call('qq_conversation_history',
                      {'source': f'qq_group/bot-1/{GROUP}', 'limit': 20,
                       'includeSegments': True})
segments = []
for message in history['messages']:
    for segment in message.get('segments', []):
        segments.append(segment)
card = next((s for s in segments if s['type'] == 'json'), None)
forward = next((s for s in segments if s['type'] == 'forward'), None)
check('card and forward segments expose fileIds',
      bool(card and card.get('fileId')) and bool(forward and forward.get('fileId')),
      [s['type'] for s in segments])
if card:
    view = client.call('qq_card_view', {'fileId': card['fileId']})
    check('qq_card_view reads title and invite group/sequence',
          view.get('title') == '群邀请'
          and view.get('groupInvite', {}).get('groupId') == '123456'
          and view.get('groupInvite', {}).get('sequence') == '777', view)
    check('qq_card_view never stores the invite ticket',
          'SECRET' not in json.dumps(view, ensure_ascii=False), 'ticket leaked')
if forward:
    expanded = client.call('qq_forward_view', {'fileId': forward['fileId']})
    check('qq_forward_view expands one level with nested marker',
          expanded.get('total') == 2
          and '[嵌套合并转发]' in json.dumps(expanded, ensure_ascii=False),
          expanded)
requests = client.call('qq_group_request_list')
check('group request list sees the invite',
      requests.get('total') == 1 and requests['requests'][0]['requestId'] == '777',
      requests)
accepted = client.call('qq_group_invite_accept', {'groupId': '123456'})
check('group invite accept reports SENT', accepted.get('state') == 'SENT', accepted)
decided = client.call('qq_group_request_decide',
                      {'requestId': '777', 'approve': True})
check('group request decide reports SENT', decided.get('state') == 'SENT', decided)

# ---------------------------------------------------------------- file + remote history + search
upload = os.path.join(DATA, 'e2e-upload.txt')
with open(upload, 'w', encoding='utf-8') as handle:
    handle.write('hello from bma3')
sent_file = client.call('qq_conversation_send_file',
                        {'source': f'qq_group/bot-1/{GROUP}', 'filePath': upload})
check('file send uses the OneBot upload action',
      sent_file.get('state') == 'SENT'
      and sent_file.get('action') == 'upload_group_file'
      and sent_file.get('via') == 'base64', sent_file)
remote = client.call('qq_remote_history',
                     {'chatType': 'GROUP', 'targetId': str(GROUP), 'count': 100})
check('qq_remote_history is read-through and capped',
      remote.get('count') == 1 and remote['messages'][0]['text'] == '远端历史', remote)
search = client.call('qq_search', {'query': '群目录', 'scope': 'all'})
groups = client.call('qq_search', {'query': 'E2E', 'scope': 'groups'})
check('qq_search finds messages and group names',
      len(search.get('messages', [])) >= 1 and len(groups.get('groups', [])) >= 1,
      (search.get('messages'), groups.get('groups')))
people = client.call('qq_search', {'query': '小王', 'scope': 'people'})
check('qq_search resolves a person to qq_person_id',
      bool(people.get('people'))
      and people['people'][0]['personId'] == 'qq_person_id/20002', people)

# ---------------------------------------------------------------- hot reload + panel budget
patched = client.call('set_config', {'patch': {
    'weighting.importantDelaySeconds': 3,
    'inbox.display.maxCharsPerItem': 80,
}})
check('set_config reports applied/reloaded',
      patched.get('applied') is True and patched.get('reloaded') is True, patched)
config = client.call('get_config')
check('delay and panel budget are hot-reloaded',
      config['weighting']['importantDelaySeconds'] == 3
      and config['inbox']['display']['maxCharsPerItem'] == 80,
      config.get('weighting', {}).get('importantDelaySeconds'))
client.call('set_config', {'patch': {
    'weighting.importantDelaySeconds': 1,
    'inbox.display.maxCharsPerItem': 1200,
}})

# ---------------------------------------------------------------- watchdog reconnect
dropped = drop_connection()
check('fake OneBot dropped the socket', dropped.get('dropped') is True, dropped)
reconnected = wait(lambda: any(account.get('connected')
                               for account in client.call('qq_account_list')['accounts']), 20)
check('watchdog reconnects the QQ account', bool(reconnected))
status = client.call('bma3_watchdog_status')
check('watchdog records the reconnect remediation',
      any(event.get('component') == 'qqAccount'
          and (event.get('remediation') or event.get('health') == 'DOWN')
          for event in status.get('recentEvents', [])),
      status.get('recentEvents', [])[-3:])

# Record the push watermark for the restart check. Wait for the current batch to settle so the
# saved value is not stale by the time the process is stopped.
settled = wait(lambda: client.call('inbox_status')['pendingPush'] == 0, 15)
time.sleep(1.5)
check('test state settled before the restart check', bool(settled))
state = client.call('inbox_status')
with open('/tmp/bma3-e2e-004/restart-state.json', 'w', encoding='utf-8') as handle:
    json.dump({'pushRevision': state['pushRevision'],
               'available': state['available']}, handle)

print('\n=== SUMMARY ===')
print(f'{sum(report)}/{len(report)} PASS')
raise SystemExit(0 if all(report) else 1)
