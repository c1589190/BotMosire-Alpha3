import { promises as fs } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { getQrFileIdentity, removeQrFile } from '../lib/qr.js';

export async function makeSandbox() {
  const base = await fs.mkdtemp(path.join(os.tmpdir(), 'bma3-qq-onboarding-test-'));
  const repo = path.join(base, 'repo');
  const privateDir = path.join(base, 'private');
  const qrDir = path.join(base, 'qr');
  await fs.mkdir(repo, { recursive: true, mode: 0o700 });
  await fs.mkdir(privateDir, { recursive: true, mode: 0o700 });
  await fs.mkdir(qrDir, { recursive: true, mode: 0o700 });
  await fs.chmod(base, 0o700);
  await fs.chmod(privateDir, 0o700);
  await fs.chmod(qrDir, 0o700);
  return {
    base,
    repo,
    privateDir,
    qrDir,
    async cleanup() {
      await fs.rm(base, { recursive: true, force: true });
    },
  };
}

export async function listFiles(directory) {
  return (await fs.readdir(directory)).sort();
}

export function createFakeConnector(script) {
  const calls = {
    options: null,
    stopCalled: false,
    callbacks: null,
  };
  const connector = {
    qrConnect() {
      return Promise.reject(new Error('not used in tests'));
    },
    startQrConnect(callbacks, options) {
      calls.callbacks = callbacks;
      calls.options = options;
      script(callbacks, options, calls);
      return () => {
        calls.stopCalled = true;
      };
    },
  };
  return { connector, calls };
}

export function createFakeQr() {
  const rendered = [];
  const written = [];
  const removed = [];
  return {
    rendered,
    written,
    removed,
    deps: {
      async renderTerminalQr(url) {
        rendered.push(url);
        return `TERMINAL_QR_FOR:${url}`;
      },
      async writeQrPng({ url, qrPath }) {
        written.push({ url, qrPath });
        const tempPath = `${qrPath}.test-tmp-${Math.random().toString(16).slice(2)}`;
        await fs.writeFile(tempPath, `fake-png:${url}`, { mode: 0o600 });
        await fs.rename(tempPath, qrPath);
        const identity = await getQrFileIdentity(qrPath);
        return { qrPath, identity };
      },
      async removeQrFile(qrPath, { expectedIdentity } = {}) {
        const removedNow = await removeQrFile(qrPath, { expectedIdentity });
        if (removedNow) removed.push(qrPath);
        return removedNow;
      },
    },
  };
}
