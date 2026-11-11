import json, urllib.request, urllib.error

class Mcp:
    def __init__(self, base, token):
        self.url = base.rstrip('/') + '/mcp'
        self.token = token
        self.session = None
        self.next_id = 0

    def _post(self, payload, expect_body=True):
        data = json.dumps(payload).encode()
        req = urllib.request.Request(self.url, data=data, method='POST')
        req.add_header('Content-Type', 'application/json')
        req.add_header('Accept', 'application/json, text/event-stream')
        req.add_header('Authorization', 'Bearer ' + self.token)
        if self.session:
            req.add_header('Mcp-Session-Id', self.session)
        try:
            with urllib.request.urlopen(req, timeout=70) as resp:
                sid = resp.headers.get('Mcp-Session-Id')
                if sid:
                    self.session = sid
                raw = resp.read().decode('utf-8', 'replace')
                ctype = resp.headers.get('Content-Type', '')
                if not expect_body:
                    return None
                return self._parse(raw, ctype)
        except urllib.error.HTTPError as e:
            body = e.read().decode('utf-8', 'replace')
            raise RuntimeError(f'HTTP {e.code}: {body[:300]}') from None

    @staticmethod
    def _parse(raw, ctype):
        if 'text/event-stream' in ctype or raw.lstrip().startswith('event:') or 'data:' in raw:
            last = None
            for line in raw.splitlines():
                if line.startswith('data:'):
                    last = line[5:].strip()
            if last is None:
                raise RuntimeError('empty SSE response: ' + raw[:200])
            return json.loads(last)
        return json.loads(raw) if raw.strip() else None

    def initialize(self):
        self.next_id += 1
        result = self._post({
            'jsonrpc': '2.0', 'id': self.next_id, 'method': 'initialize',
            'params': {'protocolVersion': '2025-06-18', 'capabilities': {},
                       'clientInfo': {'name': 'e2e-driver', 'version': '1'}}})
        self._post({'jsonrpc': '2.0', 'method': 'notifications/initialized'}, expect_body=False)
        return result

    def call(self, name, arguments=None):
        self.next_id += 1
        result = self._post({
            'jsonrpc': '2.0', 'id': self.next_id, 'method': 'tools/call',
            'params': {'name': name, 'arguments': arguments or {}}})
        if 'error' in result:
            raise RuntimeError(f'{name} rpc error: {result["error"]}')
        res = result['result']
        text = ''
        for item in res.get('content', []):
            if item.get('type') == 'text':
                text += item.get('text', '')
        if res.get('isError'):
            raise RuntimeError(f'{name} tool error: {text}')
        try:
            return json.loads(text) if text.strip().startswith(('{', '[')) else text
        except json.JSONDecodeError:
            return text
