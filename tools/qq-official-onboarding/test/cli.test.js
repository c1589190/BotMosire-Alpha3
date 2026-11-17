import test from 'node:test';
import assert from 'node:assert/strict';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import { makeSandbox, createFakeConnector, createFakeQr } from './helpers.js';
import { runCli } from '../lib/cli.js';

const SECRET = 'TEST_ONLY_FAKE_APP_SECRET_DO_NOT_LEAK';

function captureIo() {
  let stdout = '';
  let stderr = '';
  return {
    io: {
      stdout: (text) => {
        stdout += text;
      },
      stderr: (text) => {
        stderr += text;
      },
    },
    get stdout() {
      return stdout;
    },
    get stderr() {
      return stderr;
    },
  };
}

function jsonLines(text) {
  return text
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => JSON.parse(line));
}

test('CLI success prints only status/account ID/path and never the secret', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const credentialsPath = path.join(box.privateDir, 'qq-official-credentials.json');
  const qrPath = path.join(box.qrDir, 'onboarding.png');
  const qr = createFakeQr();
  const { connector } = createFakeConnector((callbacks) => {
    setImmediate(() => callbacks.onQrDisplayed('https://q.qq.com/example?task_id=cli'));
    setImmediate(() => callbacks.onSuccess([{ appId: 'app-id-cli', appSecret: SECRET }]));
  });
  const captured = captureIo();

  const code = await runCli(
    [
      '--credentials',
      credentialsPath,
      '--qr-out',
      qrPath,
      '--no-terminal-qr',
      '--timeout-ms',
      '500',
    ],
    {
      connector,
      io: captured.io,
      onboardingDependencies: { qr: qr.deps, repoRoot: box.repo, platform: 'linux' },
    },
  );

  assert.equal(code, 0);
  assert.equal(captured.stderr, '');
  assert.equal(captured.stdout.includes(SECRET), false);
  const lines = jsonLines(captured.stdout);
  assert.equal(lines.at(-1).status, 'SUCCESS');
  assert.equal(lines.at(-1).accountId, 'app-id-cli');
  assert.equal(lines.at(-1).credentialPath, credentialsPath);
  assert.equal(lines.at(-1).qrPath, qrPath);
  for (const line of lines) {
    for (const key of Object.keys(line)) {
      assert.ok(['status', 'accountId', 'credentialPath', 'qrPath', 'errorCode'].includes(key));
    }
  }
  const saved = JSON.parse(await fs.readFile(credentialsPath, 'utf8'));
  assert.equal(saved.appId, 'app-id-cli');
  assert.equal(saved.appSecret, SECRET);
});

test('CLI failure hides SDK error text and returns non-zero', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const { connector } = createFakeConnector((callbacks) => {
    setImmediate(() => callbacks.onFailure(new Error(`raw sdk message ${SECRET}`)));
  });
  const captured = captureIo();

  const code = await runCli(
    [
      '--credentials',
      path.join(box.privateDir, 'qq-official-credentials.json'),
      '--qr-out',
      path.join(box.qrDir, 'onboarding.png'),
      '--timeout-ms',
      '500',
    ],
    {
      connector,
      io: captured.io,
      onboardingDependencies: {
        qr: createFakeQr().deps,
        repoRoot: box.repo,
        platform: 'linux',
      },
    },
  );

  assert.equal(code, 1);
  assert.equal(captured.stdout.includes(SECRET), false);
  assert.equal(captured.stderr.includes(SECRET), false);
  const last = jsonLines(captured.stdout).at(-1);
  assert.equal(last.status, 'FAILED');
  assert.equal(last.errorCode, 'CONNECTOR_FLOW_FAILED');
});

test('CLI validates arguments without loading the SDK or printing sensitive data', async () => {
  const captured = captureIo();
  const code = await runCli(['--credentials', 'relative.json'], { io: captured.io });
  assert.equal(code, 2);
  assert.equal(captured.stdout, '');
  assert.equal(captured.stderr.includes(SECRET), false);
  const line = JSON.parse(captured.stderr.trim());
  assert.equal(line.status, 'FAILED');
  assert.equal(line.errorCode, 'OUTPUT_PATH_NOT_ABSOLUTE');
});

test('CLI help is available without any connector/network access', async () => {
  const captured = captureIo();
  const code = await runCli(['--help'], { io: captured.io });
  assert.equal(code, 0);
  assert.match(captured.stdout, /Usage:/);
  assert.match(captured.stdout, /never prints the AppSecret/i);
});
