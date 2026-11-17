import { promises as fs } from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { ERROR_CODES, OnboardingError } from './errors.js';

export const EXPECTED_CONNECTOR_VERSION = '1.2.0';
const PACKAGE_NAME = '@tencent-connect/qqbot-connector';
const requireFromHere = createRequire(import.meta.url);

/** Validate only the public contract we depend on; no SDK internals are replicated. */
export function assertConnectorContract(
  connector,
  { expectedVersion = EXPECTED_CONNECTOR_VERSION } = {},
) {
  if (!connector || typeof connector.startQrConnect !== 'function') {
    throw new OnboardingError(ERROR_CODES.CONNECTOR_API_CHANGED);
  }
  if (typeof connector.qrConnect !== 'function') {
    throw new OnboardingError(ERROR_CODES.CONNECTOR_API_CHANGED);
  }
  if (connector.__bma3Version && connector.__bma3Version !== expectedVersion) {
    throw new OnboardingError(ERROR_CODES.CONNECTOR_VERSION_MISMATCH);
  }
  return true;
}

export async function readInstalledConnectorVersion({ fsImpl = fs } = {}) {
  let entry;
  try {
    entry = requireFromHere.resolve(PACKAGE_NAME);
  } catch {
    throw new OnboardingError(ERROR_CODES.CONNECTOR_LOAD_FAILED);
  }

  let current = path.dirname(entry);
  for (let depth = 0; depth < 8; depth += 1) {
    const packageJsonPath = path.join(current, 'package.json');
    try {
      const parsed = JSON.parse(await fsImpl.readFile(packageJsonPath, 'utf8'));
      if (parsed && parsed.name === PACKAGE_NAME && typeof parsed.version === 'string') {
        return parsed.version;
      }
    } catch {
      // Keep walking towards the package root; absence is not an output detail.
    }
    const parent = path.dirname(current);
    if (parent === current) break;
    current = parent;
  }
  throw new OnboardingError(ERROR_CODES.CONNECTOR_LOAD_FAILED);
}

export async function loadOfficialConnector({ fsImpl = fs } = {}) {
  let connector;
  try {
    connector = await import(PACKAGE_NAME);
  } catch {
    throw new OnboardingError(ERROR_CODES.CONNECTOR_LOAD_FAILED);
  }

  assertConnectorContract(connector);
  const version = await readInstalledConnectorVersion({ fsImpl });
  if (version !== EXPECTED_CONNECTOR_VERSION) {
    throw new OnboardingError(ERROR_CODES.CONNECTOR_VERSION_MISMATCH);
  }
  return connector;
}
