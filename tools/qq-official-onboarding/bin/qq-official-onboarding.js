#!/usr/bin/env node
import { runCli } from '../lib/cli.js';

const abortController = new AbortController();
const cancel = () => abortController.abort();
process.once('SIGINT', cancel);
process.once('SIGTERM', cancel);

function flush(writable) {
  return new Promise((resolve) => {
    try {
      writable.write('', resolve);
    } catch {
      resolve();
    }
  });
}

let exitCode = 1;
try {
  exitCode = await runCli(process.argv.slice(2), { signal: abortController.signal });
} catch {
  process.stderr.write('{"status":"FAILED","errorCode":"ONBOARDING_FAILED"}\n');
  exitCode = 1;
} finally {
  process.removeListener('SIGINT', cancel);
  process.removeListener('SIGTERM', cancel);
}

await Promise.all([flush(process.stdout), flush(process.stderr)]);
process.exit(exitCode);
