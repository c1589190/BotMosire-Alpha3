import { constants as fsConstants, promises as fs } from 'node:fs';
import path from 'node:path';
import { randomBytes } from 'node:crypto';
import { ERROR_CODES, OnboardingError, safeErrorCode } from './errors.js';
import { REPO_ROOT, prepareSafeOutputPath } from './paths.js';
import {
  defaultRunCommand,
  readCurrentWindowsUser,
  setWindowsCurrentUserOnlyAcl,
  verifyWindowsCurrentUserOnlyAcl,
} from './windows-acl.js';

const POSIX_PLATFORMS = new Set([
  'aix',
  'darwin',
  'freebsd',
  'linux',
  'netbsd',
  'openbsd',
  'sunos',
]);

export function validateCredentials(appId, appSecret) {
  if (typeof appId !== 'string' || appId.trim() === '') {
    throw new OnboardingError(ERROR_CODES.CREDENTIAL_INPUT_INVALID);
  }
  if (typeof appSecret !== 'string' || appSecret.trim() === '') {
    throw new OnboardingError(ERROR_CODES.CREDENTIAL_INPUT_INVALID);
  }
  return { appId, appSecret };
}

function currentUid() {
  if (typeof process.getuid !== 'function') {
    throw new OnboardingError(ERROR_CODES.UNSUPPORTED_PLATFORM);
  }
  return process.getuid();
}

/** Verify a file is a regular non-symlink owned only by the current principal. */
export async function verifyOwnerOnlyFile(
  filePath,
  { platform = process.platform, fsImpl = fs, runCommand, currentUser } = {},
) {
  let stat;
  try {
    stat = await fsImpl.lstat(filePath);
  } catch {
    throw new OnboardingError(ERROR_CODES.CREDENTIAL_PERMISSIONS_INVALID);
  }
  if (stat.isSymbolicLink() || !stat.isFile()) {
    throw new OnboardingError(ERROR_CODES.CREDENTIAL_PERMISSIONS_INVALID);
  }

  if (platform === 'win32') {
    await verifyWindowsCurrentUserOnlyAcl(filePath, { runCommand, currentUser });
    return true;
  }
  if (!POSIX_PLATFORMS.has(platform)) {
    throw new OnboardingError(ERROR_CODES.UNSUPPORTED_PLATFORM);
  }

  const uid = currentUid();
  if (typeof stat.uid !== 'number' || stat.uid !== uid || (stat.mode & 0o777) !== 0o600) {
    throw new OnboardingError(ERROR_CODES.CREDENTIAL_PERMISSIONS_INVALID);
  }
  return true;
}

async function lstatOrNull(filePath, fsImpl) {
  try {
    return await fsImpl.lstat(filePath);
  } catch (error) {
    if (error && error.code === 'ENOENT') {
      return null;
    }
    throw new OnboardingError(ERROR_CODES.CREDENTIAL_EXISTING_FILE_INVALID);
  }
}

/**
 * Validate the destination directory and containment rules before a scan starts.
 * Existing credential targets are also checked; they must already be owner-only.
 */
export async function prepareCredentialsDestination({
  credentialsPath,
  repoRoot = REPO_ROOT,
  platform = process.platform,
  fsImpl = fs,
  runCommand = defaultRunCommand,
  currentUser,
} = {}) {
  const destination = await prepareSafeOutputPath(credentialsPath, {
    repoRoot,
    platform,
    fsImpl,
  });

  if (POSIX_PLATFORMS.has(platform)) {
    const uid = currentUid();
    if (
      destination.parentStat.uid !== uid ||
      (destination.parentStat.mode & 0o022) !== 0
    ) {
      throw new OnboardingError(ERROR_CODES.CREDENTIAL_DIRECTORY_PERMISSIONS_INVALID);
    }
  }

  const existing = await lstatOrNull(destination.targetPath, fsImpl);
  if (existing) {
    if (existing.isSymbolicLink() || !existing.isFile()) {
      throw new OnboardingError(ERROR_CODES.CREDENTIAL_EXISTING_FILE_INVALID);
    }
    try {
      await verifyOwnerOnlyFile(destination.targetPath, {
        platform,
        fsImpl,
        runCommand,
        currentUser,
      });
    } catch (error) {
      throw new OnboardingError(
        error && error.code === ERROR_CODES.WINDOWS_ACL_UNVERIFIABLE
          ? ERROR_CODES.CREDENTIAL_PERMISSIONS_INVALID
          : safeErrorCode(error, ERROR_CODES.CREDENTIAL_PERMISSIONS_INVALID),
      );
    }
  }

  return destination;
}

async function safeUnlink(filePath, fsImpl) {
  try {
    await fsImpl.unlink(filePath);
  } catch {
    // Best-effort cleanup only. Callers never include fs error text in output.
  }
}

/**
 * Atomically write {appId, appSecret} to an owner-only file.
 *
 * The plaintext exists only in this process and in owner-only files. If an
 * existing credential file is replaced, a same-directory hard-link backup is
 * created first. The new target is verified after rename; any post-rename
 * failure restores the old inode rather than unlinking the only remaining copy.
 */
export async function writeCredentials({
  appId,
  appSecret,
  credentialsPath,
  repoRoot = REPO_ROOT,
  platform = process.platform,
  fsImpl = fs,
  runCommand = defaultRunCommand,
  currentUser,
} = {}) {
  const validated = validateCredentials(appId, appSecret);
  const destination = await prepareCredentialsDestination({
    credentialsPath,
    repoRoot,
    platform,
    fsImpl,
    runCommand,
    currentUser,
  });

  const resolvedUser =
    platform === 'win32'
      ? currentUser || (await readCurrentWindowsUser(runCommand))
      : currentUser;
  const suffix = `${process.pid}.${randomBytes(12).toString('hex')}`;
  const tempPath = path.join(
    destination.parentPath,
    `.${path.basename(destination.targetPath)}.${suffix}.tmp`,
  );
  const backupPath = path.join(
    destination.parentPath,
    `.${path.basename(destination.targetPath)}.${suffix}.rollback`,
  );

  const verify = (filePath) =>
    verifyOwnerOnlyFile(filePath, {
      platform,
      fsImpl,
      runCommand,
      currentUser: resolvedUser,
    });

  let handle = null;
  let renamed = false;
  let backupCreated = false;
  let preserveBackup = false;

  async function restorePreviousTarget() {
    if (!backupCreated) {
      try {
        await fsImpl.unlink(destination.targetPath);
      } catch {
        throw new OnboardingError(ERROR_CODES.CREDENTIAL_ROLLBACK_FAILED);
      }
      return;
    }
    try {
      // rename(2) is atomic and replaces the new target with the old inode.
      await fsImpl.rename(backupPath, destination.targetPath);
      backupCreated = false;
      await verify(destination.targetPath);
    } catch {
      // Keep the owner-only backup in place for manual recovery. Removing it
      // would destroy the last copy of the previous credentials.
      preserveBackup = true;
      throw new OnboardingError(ERROR_CODES.CREDENTIAL_ROLLBACK_FAILED);
    }
  }

  try {
    const flags =
      fsConstants.O_WRONLY |
      fsConstants.O_CREAT |
      fsConstants.O_EXCL |
      (fsConstants.O_NOFOLLOW || 0);
    handle = await fsImpl.open(tempPath, flags, 0o600);
    await handle.chmod(0o600);
    if (platform === 'win32') {
      await setWindowsCurrentUserOnlyAcl(tempPath, { runCommand, currentUser: resolvedUser });
    }
    await verify(tempPath);

    const payload = Buffer.from(
      `${JSON.stringify({ appId: validated.appId, appSecret: validated.appSecret }, null, 2)}\n`,
      'utf8',
    );
    await handle.writeFile(payload);
    await handle.sync();
    await handle.close();
    handle = null;

    await verify(tempPath);

    // Re-check a target that may have appeared while the temp file was written.
    const existing = await lstatOrNull(destination.targetPath, fsImpl);
    if (existing) {
      if (existing.isSymbolicLink() || !existing.isFile()) {
        throw new OnboardingError(ERROR_CODES.CREDENTIAL_EXISTING_FILE_INVALID);
      }
      await verify(destination.targetPath);

      // Hard-link rather than copy: ACL/mode/content of the old inode remain
      // exact and no second plaintext payload is created.
      try {
        await fsImpl.link(destination.targetPath, backupPath);
      } catch {
        throw new OnboardingError(ERROR_CODES.CREDENTIAL_BACKUP_FAILED);
      }
      backupCreated = true;
      await verify(backupPath);

      const backupStat = await fsImpl.lstat(backupPath);
      const currentStat = await fsImpl.lstat(destination.targetPath);
      if (
        backupStat.dev !== currentStat.dev ||
        backupStat.ino !== currentStat.ino
      ) {
        throw new OnboardingError(ERROR_CODES.CREDENTIAL_TARGET_CHANGED);
      }
    }

    await fsImpl.rename(tempPath, destination.targetPath);
    renamed = true;

    try {
      await verify(destination.targetPath);
    } catch {
      throw new OnboardingError(ERROR_CODES.CREDENTIAL_FINAL_VERIFY_FAILED);
    }

    if (backupCreated) {
      try {
        await fsImpl.unlink(backupPath);
        backupCreated = false;
      } catch {
        throw new OnboardingError(ERROR_CODES.CREDENTIAL_BACKUP_CLEANUP_FAILED);
      }
    }

    return {
      accountId: validated.appId,
      credentialPath: destination.targetPath,
    };
  } catch (error) {
    if (renamed) {
      await restorePreviousTarget();
    }
    throw new OnboardingError(safeErrorCode(error, ERROR_CODES.CREDENTIAL_WRITE_FAILED));
  } finally {
    if (handle) {
      try {
        await handle.close();
      } catch {
        // ignore cleanup failure
      }
    }
    await safeUnlink(tempPath, fsImpl);
    if (backupCreated && !preserveBackup) {
      await safeUnlink(backupPath, fsImpl);
    }
  }
}
