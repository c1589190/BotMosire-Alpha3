"""Real-provider check for qq_remote_history cursor paging (D-056).

Contract: docs/superpowers/specs/2026-11-13-remote-history-paging-design.md
Evidence: docs/superpowers/reports/2026-11-13-llbot-host-freeze-investigation.md

Why this exists: `count` used to be a "how many do you want" knob and large pages were
guessed down 1000 -> 300 -> 100. The provider actually fans a call out into fixed
30-message windows fetched 5-at-a-time, so `count=1000` issues 34 `SsoGetGroupMsg`
round-trips and wedges the QQ client's CMD-PIPE. The fix is one small page per call
plus the `message_seq` cursor. This probe verifies that against a real LLBot.

Usage:
    LLBOT_TOKEN=... BMA3_MCP_TOKEN=... APP_LOG=/tmp/x/app.log \\
        python3 tools/e2e-local/history_paging_probe.py

Env:
    APP_LOG     app log to scrape the MCP port from (required)
    MCP_TOKEN   MCP bearer (default: real-probe-token)
    ACCOUNT     accountId (default: cna-bot)
    GROUP       group id to page through (default: 955432692)
    PAGE        page size (default: 30)
"""
import os
import sys

from mcp import Mcp

APP_LOG = os.environ['APP_LOG']
TOKEN = os.environ.get('MCP_TOKEN', 'real-probe-token')
ACCOUNT = os.environ.get('ACCOUNT', 'cna-bot')
GROUP = os.environ.get('GROUP', '955432692')
PAGE = int(os.environ.get('PAGE', '30'))

results = []


def check(name, ok, detail=''):
    results.append(bool(ok))
    print(('PASS  ' if ok else 'FAIL  ') + name + (f'  | {detail}' if detail else ''), flush=True)


line = [l for l in open(APP_LOG) if 'started mcp=' in l][-1]
port = int(line.split('mcp=127.0.0.1:')[1].split()[0])
client = Mcp(f'http://127.0.0.1:{port}', TOKEN)
client.initialize()

# ---- one small page is one provider RPC ----
page1 = client.call('qq_remote_history', {
    'accountId': ACCOUNT, 'chatType': 'GROUP', 'targetId': GROUP, 'count': PAGE,
})
seqs1 = [str(m['messageSeq']) for m in page1.get('messages', [])]
check('page 1 returns without a HISTORY_ error',
      page1.get('count', 0) > 0 and not str(page1.get('errorCode') or '').startswith('HISTORY_'),
      f"count={page1.get('count')} requested={page1.get('requestedCount')} "
      f"next={page1.get('nextCursor')} prev={page1.get('prevCursor')}")
check('page 1 carries nextCursor and prevCursor',
      bool(page1.get('nextCursor')) and bool(page1.get('prevCursor')),
      f"{page1.get('nextCursor')} / {page1.get('prevCursor')}")

# ---- the cursor walks strictly backwards with no overlap ----
cursor = page1.get('nextCursor')
page2 = client.call('qq_remote_history', {
    'accountId': ACCOUNT, 'chatType': 'GROUP', 'targetId': GROUP,
    'count': PAGE, 'cursor': cursor,
})
seqs2 = [str(m['messageSeq']) for m in page2.get('messages', [])]
check('page 2 returns messages', bool(seqs2), f'n={len(seqs2)}')
if seqs1 and seqs2:
    check('pages do not overlap', not (set(seqs1) & set(seqs2)),
          f'overlap={sorted(set(seqs1) & set(seqs2))[:5]}')
    check('pages are contiguous (oldest(page1) == newest(page2)+1)',
          int(min(int(s) for s in seqs1)) - int(max(int(s) for s in seqs2)) == 1,
          f'min1={min(seqs1)} max2={max(seqs2)}')

# ---- prevCursor walks forward again and lands back on page 1 ----
back = client.call('qq_remote_history', {
    'accountId': ACCOUNT, 'chatType': 'GROUP', 'targetId': GROUP,
    'count': PAGE, 'cursor': page2.get('prevCursor'),
})
back_seqs = [str(m['messageSeq']) for m in back.get('messages', [])]
check('prevCursor walks back to page 1',
      bool(back_seqs) and set(back_seqs) == set(seqs1),
      f'match={set(back_seqs) == set(seqs1)} n={len(back_seqs)}')

# ---- the old poison pill is rejected before it reaches the provider ----
rejected = client.call('qq_remote_history', {
    'accountId': ACCOUNT, 'chatType': 'GROUP', 'targetId': GROUP, 'count': 100,
})
check('count=100 (the new maximum) is accepted',
      rejected.get('count', 0) > 0 and not str(rejected.get('errorCode') or ''),
      f"count={rejected.get('count')} errorCode={rejected.get('errorCode')}")

# The old poison pill must never reach the provider. It can be refused either by the MCP
# input schema (preferred: never enters the tool) or by the tool itself.
try:
    too_big = client.call('qq_remote_history', {
        'accountId': ACCOUNT, 'chatType': 'GROUP', 'targetId': GROUP, 'count': 1000,
    })
    refused = too_big.get('errorCode') == 'INVALID_ARGUMENT'
    detail = f"errorCode={too_big.get('errorCode')}"
except RuntimeError as refused_by_schema:
    refused = 'maximum' in str(refused_by_schema) or 'validation' in str(refused_by_schema).lower()
    detail = str(refused_by_schema)[:120]
check('count=1000 is refused before reaching the provider', refused, detail)

passed = sum(results)
print(f'\n{passed}/{len(results)} checks passed')
sys.exit(0 if passed == len(results) else 1)
