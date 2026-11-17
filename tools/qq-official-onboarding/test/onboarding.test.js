import test from 'node:test';
import assert from 'node:assert/strict';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import { makeSandbox, listFiles, createFakeConnector, createFakeQr } from './helpers.js';
import { runOnboarding } from '../lib/onboarding.js';

const SECRET = 'TEST_ONLY_FAKE_APP_SECRET_DO_NOT_LEAK';
const QR_URL_1 = 'https://q.qq.com/qqbot/openclaw/connect.html?task_id=one&source=bma3&_wv=2';
const QR_URL_2 = 'https://q.qq.com/qqbot/openclaw/connect.html?task_id=two&source=bma3&_wv=2';

function baseOptions(box, connector, qr, events, extra = {}) {
  return {
    connector,
    credentialsPath: path.join(box.privateDir, 'qq-official-credentials.json'),
    qrPath: path.join(box.qrDir, 'onboarding.png'),
    repoRoot: box.repo,
    platform: 'linux',
    qr: qr.deps,
    onEvent: (event) => events.push(event),
    timeoutMs: 200,
    source: 'bma3',
    ...extra,
  };
}

test('fake SDK success writes separable credentials and emits no secret', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const events = [];
  const qr = createFakeQr();
  const { connector, calls } = createFakeConnector((callbacks) => {
    setImmediate(() => callbacks.onQrDisplayed(QR_URL_1));
    setImmediate(() =>
      callbacks.onSuccess([
        { appId: 'app-id-ok', appSecret: SECRET, userOpenid: 'fake-openid-must-not-output' },
      ]),
    );
  });

  const result = await runOnboarding(baseOptions(box, connector, qr, events));

  assert.equal(result.status, 'SUCCESS');
  assert.equal(result.accountId, 'app-id-ok');
  assert.equal(result.credentialPath, path.join(box.privateDir, 'qq-official-credentials.json'));
  assert.equal(result.qrPath, path.join(box.qrDir, 'onboarding.png'));
  assert.equal(calls.options.displayQrCodeToConsole, false);
  assert.equal(calls.options.source, 'bma3');
  assert.ok(calls.options.signal);
  assert.equal(calls.stopCalled, true);
  assert.equal(JSON.stringify(events).includes(SECRET), false);
  assert.equal(JSON.stringify(events).includes('fake-openid-must-not-output'), false);

  const credentials = JSON.parse(await fs.readFile(result.credentialPath, 'utf8'));
  assert.deepEqual(Object.keys(credentials).sort(), ['appId', 'appSecret']);
  assert.equal(credentials.appId, 'app-id-ok');
  assert.equal(credentials.appSecret, SECRET);
  assert.equal((await fs.stat(result.credentialPath)).mode & 0o777, 0o600);
  assert.ok(events.some((event) => event.status === 'QR_READY'));
  assert.ok(events.some((event) => event.type === 'terminal_qr'));
});

test('fake SDK failure text is never copied to events or errors', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const events = [];
  const qr = createFakeQr();
  const { connector, calls } = createFakeConnector((callbacks) => {
    setImmediate(() => callbacks.onFailure(new Error(`sdk exploded with ${SECRET}`)));
  });

  const result = await runOnboarding(baseOptions(box, connector, qr, events));

  assert.equal(result.status, 'FAILED');
  assert.equal(result.errorCode, 'CONNECTOR_FLOW_FAILED');
  assert.equal(JSON.stringify(events).includes(SECRET), false);
  assert.equal(String(result).includes(SECRET), false);
  assert.equal(calls.stopCalled, true);
  assert.deepEqual(await listFiles(box.privateDir), []);
  assert.deepEqual(await listFiles(box.qrDir), []);
});

test('timeout aborts, stops the fake SDK, and removes QR/credential residue', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const events = [];
  const qr = createFakeQr();
  const { connector, calls } = createFakeConnector((callbacks) => {
    setImmediate(() => callbacks.onQrDisplayed(QR_URL_1));
  });

  const result = await runOnboarding(
    baseOptions(box, connector, qr, events, { timeoutMs: 40 }),
  );

  assert.equal(result.status, 'TIMEOUT');
  assert.equal(result.errorCode, 'ONBOARDING_TIMEOUT');
  assert.equal(calls.stopCalled, true);
  assert.equal(calls.options.signal.aborted, true);
  assert.equal(JSON.stringify(events).includes(SECRET), false);
  assert.deepEqual(await listFiles(box.privateDir), []);
  assert.deepEqual(await listFiles(box.qrDir), []);
});

test('external cancellation aborts and removes generated files', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const events = [];
  const qr = createFakeQr();
  const controller = new AbortController();
  const { connector, calls } = createFakeConnector((callbacks) => {
    setImmediate(() => callbacks.onQrDisplayed(QR_URL_1));
    setTimeout(() => controller.abort(), 20);
  });

  const result = await runOnboarding(
    baseOptions(box, connector, qr, events, { signal: controller.signal, timeoutMs: 500 }),
  );

  assert.equal(result.status, 'CANCELLED');
  assert.equal(result.errorCode, 'ONBOARDING_CANCELLED');
  assert.equal(calls.stopCalled, true);
  assert.deepEqual(await listFiles(box.privateDir), []);
  assert.deepEqual(await listFiles(box.qrDir), []);
});

test('expiry refresh emits a new QR and only final artifacts remain on success', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const events = [];
  const qr = createFakeQr();
  const { connector } = createFakeConnector((callbacks) => {
    setImmediate(() => callbacks.onQrDisplayed(QR_URL_1));
    setTimeout(() => callbacks.onQrExpired(), 2);
    setTimeout(() => callbacks.onQrDisplayed(QR_URL_2), 3);
    setTimeout(() => callbacks.onSuccess([{ appId: 'app-id-refreshed', appSecret: SECRET }]), 8);
  });

  const result = await runOnboarding(baseOptions(box, connector, qr, events));

  assert.equal(result.status, 'SUCCESS');
  assert.deepEqual(qr.rendered, [QR_URL_1, QR_URL_2]);
  assert.deepEqual(
    events.filter((event) => event.status).map((event) => event.status),
    ['QR_READY', 'QR_EXPIRED', 'QR_READY', 'SUCCESS'],
  );
  assert.equal(
    await fs.readFile(path.join(box.qrDir, 'onboarding.png'), 'utf8'),
    `fake-png:${QR_URL_2}`,
  );
  assert.deepEqual(await listFiles(box.qrDir), ['onboarding.png']);
});

test('multiple or malformed SDK credential payloads fail without writing secrets', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const events = [];
  const qr = createFakeQr();
  const { connector } = createFakeConnector((callbacks) => {
    setImmediate(() =>
      callbacks.onSuccess([
        { appId: 'one', appSecret: SECRET },
        { appId: 'two', appSecret: `${SECRET}-two` },
      ]),
    );
  });

  const result = await runOnboarding(baseOptions(box, connector, qr, events));

  assert.equal(result.status, 'FAILED');
  assert.equal(result.errorCode, 'CONNECTOR_INVALID_CREDENTIALS');
  assert.equal(JSON.stringify(events).includes(SECRET), false);
  assert.deepEqual(await listFiles(box.privateDir), []);
  assert.deepEqual(await listFiles(box.qrDir), []);
});

test('a changed startQrConnect return contract fails closed', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const events = [];
  const qr = createFakeQr();
  const connector = {
    qrConnect() {},
    startQrConnect() {
      return undefined;
    },
  };

  const result = await runOnboarding(baseOptions(box, connector, qr, events));

  assert.equal(result.status, 'FAILED');
  assert.equal(result.errorCode, 'CONNECTOR_START_FAILED');
  assert.deepEqual(await listFiles(box.privateDir), []);
  assert.deepEqual(await listFiles(box.qrDir), []);
});

test('missing public SDK API is rejected before any scan starts', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const events = [];
  const qr = createFakeQr();

  await assert.rejects(
    runOnboarding(baseOptions(box, {}, qr, events)),
    { code: 'CONNECTOR_API_CHANGED' },
  );
  assert.deepEqual(await listFiles(box.privateDir), []);
  assert.deepEqual(await listFiles(box.qrDir), []);
});

test('pre-existing QR target blocks the run before the SDK starts and is preserved', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const events = [];
  const qr = createFakeQr();
  const qrPath = path.join(box.qrDir, 'user-owned.png');
  await fs.writeFile(qrPath, 'user-owned-qr', { mode: 0o600 });
  const { connector, calls } = createFakeConnector((callbacks) => {
    setImmediate(() => callbacks.onQrDisplayed(QR_URL_1));
  });

  await assert.rejects(
    runOnboarding(
      baseOptions(box, connector, qr, events, {
        qrPath,
      }),
    ),
    { code: 'QR_TARGET_EXISTS' },
  );

  assert.equal(calls.callbacks, null);
  assert.equal(await fs.readFile(qrPath, 'utf8'), 'user-owned-qr');
  assert.deepEqual(await listFiles(box.qrDir), ['user-owned.png']);
});

test('timeout cleanup preserves a QR path replaced by another file during the run', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const events = [];
  const qr = createFakeQr();
  const qrPath = path.join(box.qrDir, 'onboarding.png');
  const { connector } = createFakeConnector((callbacks) => {
    setImmediate(() => callbacks.onQrDisplayed(QR_URL_1));
  });

  let replaced = false;
  const options = baseOptions(box, connector, qr, events, {
    qrPath,
    timeoutMs: 80,
  });
  options.onEvent = (event) => {
    events.push(event);
    if (event.status === 'QR_READY' && !replaced) {
      replaced = true;
      setTimeout(async () => {
        await fs.rm(qrPath, { force: true });
        await fs.writeFile(qrPath, 'user-file-after-start', { mode: 0o600 });
      }, 0);
    }
  };

  const result = await runOnboarding(options);

  assert.equal(result.status, 'TIMEOUT');
  assert.equal(replaced, true);
  assert.equal(await fs.readFile(qrPath, 'utf8'), 'user-file-after-start');
  assert.deepEqual(await listFiles(box.qrDir), ['onboarding.png']);
  assert.deepEqual(await listFiles(box.privateDir), []);
});
