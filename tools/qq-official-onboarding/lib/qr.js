import { constants as fsConstants, promises as fs } from 'node:fs';
import path from 'node:path';
import { createHash, randomBytes } from 'node:crypto';
import QRCode from 'qrcode';
import { ERROR_CODES, OnboardingError, safeErrorCode } from './errors.js';
import { REPO_ROOT, prepareSafeOutputPath } from './paths.js';

export async function renderTerminalQr(text) {
  return QRCode.toString(text, {
    type: 'terminal',
    small: true,
    errorCorrectionLevel: 'M',
  });
}

export async function renderPngBuffer(text) {
  return QRCode.toBuffer(text, {
    type: 'png',
    errorCorrectionLevel: 'M',
    margin: 2,
    width: 640,
  });
}

function identityOf(stat, sha256 = null) {
  const identity = {
    dev: stat.dev,
    ino: stat.ino,
    birthtimeMs: stat.birthtimeMs,
    ctimeMs: stat.ctimeMs,
    size: stat.size,
  };
  if (sha256) identity.sha256 = sha256;
  return identity;
}

function sameIdentity(left, right) {
  if (!left || !right || typeof left.dev !== 'number' || typeof left.ino !== 'number') {
    return false;
  }
  return Object.keys(left).every((key) => left[key] === right[key]);
}

export async function getQrFileIdentity(qrPath, { fsImpl = fs } = {}) {
  const stat = await fsImpl.lstat(qrPath);
  if (stat.isSymbolicLink() || !stat.isFile()) {
    throw new OnboardingError(ERROR_CODES.QR_PATH_INVALID);
  }
  const sha256 = createHash('sha256').update(await fsImpl.readFile(qrPath)).digest('hex');
  return identityOf(stat, sha256);
}

async function lstatOrNull(filePath, fsImpl) {
  try {
    return await fsImpl.lstat(filePath);
  } catch (error) {
    if (error && error.code === 'ENOENT') return null;
    throw new OnboardingError(ERROR_CODES.QR_PATH_INVALID);
  }
}

async function assertUsableExistingTarget(
  targetPath,
  existing,
  { fsImpl, allowExisting, expectedIdentity },
) {
  if (!existing) return;
  if (existing.isSymbolicLink() || !existing.isFile()) {
    throw new OnboardingError(ERROR_CODES.QR_PATH_INVALID);
  }
  if (!allowExisting || !expectedIdentity) {
    throw new OnboardingError(ERROR_CODES.QR_TARGET_EXISTS);
  }
  const currentIdentity = await getQrFileIdentity(targetPath, { fsImpl });
  if (!sameIdentity(currentIdentity, expectedIdentity)) {
    throw new OnboardingError(ERROR_CODES.QR_TARGET_CHANGED);
  }
}

export async function prepareQrDestination({
  qrPath,
  repoRoot = REPO_ROOT,
  platform = process.platform,
  fsImpl = fs,
  allowExisting = false,
  expectedIdentity = null,
} = {}) {
  if (typeof qrPath !== 'string' || qrPath.trim() === '') {
    throw new OnboardingError(ERROR_CODES.QR_PATH_INVALID);
  }
  const destination = await prepareSafeOutputPath(qrPath, { repoRoot, platform, fsImpl });
  const existing = await lstatOrNull(destination.targetPath, fsImpl);
  await assertUsableExistingTarget(destination.targetPath, existing, {
    fsImpl,
    allowExisting,
    expectedIdentity,
  });
  return {
    ...destination,
    existingIdentity: existing ? await getQrFileIdentity(destination.targetPath, { fsImpl }) : null,
  };
}

async function unlinkIfPresent(filePath, fsImpl) {
  try {
    await fsImpl.unlink(filePath);
  } catch {
    // Best-effort cleanup only.
  }
}

/**
 * Render the QR URL to a PNG using a same-directory temp file and atomic rename.
 * By default an existing target is never overwritten. A refresh inside one run
 * may pass the identity returned by the previous write; a changed target fails.
 */
export async function writeQrPng({
  url,
  qrPath,
  repoRoot = REPO_ROOT,
  platform = process.platform,
  fsImpl = fs,
  allowExisting = false,
  expectedExistingIdentity = null,
} = {}) {
  if (typeof url !== 'string' || url.trim() === '') {
    throw new OnboardingError(ERROR_CODES.QR_PATH_INVALID);
  }
  const destination = await prepareQrDestination({
    qrPath,
    repoRoot,
    platform,
    fsImpl,
    allowExisting,
    expectedIdentity: expectedExistingIdentity,
  });
  const tempPath = path.join(
    destination.parentPath,
    `.${path.basename(destination.targetPath)}.${process.pid}.${randomBytes(12).toString('hex')}.tmp`,
  );

  let handle = null;
  try {
    const png = await renderPngBuffer(url);
    const flags =
      fsConstants.O_WRONLY |
      fsConstants.O_CREAT |
      fsConstants.O_EXCL |
      (fsConstants.O_NOFOLLOW || 0);
    handle = await fsImpl.open(tempPath, flags, 0o600);
    await handle.chmod(0o600);
    await handle.writeFile(png);
    await handle.sync();
    await handle.close();
    handle = null;

    // Re-check immediately before rename, closing the pre-existing-file/race
    // case as far as a portable rename allows.
    const existing = await lstatOrNull(destination.targetPath, fsImpl);
    await assertUsableExistingTarget(destination.targetPath, existing, {
      fsImpl,
      allowExisting,
      expectedIdentity: expectedExistingIdentity,
    });

    const sha256 = createHash('sha256').update(png).digest('hex');
    await fsImpl.rename(tempPath, destination.targetPath);
    const finalStat = await fsImpl.lstat(destination.targetPath);
    if (finalStat.isSymbolicLink() || !finalStat.isFile()) {
      throw new OnboardingError(ERROR_CODES.QR_WRITE_FAILED);
    }
    return { qrPath: destination.targetPath, identity: identityOf(finalStat, sha256) };
  } catch (error) {
    throw new OnboardingError(safeErrorCode(error, ERROR_CODES.QR_WRITE_FAILED));
  } finally {
    if (handle) {
      try {
        await handle.close();
      } catch {
        // ignore cleanup failure
      }
    }
    await unlinkIfPresent(tempPath, fsImpl);
  }
}

/**
 * Remove only a QR file whose inode identity matches the exact file this run
 * created/refreshed. Without an identity nothing is deleted.
 */
export async function removeQrFile(qrPath, { fsImpl = fs, expectedIdentity = null } = {}) {
  if (typeof qrPath !== 'string' || qrPath === '' || !expectedIdentity) return false;
  let stat;
  try {
    stat = await fsImpl.lstat(qrPath);
  } catch {
    return false;
  }
  if (stat.isSymbolicLink() || !stat.isFile()) return false;
  try {
    const sha256 = createHash('sha256').update(await fsImpl.readFile(qrPath)).digest('hex');
    if (!sameIdentity(identityOf(stat, sha256), expectedIdentity)) return false;
    await fsImpl.unlink(qrPath);
    return true;
  } catch {
    return false;
  }
}
