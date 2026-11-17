import { execFile } from 'node:child_process';
import { ERROR_CODES, OnboardingError } from './errors.js';

const ACL_VERIFY_SCRIPT = [
  "$ErrorActionPreference='Stop';",
  '$acl = Get-Acl -LiteralPath $env:BMA3_ONBOARDING_ACL_PATH;',
  '$items = @($acl.Access | ForEach-Object {',
  '  [pscustomobject]@{',
  '    identity = $_.IdentityReference.Value;',
  '    inherited = [bool]$_.IsInherited;',
  '    type = [string]$_.AccessControlType;',
  '    rights = [string]$_.FileSystemRights',
  '  }',
  '});',
  '$out = [pscustomobject]@{ owner = $acl.Owner; items = $items };',
  '$out | ConvertTo-Json -Compress -Depth 6',
].join(' ');

export function defaultRunCommand(command, args, { env } = {}) {
  return new Promise((resolve, reject) => {
    execFile(
      command,
      args,
      {
        encoding: 'utf8',
        windowsHide: true,
        maxBuffer: 1024 * 1024,
        env: { ...process.env, ...(env || {}) },
      },
      (error, stdout) => {
        if (error) {
          reject(new OnboardingError(ERROR_CODES.WINDOWS_ACL_COMMAND_FAILED));
          return;
        }
        resolve(String(stdout || ''));
      },
    );
  });
}

function normalizedPrincipal(value) {
  return typeof value === 'string' ? value.trim().toLowerCase() : '';
}

export async function readCurrentWindowsUser(runCommand = defaultRunCommand) {
  let output;
  try {
    output = await runCommand('whoami', []);
  } catch {
    throw new OnboardingError(ERROR_CODES.WINDOWS_ACL_UNVERIFIABLE);
  }
  const user = String(output || '').split(/\r?\n/).map((line) => line.trim()).find(Boolean);
  if (!user) {
    throw new OnboardingError(ERROR_CODES.WINDOWS_ACL_UNVERIFIABLE);
  }
  return user;
}

/** Validate the structured ACL emitted by windows-acl.js. */
export function verifyWindowsAclJson(raw, currentUser) {
  let parsed;
  try {
    parsed = JSON.parse(String(raw || '').replace(/^\uFEFF/, '').trim());
  } catch {
    throw new OnboardingError(ERROR_CODES.WINDOWS_ACL_UNVERIFIABLE);
  }
  const acl = Array.isArray(parsed) ? parsed[0] : parsed;
  if (!acl || typeof acl !== 'object') {
    throw new OnboardingError(ERROR_CODES.WINDOWS_ACL_UNVERIFIABLE);
  }

  const expectedUser = normalizedPrincipal(currentUser);
  if (!expectedUser || normalizedPrincipal(acl.owner) !== expectedUser) {
    throw new OnboardingError(ERROR_CODES.WINDOWS_ACL_UNVERIFIABLE);
  }

  const entries = Array.isArray(acl.items) ? acl.items : acl.items ? [acl.items] : [];
  if (entries.length === 0) {
    throw new OnboardingError(ERROR_CODES.WINDOWS_ACL_UNVERIFIABLE);
  }

  for (const entry of entries) {
    if (!entry || typeof entry !== 'object') {
      throw new OnboardingError(ERROR_CODES.WINDOWS_ACL_UNVERIFIABLE);
    }
    if (entry.inherited !== false) {
      throw new OnboardingError(ERROR_CODES.WINDOWS_ACL_UNVERIFIABLE);
    }
    if (String(entry.type || '').toLowerCase() !== 'allow') {
      throw new OnboardingError(ERROR_CODES.WINDOWS_ACL_UNVERIFIABLE);
    }
    if (normalizedPrincipal(entry.identity) !== expectedUser) {
      throw new OnboardingError(ERROR_CODES.WINDOWS_ACL_UNVERIFIABLE);
    }
    if (typeof entry.rights !== 'string' || entry.rights.trim() === '') {
      throw new OnboardingError(ERROR_CODES.WINDOWS_ACL_UNVERIFIABLE);
    }
  }
  return true;
}

export async function setWindowsCurrentUserOnlyAcl(
  filePath,
  { runCommand = defaultRunCommand, currentUser } = {},
) {
  const user = currentUser || (await readCurrentWindowsUser(runCommand));
  try {
    await runCommand('icacls', [
      filePath,
      '/inheritance:r',
      '/grant:r',
      `${user}:(R,W,D)`,
    ]);
  } catch {
    throw new OnboardingError(ERROR_CODES.WINDOWS_ACL_COMMAND_FAILED);
  }
  return user;
}

export async function verifyWindowsCurrentUserOnlyAcl(
  filePath,
  { runCommand = defaultRunCommand, currentUser } = {},
) {
  const user = currentUser || (await readCurrentWindowsUser(runCommand));
  let output;
  try {
    output = await runCommand(
      'powershell.exe',
      ['-NoProfile', '-NonInteractive', '-Command', ACL_VERIFY_SCRIPT],
      { env: { BMA3_ONBOARDING_ACL_PATH: filePath } },
    );
  } catch {
    throw new OnboardingError(ERROR_CODES.WINDOWS_ACL_UNVERIFIABLE);
  }
  return verifyWindowsAclJson(output, user);
}
