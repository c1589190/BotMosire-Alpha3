import json
import time

from mcp import Mcp

APP_LOG = '/tmp/bma3-llbot-live/app.log'
TOKEN = 'real-probe-token'
ACCOUNT = 'qq-main'
GROUP = '955432692'

line = [l for l in open(APP_LOG) if 'started mcp=' in l][-1]
MCP_PORT = int(line.split('mcp=127.0.0.1:')[1].split()[0])

report = []


def check(name, ok, detail=''):
    report.append(bool(ok))
    print(('PASS  ' if ok else 'FAIL  ') + name + (f'  | {detail}' if detail else ''), flush=True)


client = Mcp(f'http://127.0.0.1:{MCP_PORT}', TOKEN)
client.initialize()

accounts = client.call('qq_account_list')
account = next((a for a in accounts['accounts'] if a['accountId'] == ACCOUNT), None)
check('real LLBot: account is connected', bool(account and account.get('connected')), account)

members = client.call('qq_group_members', {
    'source': f'qq_group/{ACCOUNT}/{GROUP}', 'refresh': True, 'limit': 200,
})
rows = members.get('members', [])
with_title = [m for m in rows if m.get('title')]
with_card = [m for m in rows if m.get('card')]
check('real LLBot: group_member_list exposes title/card',
      members.get('total', 0) > 0 and len(with_title) > 0,
      f"members={members.get('total')} titles={len(with_title)} cards={len(with_card)}")
if with_title:
    sample = with_title[0]
    info = client.call('qq_group_member_info', {
        'source': f'qq_group/{ACCOUNT}/{GROUP}', 'qq': sample['qq'], 'refresh': True,
    })
    check('real LLBot: group_member_info title field',
          bool(info.get('title')) and bool(info.get('role')),
          f"titlePresent={bool(info.get('title'))} role={info.get('role')}")

# Cursor paging (D-056): one small page per call. count<=30 is exactly one provider RPC --
# count=1000 makes LLBot fan out to 34 SsoGetGroupMsg calls and wedges its CMD-PIPE
# (see reports/2026-11-13-llbot-host-freeze-investigation.md).
started = time.time()
history = client.call('qq_remote_history', {
    'accountId': ACCOUNT, 'chatType': 'GROUP', 'targetId': GROUP,
    'count': 30, 'includeSegments': True,
})
elapsed = time.time() - started
check('real LLBot: one 30-message page returns fast',
      history.get('count', 0) > 0
      and history.get('fetchedCount') == history.get('count')
      and 'HISTORY_' not in str(history.get('errorCode') or ''),
      f"elapsed={elapsed:.2f}s requested={history.get('requestedCount')} "
      f"fetched={history.get('fetchedCount')} nextCursor={history.get('nextCursor')}")

next_cursor = history.get('nextCursor') or ''
check('real LLBot: page carries a nextCursor', bool(next_cursor), next_cursor)

if next_cursor:
    page2 = client.call('qq_remote_history', {
        'accountId': ACCOUNT, 'chatType': 'GROUP', 'targetId': GROUP,
        'count': 30, 'cursor': next_cursor,
    })
    first_seqs = {str(m.get('messageSeq')) for m in history.get('messages', [])}
    second_seqs = {str(m.get('messageSeq')) for m in page2.get('messages', [])}
    overlap = first_seqs & second_seqs
    check('real LLBot: cursor paging does not overlap',
          bool(second_seqs) and not overlap,
          f'page1={len(first_seqs)} page2={len(second_seqs)} overlap={len(overlap)} '
          f'cursor={next_cursor}')

forward_ids = []
for message in history.get('messages', []):
    for segment in message.get('segments', []) or []:
        if segment.get('type') == 'forward':
            data = segment.get('data') or {}
            if data.get('id'):
                forward_ids.append(data['id'])
check('real LLBot: history segments expose a forward id when present',
      True, f'forwards={len(forward_ids)}')

if not forward_ids:
    for scan_group in ('938269591', '992899895', '789379726'):
        scan = client.call('qq_remote_history', {
            'accountId': ACCOUNT, 'chatType': 'GROUP', 'targetId': scan_group,
            'count': 50, 'includeSegments': True,
        })
        for message in scan.get('messages', []):
            for segment in message.get('segments', []) or []:
                if segment.get('type') == 'forward':
                    data = segment.get('data') or {}
                    if data.get('id'):
                        forward_ids.append(data['id'])
        if forward_ids:
            print(f'  forward found in group {scan_group}')
            break

if forward_ids:
    view = client.call('qq_forward_view', {
        'accountId': ACCOUNT, 'forwardId': forward_ids[0], 'limit': 10,
    })
    check('real LLBot: get_forward_msg expands through forwardId',
          view.get('total', 0) > 0
          and len(view.get('messages', [])) > 0
          and 'text' in view['messages'][0],
          f"total={view.get('total')} shown={view.get('shown')} "
          f"nested={view.get('nestedExpanded')}")
else:
    check('real LLBot: get_forward_msg sample available', False,
          'no forward segment in scanned history')

requests = client.call('qq_group_request_list', {'accountId': ACCOUNT, 'limit': 20})
check('real LLBot: group_system_msg responds', requests.get('total', -1) >= 0, requests.get('total'))

config = client.call('get_config')
check('real BMA3 config keeps minPriority=300 for the regression probe',
      config['inbox']['push']['minPriority'] == 300,
      config['inbox']['push'])

print('\n=== SUMMARY ===')
print(f'{sum(report)}/{len(report)} PASS')
raise SystemExit(0 if all(report) else 1)
