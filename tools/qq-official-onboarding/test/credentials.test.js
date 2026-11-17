import test from 'node:test';
import assert from 'node:assert/strict';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import { makeSandbox, listFiles } from './helpers.js';
import { writeCredentials } from '../lib/credentials.js';

const SECRET = 'TEST_ONLY_FAKE_APP_SECRET_DO_NOT_LEAK';

function windowsRunner({ invalid = false } = {}) {
  const calls = [];
  const runCommand = async (command, args, options = {}) => {
    calls.push({ command, args, options });
    if (command === 'whoami') return 'DOMAIN\\alice\r\n';
    if (command === 'icacls') return 'Successfully processed 1 files; Failed processing 0 files\r\n';
    if (command === 'powershell.exe') {
      return JSON.stringify({
        owner: 'DOMAIN\\alice',
        items: invalid
          ? [
              { identity: 'DOMAIN\\alice', inherited: false, type: 'Allow', rights: 'Read, Write, Delete' },
              { identity: 'DOMAIN\\bob', inherited: false, type: 'Allow', rights: 'Read' },
            ]
          : [{ identity: 'DOMAIN\\alice', inherited: false, type: 'Allow', rights: 'Read, Write, Delete' }],
      });
    }
    throw new Error('unexpected Windows command');
  };
  return { runCommand, calls };
}

test('POSIX: writes separate appId/appSecret atomically with mode 0600', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const credentialsPath = path.join(box.privateDir, 'qq-official-credentials.json');

  const result = await writeCredentials({
    appId: 'app-id-123',
    appSecret: SECRET,
    credentialsPath,
    repoRoot: box.repo,
    platform: 'linux',
  });

  assert.deepEqual(result, { accountId: 'app-id-123', credentialPath: credentialsPath });
  const stat = await fs.stat(credentialsPath);
  assert.equal(stat.mode & 0o777, 0o600);
  assert.equal(stat.uid, process.getuid());
  const parsed = JSON.parse(await fs.readFile(credentialsPath, 'utf8'));
  assert.deepEqual(Object.keys(parsed).sort(), ['appId', 'appSecret']);
  assert.equal(parsed.appId, 'app-id-123');
  assert.equal(parsed.appSecret, SECRET);
  assert.deepEqual(await listFiles(box.privateDir), ['qq-official-credentials.json']);
});

test('POSIX: replacing an existing 0600 credential file leaves no temp residue', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const credentialsPath = path.join(box.privateDir, 'qq-official-credentials.json');

  await writeCredentials({
    appId: 'app-id-old',
    appSecret: 'old-fake-secret',
    credentialsPath,
    repoRoot: box.repo,
    platform: 'linux',
  });
  const result = await writeCredentials({
    appId: 'app-id-new',
    appSecret: SECRET,
    credentialsPath,
    repoRoot: box.repo,
    platform: 'linux',
  });

  assert.equal(result.accountId, 'app-id-new');
  assert.equal((await fs.stat(credentialsPath)).mode & 0o777, 0o600);
  const parsed = JSON.parse(await fs.readFile(credentialsPath, 'utf8'));
  assert.equal(parsed.appId, 'app-id-new');
  assert.equal(parsed.appSecret, SECRET);
  assert.deepEqual(await listFiles(box.privateDir), ['qq-official-credentials.json']);
});

test('POSIX: existing broad-mode target is rejected without changing it or leaking data', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const credentialsPath = path.join(box.privateDir, 'qq-official-credentials.json');
  await fs.writeFile(credentialsPath, 'unchanged\n', { mode: 0o644 });
  await fs.chmod(credentialsPath, 0o644);

  await assert.rejects(
    writeCredentials({
      appId: 'app-id',
      appSecret: SECRET,
      credentialsPath,
      repoRoot: box.repo,
      platform: 'linux',
    }),
    (error) => {
      assert.match(error.code, /^(CREDENTIAL_PERMISSIONS_INVALID|CREDENTIAL_EXISTING_FILE_INVALID)$/);
      assert.equal(String(error).includes(SECRET), false);
      return true;
    },
  );
  assert.equal(await fs.readFile(credentialsPath, 'utf8'), 'unchanged\n');
  assert.deepEqual(await listFiles(box.privateDir), ['qq-official-credentials.json']);
});

test('rejects credential paths inside the repository before writing', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const credentialsPath = path.join(box.repo, 'qq-official-credentials.json');

  await assert.rejects(
    writeCredentials({
      appId: 'app-id',
      appSecret: SECRET,
      credentialsPath,
      repoRoot: box.repo,
      platform: 'linux',
    }),
    { code: 'OUTPUT_PATH_INSIDE_REPO' },
  );
  assert.deepEqual(await listFiles(box.repo), []);
});

test('rejects a group/world-writable credential directory', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const looseDir = path.join(box.base, 'loose');
  await fs.mkdir(looseDir, { mode: 0o777 });
  await fs.chmod(looseDir, 0o777);
  const credentialsPath = path.join(looseDir, 'qq-official-credentials.json');

  await assert.rejects(
    writeCredentials({
      appId: 'app-id',
      appSecret: SECRET,
      credentialsPath,
      repoRoot: box.repo,
      platform: 'linux',
    }),
    { code: 'CREDENTIAL_DIRECTORY_PERMISSIONS_INVALID' },
  );
  assert.deepEqual(await listFiles(looseDir), []);
});

test('rejects a symlink credential target', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const real = path.join(box.privateDir, 'real.json');
  const credentialsPath = path.join(box.privateDir, 'qq-official-credentials.json');
  await fs.writeFile(real, 'unchanged', { mode: 0o600 });
  await fs.symlink(real, credentialsPath);

  await assert.rejects(
    writeCredentials({
      appId: 'app-id',
      appSecret: SECRET,
      credentialsPath,
      repoRoot: box.repo,
      platform: 'linux',
    }),
    { code: 'CREDENTIAL_EXISTING_FILE_INVALID' },
  );
  assert.equal(await fs.readFile(real, 'utf8'), 'unchanged');
  assert.deepEqual(await listFiles(box.privateDir), ['qq-official-credentials.json', 'real.json']);
});

test('Windows simulation: applies and verifies current-user-only DACL', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const credentialsPath = path.join(box.privateDir, 'qq-official-credentials.json');
  const { runCommand, calls } = windowsRunner();

  const result = await writeCredentials({
    appId: 'app-id-win',
    appSecret: SECRET,
    credentialsPath,
    repoRoot: box.repo,
    platform: 'win32',
    runCommand,
    currentUser: 'DOMAIN\\alice',
  });

  assert.equal(result.accountId, 'app-id-win');
  assert.equal(calls.some((call) => call.command === 'icacls'), true);
  assert.equal(calls.filter((call) => call.command === 'powershell.exe').length >= 2, true);
  assert.equal((await fs.stat(credentialsPath)).mode & 0o777, 0o600);
});

test('Windows simulation: unverifiable DACL fails closed and cleans temp files', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const credentialsPath = path.join(box.privateDir, 'qq-official-credentials.json');
  const { runCommand } = windowsRunner({ invalid: true });

  await assert.rejects(
    writeCredentials({
      appId: 'app-id-win',
      appSecret: SECRET,
      credentialsPath,
      repoRoot: box.repo,
      platform: 'win32',
      runCommand,
      currentUser: 'DOMAIN\\alice',
    }),
    (error) => {
      assert.equal(error.code, 'WINDOWS_ACL_UNVERIFIABLE');
      assert.equal(String(error).includes(SECRET), false);
      return true;
    },
  );
  assert.deepEqual(await listFiles(box.privateDir), []);
});

test('Windows simulation: ACL command failure fails closed without residue', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const credentialsPath = path.join(box.privateDir, 'qq-official-credentials.json');
  const runCommand = async (command) => {
    if (command === 'icacls') throw new Error(`command failed ${SECRET}`);
    if (command === 'whoami') return 'DOMAIN\\alice\r\n';
    return '';
  };

  await assert.rejects(
    writeCredentials({
      appId: 'app-id-win',
      appSecret: SECRET,
      credentialsPath,
      repoRoot: box.repo,
      platform: 'win32',
      runCommand,
      currentUser: 'DOMAIN\\alice',
    }),
    (error) => {
      assert.equal(error.code, 'WINDOWS_ACL_COMMAND_FAILED');
      assert.equal(String(error).includes(SECRET), false);
      return true;
    },
  );
  assert.deepEqual(await listFiles(box.privateDir), []);
});

test('POSIX: final verification failure restores the previous credential inode', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const credentialsPath = path.join(box.privateDir, 'qq-official-credentials.json');
  const oldSecret = 'old-fake-secret-preserved';
  const newSecret = 'new-fake-secret-must-not-persist';

  await writeCredentials({
    appId: 'app-id-old',
    appSecret: oldSecret,
    credentialsPath,
    repoRoot: box.repo,
    platform: 'linux',
  });

  const fsImpl = Object.create(fs);
  let replacedOnce = false;
  fsImpl.rename = async (from, to) => {
    await fs.rename(from, to);
    if (to === credentialsPath && !replacedOnce) {
      replacedOnce = true;
      await fs.chmod(credentialsPath, 0o644);
    }
  };

  await assert.rejects(
    writeCredentials({
      appId: 'app-id-new',
      appSecret: newSecret,
      credentialsPath,
      repoRoot: box.repo,
      platform: 'linux',
      fsImpl,
    }),
    (error) => {
      assert.equal(error.code, 'CREDENTIAL_FINAL_VERIFY_FAILED');
      assert.equal(String(error).includes(oldSecret), false);
      assert.equal(String(error).includes(newSecret), false);
      return true;
    },
  );

  const restored = JSON.parse(await fs.readFile(credentialsPath, 'utf8'));
  assert.equal(restored.appId, 'app-id-old');
  assert.equal(restored.appSecret, oldSecret);
  assert.equal((await fs.stat(credentialsPath)).mode & 0o777, 0o600);
  assert.deepEqual(await listFiles(box.privateDir), ['qq-official-credentials.json']);
});

test('Windows simulation: final ACL failure rolls back to the old owner-only target', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const credentialsPath = path.join(box.privateDir, 'qq-official-credentials.json');
  const oldSecret = 'old-windows-fake-secret';
  const newSecret = 'new-windows-fake-secret-must-not-persist';

  await writeCredentials({
    appId: 'app-id-old-win',
    appSecret: oldSecret,
    credentialsPath,
    repoRoot: box.repo,
    platform: 'linux',
  });

  const validAcl = JSON.stringify({
    owner: 'DOMAIN\\alice',
    items: [
      { identity: 'DOMAIN\\alice', inherited: false, type: 'Allow', rights: 'Read, Write, Delete' },
    ],
  });
  const invalidAcl = JSON.stringify({
    owner: 'DOMAIN\\alice',
    items: [
      { identity: 'DOMAIN\\alice', inherited: false, type: 'Allow', rights: 'Read, Write, Delete' },
      { identity: 'DOMAIN\\bob', inherited: false, type: 'Allow', rights: 'Read' },
    ],
  });
  const runCommand = async (command, args, options = {}) => {
    if (command === 'whoami') return 'DOMAIN\\alice\r\n';
    if (command === 'icacls') return 'Successfully processed 1 files; Failed processing 0 files\r\n';
    if (command === 'powershell.exe') {
      const inspectedPath = options.env && options.env.BMA3_ONBOARDING_ACL_PATH;
      if (inspectedPath === credentialsPath) {
        const content = await fs.readFile(credentialsPath, 'utf8');
        if (content.includes('app-id-new-win')) return invalidAcl;
      }
      return validAcl;
    }
    throw new Error('unexpected Windows command');
  };

  await assert.rejects(
    writeCredentials({
      appId: 'app-id-new-win',
      appSecret: newSecret,
      credentialsPath,
      repoRoot: box.repo,
      platform: 'win32',
      runCommand,
      currentUser: 'DOMAIN\\alice',
    }),
    (error) => {
      assert.equal(error.code, 'CREDENTIAL_FINAL_VERIFY_FAILED');
      assert.equal(String(error).includes(oldSecret), false);
      assert.equal(String(error).includes(newSecret), false);
      return true;
    },
  );

  const restored = JSON.parse(await fs.readFile(credentialsPath, 'utf8'));
  assert.equal(restored.appId, 'app-id-old-win');
  assert.equal(restored.appSecret, oldSecret);
  assert.equal((await fs.stat(credentialsPath)).mode & 0o777, 0o600);
  assert.deepEqual(await listFiles(box.privateDir), ['qq-official-credentials.json']);
});

test('POSIX: inability to create a rollback backup fails closed without replacing old target', async (t) => {
  const box = await makeSandbox();
  t.after(() => box.cleanup());
  const credentialsPath = path.join(box.privateDir, 'qq-official-credentials.json');
  await writeCredentials({
    appId: 'app-id-old',
    appSecret: 'old-fake-secret',
    credentialsPath,
    repoRoot: box.repo,
    platform: 'linux',
  });

  const fsImpl = Object.create(fs);
  fsImpl.link = async () => {
    throw new Error('hard links unavailable');
  };

  await assert.rejects(
    writeCredentials({
      appId: 'app-id-new',
      appSecret: 'new-fake-secret',
      credentialsPath,
      repoRoot: box.repo,
      platform: 'linux',
      fsImpl,
    }),
    { code: 'CREDENTIAL_BACKUP_FAILED' },
  );

  const preserved = JSON.parse(await fs.readFile(credentialsPath, 'utf8'));
  assert.equal(preserved.appId, 'app-id-old');
  assert.equal(preserved.appSecret, 'old-fake-secret');
  assert.deepEqual(await listFiles(box.privateDir), ['qq-official-credentials.json']);
});
