import test from 'node:test';
import assert from 'node:assert/strict';
import { promises as fs } from 'node:fs';
import {
  EXPECTED_CONNECTOR_VERSION,
  assertConnectorContract,
  loadOfficialConnector,
  readInstalledConnectorVersion,
} from '../lib/connector.js';

test('locked official connector is installed and exposes the expected public contract', async () => {
  const version = await readInstalledConnectorVersion();
  assert.equal(version, EXPECTED_CONNECTOR_VERSION);
  assert.equal(version, '1.2.0');

  const connector = await loadOfficialConnector();
  assert.equal(typeof connector.startQrConnect, 'function');
  assert.equal(typeof connector.qrConnect, 'function');
});

test('connector contract fails closed when the public API or version changes', () => {
  assert.throws(() => assertConnectorContract({}), { code: 'CONNECTOR_API_CHANGED' });
  assert.throws(
    () => assertConnectorContract({ qrConnect() {}, startQrConnect() {}, __bma3Version: '9.9.9' }),
    { code: 'CONNECTOR_VERSION_MISMATCH' },
  );
});

test('package metadata locks exact production dependencies and Node >= 18', async () => {
  const packageJson = JSON.parse(await fs.readFile(new URL('../package.json', import.meta.url), 'utf8'));
  const lock = JSON.parse(await fs.readFile(new URL('../package-lock.json', import.meta.url), 'utf8'));

  assert.equal(packageJson.engines.node, '>=18.0.0');
  assert.equal(packageJson.dependencies['@tencent-connect/qqbot-connector'], '1.2.0');
  assert.equal(packageJson.dependencies.qrcode, '1.5.4');
  assert.equal(lock.packages[''].dependencies['@tencent-connect/qqbot-connector'], '1.2.0');
  assert.equal(lock.packages['node_modules/@tencent-connect/qqbot-connector'].version, '1.2.0');
  assert.equal(lock.packages['node_modules/qrcode'].version, '1.5.4');
  assert.match(
    lock.packages['node_modules/@tencent-connect/qqbot-connector'].integrity,
    /^sha512-/,
  );
});
