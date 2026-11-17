import test from 'node:test';
import assert from 'node:assert/strict';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import { PNG } from 'pngjs';
import jsQR from 'jsqr';
import { makeSandbox, listFiles } from './helpers.js';
import { getQrFileIdentity, removeQrFile, renderTerminalQr, writeQrPng } from '../lib/qr.js';

test('renders a terminal QR and a PNG whose contents decode to the same URL', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const url =
    'https://q.qq.com/qqbot/openclaw/connect.html?task_id=fake-task&source=bma3&_wv=2';
  const qrPath = path.join(box.qrDir, 'onboarding.png');

  const terminal = await renderTerminalQr(url);
  assert.equal(typeof terminal, 'string');
  assert.ok(terminal.length > 100);
  assert.ok(terminal.includes('\x1b['));

  const result = await writeQrPng({ url, qrPath, repoRoot: box.repo, platform: 'linux' });
  assert.equal(result.qrPath, qrPath);
  assert.equal((await fs.stat(qrPath)).mode & 0o777, 0o600);

  const png = PNG.sync.read(await fs.readFile(qrPath));
  const decoded = jsQR(new Uint8ClampedArray(png.data), png.width, png.height, {
    inversionAttempts: 'dontInvert',
  });
  assert.ok(decoded);
  assert.equal(decoded.data, url);
  assert.deepEqual(await listFiles(box.qrDir), ['onboarding.png']);
});

test('rejects QR output inside the repo and leaves no temp file', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const qrPath = path.join(box.repo, 'onboarding.png');

  await assert.rejects(
    writeQrPng({
      url: 'https://q.qq.com/example',
      qrPath,
      repoRoot: box.repo,
      platform: 'linux',
    }),
    { code: 'OUTPUT_PATH_INSIDE_REPO' },
  );
  assert.deepEqual(await listFiles(box.repo), []);
});

test('rejects an existing directory at the QR target and leaves no temp file', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const qrPath = path.join(box.qrDir, 'onboarding.png');
  await fs.mkdir(qrPath);

  await assert.rejects(
    writeQrPng({
      url: 'https://q.qq.com/example',
      qrPath,
      repoRoot: box.repo,
      platform: 'linux',
    }),
    { code: 'QR_PATH_INVALID' },
  );
  assert.deepEqual(await listFiles(box.qrDir), ['onboarding.png']);
});


test('never overwrites a pre-existing QR file', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const qrPath = path.join(box.qrDir, 'user-qr.png');
  await fs.writeFile(qrPath, 'user-owned-qr', { mode: 0o600 });

  await assert.rejects(
    writeQrPng({
      url: 'https://q.qq.com/example',
      qrPath,
      repoRoot: box.repo,
      platform: 'linux',
    }),
    { code: 'QR_TARGET_EXISTS' },
  );
  assert.equal(await fs.readFile(qrPath, 'utf8'), 'user-owned-qr');
  assert.deepEqual(await listFiles(box.qrDir), ['user-qr.png']);
});

test('refreshes only the identity returned by this run and refuses a replaced target', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const qrPath = path.join(box.qrDir, 'onboarding.png');
  const first = await writeQrPng({
    url: 'https://q.qq.com/example?task_id=first',
    qrPath,
    repoRoot: box.repo,
    platform: 'linux',
  });
  assert.ok(first.identity);

  const second = await writeQrPng({
    url: 'https://q.qq.com/example?task_id=second',
    qrPath,
    repoRoot: box.repo,
    platform: 'linux',
    allowExisting: true,
    expectedExistingIdentity: first.identity,
  });
  assert.ok(second.identity);

  await fs.unlink(qrPath);
  await fs.writeFile(qrPath, 'replacement-user-file', { mode: 0o600 });

  await assert.rejects(
    writeQrPng({
      url: 'https://q.qq.com/example?task_id=third',
      qrPath,
      repoRoot: box.repo,
      platform: 'linux',
      allowExisting: true,
      expectedExistingIdentity: second.identity,
    }),
    { code: 'QR_TARGET_CHANGED' },
  );
  assert.equal(await fs.readFile(qrPath, 'utf8'), 'replacement-user-file');
  assert.deepEqual(await listFiles(box.qrDir), ['onboarding.png']);
});

test('cleanup deletes only an identity-matched QR file', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const qrPath = path.join(box.qrDir, 'onboarding.png');
  const first = await writeQrPng({
    url: 'https://q.qq.com/example?task_id=first',
    qrPath,
    repoRoot: box.repo,
    platform: 'linux',
  });

  await fs.unlink(qrPath);
  await fs.writeFile(qrPath, 'replacement-user-file', { mode: 0o600 });
  assert.equal(
    await removeQrFile(qrPath, { expectedIdentity: first.identity }),
    false,
  );
  assert.equal(await fs.readFile(qrPath, 'utf8'), 'replacement-user-file');

  const currentIdentity = await getQrFileIdentity(qrPath);
  assert.equal(await removeQrFile(qrPath, { expectedIdentity: currentIdentity }), true);
  assert.deepEqual(await listFiles(box.qrDir), []);
});
