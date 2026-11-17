import { ERROR_CODES, OnboardingError, safeErrorCode } from './errors.js';
import { assertConnectorContract } from './connector.js';
import {
  prepareCredentialsDestination,
  validateCredentials,
  writeCredentials,
} from './credentials.js';
import {
  prepareQrDestination,
  removeQrFile,
  renderTerminalQr,
  writeQrPng,
} from './qr.js';

export const DEFAULT_TIMEOUT_MS = 180_000;
export const DEFAULT_SOURCE = 'bma3';

const qrDependencies = {
  renderTerminalQr,
  writeQrPng,
  removeQrFile,
};

function emitSafely(onEvent, event) {
  try {
    onEvent(event);
  } catch {
    // Output callbacks must never turn into a credential-bearing exception.
  }
}

function normalizeSingleCredential(credentials) {
  if (!Array.isArray(credentials) || credentials.length !== 1) {
    throw new OnboardingError(ERROR_CODES.CONNECTOR_INVALID_CREDENTIALS);
  }
  const item = credentials[0];
  if (!item || typeof item !== 'object') {
    throw new OnboardingError(ERROR_CODES.CONNECTOR_INVALID_CREDENTIALS);
  }
  return validateCredentials(item.appId, item.appSecret);
}

/**
 * Run the isolated A onboarding flow.
 *
 * All paths, the fake-able connector, file APIs, command runner, and QR renderer
 * are injectable so tests never touch the real Tencent service. Expected runtime
 * failures are returned as fixed status/errorCode objects; SDK messages are never
 * copied into events, output, or errors.
 */
export async function runOnboarding({
  connector,
  credentialsPath,
  qrPath,
  timeoutMs = DEFAULT_TIMEOUT_MS,
  source = DEFAULT_SOURCE,
  signal,
  onEvent = () => {},
  repoRoot,
  platform = process.platform,
  fsImpl,
  runCommand,
  currentUser,
  qr = qrDependencies,
} = {}) {
  assertConnectorContract(connector);
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0) {
    throw new OnboardingError(ERROR_CODES.INVALID_ARGUMENTS);
  }
  if (typeof source !== 'string' || source.length > 200) {
    throw new OnboardingError(ERROR_CODES.INVALID_ARGUMENTS);
  }
  if (signal && signal.aborted) {
    const result = { status: 'CANCELLED', errorCode: ERROR_CODES.ONBOARDING_CANCELLED };
    emitSafely(onEvent, result);
    return result;
  }

  const credentialDestination = await prepareCredentialsDestination({
    credentialsPath,
    repoRoot,
    platform,
    fsImpl,
    runCommand,
    currentUser,
  });
  const qrDestination = await prepareQrDestination({
    qrPath,
    repoRoot,
    platform,
    fsImpl,
    allowExisting: false,
  });
  const comparableCredentials =
    platform === 'win32'
      ? credentialDestination.targetPath.toLowerCase()
      : credentialDestination.targetPath;
  const comparableQr =
    platform === 'win32' ? qrDestination.targetPath.toLowerCase() : qrDestination.targetPath;
  if (comparableCredentials === comparableQr) {
    throw new OnboardingError(ERROR_CODES.INVALID_ARGUMENTS);
  }
  if (signal && signal.aborted) {
    const result = { status: 'CANCELLED', errorCode: ERROR_CODES.ONBOARDING_CANCELLED };
    emitSafely(onEvent, result);
    return result;
  }

  return new Promise((resolve) => {
    const abortController = new AbortController();
    let accepting = true;
    let closed = false;
    let discardQr = false;
    let qrWritten = false;
    let qrIdentity = null;
    let qrFailure = null;
    let qrQueue = Promise.resolve();
    let stop = () => {};
    let timer = null;

    const removeAbortListener = () => {
      if (signal) {
        signal.removeEventListener('abort', onExternalAbort);
      }
    };

    const finish = (result) => {
      if (closed) return;
      closed = true;
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
      removeAbortListener();
      resolve(result);
    };

    const emit = (event) => emitSafely(onEvent, event);

    const stopSdk = () => {
      try {
        stop();
      } catch {
        // The SDK's stop implementation is not part of the output contract.
      }
      try {
        abortController.abort();
      } catch {
        // AbortController.abort() is synchronous and should not throw.
      }
    };

    async function cleanupQr() {
      if (qrWritten && qrIdentity) {
        await qr.removeQrFile(qrPath, { fsImpl, expectedIdentity: qrIdentity });
      }
      qrWritten = false;
      qrIdentity = null;
    }

    async function fail(errorCode, status) {
      if (!accepting || closed) return;
      accepting = false;
      discardQr = true;
      stopSdk();
      await qrQueue;
      await cleanupQr();
      const result = { status, errorCode };
      emit(result);
      finish(result);
    }

    async function succeed(rawCredentials) {
      if (!accepting || closed) return;
      accepting = false;
      stopSdk();
      await qrQueue;

      if (qrFailure) {
        discardQr = true;
        await cleanupQr();
        const result = {
          status: 'FAILED',
          errorCode: safeErrorCode(qrFailure, ERROR_CODES.QR_WRITE_FAILED),
        };
        emit(result);
        finish(result);
        return;
      }

      let credentials;
      try {
        credentials = normalizeSingleCredential(rawCredentials);
      } catch (error) {
        discardQr = true;
        await cleanupQr();
        const result = { status: 'FAILED', errorCode: safeErrorCode(error) };
        emit(result);
        finish(result);
        return;
      }

      try {
        const written = await writeCredentials({
          appId: credentials.appId,
          appSecret: credentials.appSecret,
          credentialsPath,
          repoRoot,
          platform,
          fsImpl,
          runCommand,
          currentUser,
        });
        const result = {
          status: 'SUCCESS',
          accountId: written.accountId,
          credentialPath: written.credentialPath,
          qrPath,
        };
        emit(result);
        finish(result);
      } catch (error) {
        discardQr = true;
        await cleanupQr();
        const result = {
          status: 'FAILED',
          errorCode: safeErrorCode(error, ERROR_CODES.CREDENTIAL_WRITE_FAILED),
        };
        emit(result);
        finish(result);
      }
    }

    function scheduleQr(url) {
      const operation = qrQueue.then(async () => {
        if (discardQr) return;
        const terminalText = await qr.renderTerminalQr(url);
        if (discardQr) return;
        const output = await qr.writeQrPng({
          url,
          qrPath,
          repoRoot,
          platform,
          fsImpl,
          allowExisting: qrWritten,
          expectedExistingIdentity: qrIdentity,
        });
        qrWritten = true;
        qrIdentity = output.identity || null;
        if (discardQr) {
          await cleanupQr();
          return;
        }
        emit({ type: 'terminal_qr', text: terminalText });
        emit({ status: 'QR_READY', qrPath: output.qrPath });
      });
      qrQueue = operation.catch((error) => {
        if (!qrFailure) qrFailure = error;
      });
      void operation.catch((error) => {
        if (accepting) {
          void fail(safeErrorCode(error, ERROR_CODES.QR_WRITE_FAILED), 'FAILED');
        }
      });
    }

    function onExternalAbort() {
      void fail(ERROR_CODES.ONBOARDING_CANCELLED, 'CANCELLED');
    }

    if (signal) {
      signal.addEventListener('abort', onExternalAbort, { once: true });
    }
    timer = setTimeout(() => {
      void fail(ERROR_CODES.ONBOARDING_TIMEOUT, 'TIMEOUT');
    }, timeoutMs);

    try {
      const returnedStop = connector.startQrConnect(
        {
          onQrDisplayed(url) {
            if (accepting) scheduleQr(url);
          },
          onQrExpired() {
            if (accepting) emit({ status: 'QR_EXPIRED' });
          },
          onSuccess(credentials) {
            void succeed(credentials);
          },
          onFailure() {
            void fail(ERROR_CODES.CONNECTOR_FLOW_FAILED, 'FAILED');
          },
        },
        {
          displayQrCodeToConsole: false,
          signal: abortController.signal,
          source,
        },
      );
      if (typeof returnedStop !== 'function') {
        throw new OnboardingError(ERROR_CODES.CONNECTOR_API_CHANGED);
      }
      stop = returnedStop;
    } catch {
      void fail(ERROR_CODES.CONNECTOR_START_FAILED, 'FAILED');
    }
  });
}
