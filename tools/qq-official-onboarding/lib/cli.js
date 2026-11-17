import path from 'node:path';
import { randomBytes } from 'node:crypto';
import { ERROR_CODES, OnboardingError, safeErrorCode } from './errors.js';
import { loadOfficialConnector } from './connector.js';
import { DEFAULT_SOURCE, DEFAULT_TIMEOUT_MS, runOnboarding } from './onboarding.js';

export const USAGE = `Usage:
  qq-official-onboarding --credentials <absolute-outside-repo-path> [options]

Required:
  --credentials <path>   Write {appId, appSecret} to this owner-only file.
                         The path must be absolute and outside this repository.

Options:
  --qr-out <path>        PNG output path (absolute, outside repo). Existing files
                         are rejected. Defaults to a fresh unique file beside the
                         credentials file.
  --timeout-ms <n>       Overall scan timeout in milliseconds (default ${DEFAULT_TIMEOUT_MS}).
  --source <text>        Source label passed to the official connector (default ${DEFAULT_SOURCE}).
  --no-terminal-qr       Do not write the terminal QR to stdout.
  --help                 Show this help.

The CLI prints only status/account ID/path JSON lines (plus the requested terminal QR).
It never prints the AppSecret, SDK error text, authorization values, or tokens.`;

function takeValue(argv, index, option) {
  const argument = argv[index];
  const prefix = `${option}=`;
  if (argument.startsWith(prefix)) {
    return { value: argument.slice(prefix.length), next: index };
  }
  if (index + 1 >= argv.length || argv[index + 1].startsWith('--')) {
    throw new OnboardingError(ERROR_CODES.INVALID_ARGUMENTS);
  }
  return { value: argv[index + 1], next: index + 1 };
}

export function parseArgs(argv) {
  const options = {
    credentialsPath: '',
    qrPath: '',
    timeoutMs: DEFAULT_TIMEOUT_MS,
    source: DEFAULT_SOURCE,
    terminalQr: true,
    help: false,
  };

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--help' || argument === '-h') {
      options.help = true;
      continue;
    }
    if (argument === '--no-terminal-qr') {
      options.terminalQr = false;
      continue;
    }
    if (argument === '--credentials' || argument.startsWith('--credentials=')) {
      const taken = takeValue(argv, index, '--credentials');
      options.credentialsPath = taken.value;
      index = taken.next;
      continue;
    }
    if (argument === '--qr-out' || argument.startsWith('--qr-out=')) {
      const taken = takeValue(argv, index, '--qr-out');
      options.qrPath = taken.value;
      index = taken.next;
      continue;
    }
    if (argument === '--timeout-ms' || argument.startsWith('--timeout-ms=')) {
      const taken = takeValue(argv, index, '--timeout-ms');
      options.timeoutMs = Number(taken.value);
      index = taken.next;
      continue;
    }
    if (argument === '--source' || argument.startsWith('--source=')) {
      const taken = takeValue(argv, index, '--source');
      options.source = taken.value;
      index = taken.next;
      continue;
    }
    throw new OnboardingError(ERROR_CODES.INVALID_ARGUMENTS);
  }

  if (options.help) return options;
  if (!options.credentialsPath || !path.isAbsolute(options.credentialsPath)) {
    throw new OnboardingError(ERROR_CODES.OUTPUT_PATH_NOT_ABSOLUTE);
  }
  if (!Number.isSafeInteger(options.timeoutMs) || options.timeoutMs <= 0) {
    throw new OnboardingError(ERROR_CODES.INVALID_ARGUMENTS);
  }
  if (options.qrPath && !path.isAbsolute(options.qrPath)) {
    throw new OnboardingError(ERROR_CODES.OUTPUT_PATH_NOT_ABSOLUTE);
  }
  if (!options.qrPath) {
    options.qrPath = path.join(
      path.dirname(options.credentialsPath),
      `qq-official-onboarding-${Date.now()}-${randomBytes(4).toString('hex')}.png`,
    );
  }
  return options;
}

function defaultIo() {
  return {
    stdout: (text) => process.stdout.write(text),
    stderr: (text) => process.stderr.write(text),
  };
}

function publicStatus(event) {
  const output = {};
  if (typeof event.status === 'string') output.status = event.status;
  if (typeof event.accountId === 'string') output.accountId = event.accountId;
  if (typeof event.credentialPath === 'string') output.credentialPath = event.credentialPath;
  if (typeof event.qrPath === 'string') output.qrPath = event.qrPath;
  if (typeof event.errorCode === 'string') output.errorCode = event.errorCode;
  return output;
}

function writeJsonLine(writer, event) {
  writer(`${JSON.stringify(publicStatus(event))}\n`);
}

export async function runCli(argv, dependencies = {}) {
  const io = dependencies.io || defaultIo();
  let options;
  try {
    options = parseArgs(argv);
  } catch (error) {
    writeJsonLine(io.stderr, {
      status: 'FAILED',
      errorCode: safeErrorCode(error, ERROR_CODES.INVALID_ARGUMENTS),
    });
    return 2;
  }

  if (options.help) {
    io.stdout(`${USAGE}\n`);
    return 0;
  }

  let connector;
  try {
    connector = dependencies.connector || (await loadOfficialConnector());
  } catch (error) {
    writeJsonLine(io.stderr, {
      status: 'FAILED',
      errorCode: safeErrorCode(error, ERROR_CODES.CONNECTOR_LOAD_FAILED),
    });
    return 1;
  }

  const onEvent = (event) => {
    if (event && event.type === 'terminal_qr') {
      if (options.terminalQr && typeof event.text === 'string') {
        io.stdout(event.text.endsWith('\n') ? event.text : `${event.text}\n`);
      }
      return;
    }
    if (event && typeof event.status === 'string') {
      writeJsonLine(io.stdout, event);
    }
  };

  let result;
  try {
    const run = dependencies.runOnboarding || runOnboarding;
    result = await run({
      ...(dependencies.onboardingDependencies || {}),
      connector,
      credentialsPath: options.credentialsPath,
      qrPath: options.qrPath,
      timeoutMs: options.timeoutMs,
      source: options.source,
      signal: dependencies.signal,
      onEvent,
    });
  } catch (error) {
    result = {
      status: 'FAILED',
      errorCode: safeErrorCode(error, ERROR_CODES.ONBOARDING_FAILED),
    };
    writeJsonLine(io.stdout, result);
  }

  if (result.status === 'SUCCESS') return 0;
  if (result.status === 'TIMEOUT') return 124;
  if (result.status === 'CANCELLED') return 130;
  return 1;
}
