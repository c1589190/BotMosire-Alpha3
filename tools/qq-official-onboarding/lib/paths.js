import { promises as fs } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ERROR_CODES, OnboardingError } from './errors.js';

/** Repository root, derived from this file's location, never from caller input. */
export const REPO_ROOT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../..',
);

function comparablePath(value, platform) {
  const resolved = path.resolve(value);
  return platform === 'win32' ? resolved.toLowerCase() : resolved;
}

/** True when candidate is parent itself or a descendant of parent. */
export function isWithin(parent, candidate, platform = process.platform) {
  const from = comparablePath(parent, platform);
  const to = comparablePath(candidate, platform);
  const relative = path.relative(from, to);
  return (
    relative === '' ||
    (relative !== '..' &&
      !relative.startsWith(`..${path.sep}`) &&
      !path.isAbsolute(relative))
  );
}

/** Lexical check before any directory is created. */
export function assertLexicallyOutsideRepo(
  targetPath,
  { repoRoot = REPO_ROOT, platform = process.platform } = {},
) {
  if (typeof targetPath !== 'string' || !path.isAbsolute(targetPath)) {
    throw new OnboardingError(ERROR_CODES.OUTPUT_PATH_NOT_ABSOLUTE);
  }
  if (isWithin(repoRoot, targetPath, platform)) {
    throw new OnboardingError(ERROR_CODES.OUTPUT_PATH_INSIDE_REPO);
  }
}

/**
 * Resolve a caller-supplied output path after checking both the lexical path and
 * the real path of its parent. The parent is created with owner-only mode when
 * absent. This prevents a symlinked parent from redirecting output into the repo.
 */
export async function prepareSafeOutputPath(
  targetPath,
  { repoRoot = REPO_ROOT, platform = process.platform, fsImpl = fs, parentMode = 0o700 } = {},
) {
  assertLexicallyOutsideRepo(targetPath, { repoRoot, platform });

  const parent = path.dirname(targetPath);
  try {
    await fsImpl.mkdir(parent, { recursive: true, mode: parentMode });
  } catch {
    throw new OnboardingError(ERROR_CODES.OUTPUT_PARENT_MISSING);
  }

  let parentReal;
  let repoReal;
  try {
    parentReal = await fsImpl.realpath(parent);
    repoReal = await fsImpl.realpath(repoRoot);
  } catch {
    throw new OnboardingError(ERROR_CODES.REPO_ROOT_UNAVAILABLE);
  }

  let parentStat;
  try {
    parentStat = await fsImpl.stat(parentReal);
  } catch {
    throw new OnboardingError(ERROR_CODES.OUTPUT_PARENT_MISSING);
  }
  if (!parentStat.isDirectory()) {
    throw new OnboardingError(ERROR_CODES.OUTPUT_PARENT_NOT_DIRECTORY);
  }

  const resolvedTarget = path.resolve(parentReal, path.basename(targetPath));
  if (isWithin(repoReal, resolvedTarget, platform)) {
    throw new OnboardingError(ERROR_CODES.OUTPUT_PATH_INSIDE_REPO);
  }
  return { targetPath: resolvedTarget, parentPath: parentReal, parentStat };
}
