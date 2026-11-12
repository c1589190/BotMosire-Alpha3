import json
import os
import time

from mcp import Mcp

APP_LOG = '/tmp/bma3-llbot-live/app.log'
TOKEN = 'real-send-token'
ACCOUNT = 'qq-main'
USER = int(os.environ.get('BMA3_TEST_QQ', '0') or '0')
if USER <= 0:
    raise SystemExit('set BMA3_TEST_QQ=<your qq> to run the real send test')
SOURCE = f'qq_private/{ACCOUNT}/{USER}'
FILE = '/tmp/bma3-llbot-live/data/bma3-0.0.4-test.txt'

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
check('real LLBot account connected', bool(account and account.get('connected')), account)

text_result = client.call('qq_private_send', {
    'accountId': ACCOUNT,
    'userId': USER,
    'text': 'BMA3 0.0.4 测试：这是一条文本消息。收到请回复。',
})
text_state = text_result[0].get('state') if isinstance(text_result, list) and text_result else None
check('real private text send', text_state == 'SENT', text_result)

file_result = client.call('qq_conversation_send_file', {
    'source': SOURCE,
    'filePath': FILE,
    'fileName': 'bma3-0.0.4-test.txt',
})
check('real private file send',
      file_result.get('state') == 'SENT',
      {k: file_result.get(k) for k in ('state', 'action', 'via', 'attempted', 'bytes', 'fileName')})

time.sleep(2)
history = client.call('qq_conversation_history', {'source': SOURCE, 'limit': 20})
outbound = [m for m in history['messages'] if m.get('direction') == 'OUT']
inbound = [m for m in history['messages'] if m.get('direction') == 'IN']
check('outbound rows were written to the board', len(outbound) >= 2, len(outbound))
check('outbound rows carry externalMessageId',
      all(bool(m.get('externalMessageId')) for m in outbound),
      [bool(m.get('externalMessageId')) for m in outbound])

print('  waiting up to 60s for a reply from %s ...' % USER, flush=True)
deadline = time.time() + 60
reply = []
while time.time() < deadline:
    try:
        current = client.call('qq_conversation_history', {'source': SOURCE, 'limit': 20})
        reply = [m for m in current['messages'] if m.get('direction') == 'IN']
        if reply:
            break
    except Exception:
        pass
    time.sleep(2)
if reply:
    print('  inbound reply received: count=%d sender=%s text=%s'
          % (len(reply), reply[-1].get('sender'), reply[-1].get('text')), flush=True)
    check('real inbound reply received', True, len(reply))
else:
    print('  no inbound reply within 60s (the user may reply later)', flush=True)

print('\n=== SUMMARY ===')
print(f'{sum(report)}/{len(report)} PASS')
raise SystemExit(0 if all(report) else 1)
