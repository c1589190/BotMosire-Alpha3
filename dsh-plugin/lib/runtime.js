import { execFile, spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { constants } from 'node:fs'
import { lstat, mkdir, open, writeFile } from 'node:fs/promises'
import { isAbsolute, join, resolve } from 'node:path'
import { createInterface } from 'node:readline'
import { setTimeout as delay } from 'node:timers/promises'
import { promisify } from 'node:util'
import { apply as applyInbox } from './inbox.js'
import { mountMcp } from './mcp.js'
import { installedImage, installImage, readJson, RuntimeError, writeJson } from './runtime-image.js'

const START_TIMEOUT = 20000
const STOP_TIMEOUT = 5000
const READY = 'BMA3_MANAGED_READY '
const OCCUPIED = 'BMA3_MANAGED_ERROR INSTANCE_IN_USE'
const windows = process.platform === 'win32'
const run = promisify(execFile)

function requireAbsolute(value) {
  if (typeof value !== 'string' || !isAbsolute(value)) throw new RuntimeError('INVALID_RUNTIME_PATH')
  return resolve(value)
}

export function runtimeRoot(profile, config) {
  if (config.rootDir !== undefined) return requireAbsolute(config.rootDir)
  if (!profile || !/^[A-Za-z0-9_-][A-Za-z0-9_.-]*$/.test(profile.name ?? '')) {
    throw new RuntimeError('PROFILE_REQUIRED')
  }
  return join(requireAbsolute(profile.home), 'bma3', 'instances', profile.name)
}

async function prepareBundledConfig(root) {
  const directory = join(root, 'config')
  await mkdir(directory, { recursive: true, mode: 0o700 })
  const config = [
    'dataDir: ' + JSON.stringify(join(root, 'data')),
    'server:', '  host: 127.0.0.1', '  port: 0', '  healthPort: 0',
    'qqconnect:', '  accounts: []',
    'inbox:', '  inboxId: bma3', '  httpPort: 0',
    'debug:', '  enabled: false',
    'logging:', '  dir: ' + JSON.stringify(join(root, 'logs')), '',
  ].join('\n')
  try {
    await writeFile(join(directory, 'app.yaml'), config, { flag: 'wx', mode: 0o600 })
  } catch (error) {
    if (error.code !== 'EEXIST') throw error
  }
}

export function managedEndpoints(info) {
  const port = (value) => Number.isInteger(value) && value >= 1 && value <= 65535
  if (info.protocol !== 1 || typeof info.version !== 'string' || !info.version
      || ![info.mcpPort, info.inboxPort, info.healthPort].every(port)
      || typeof info.mcpPath !== 'string' || !info.mcpPath.startsWith('/')
      || typeof info.inboxId !== 'string' || !info.inboxId) {
    throw new RuntimeError('INVALID_MANAGED_HANDSHAKE')
  }
  const basePath = info.mcpPath.replace(/\/+$/, '')
  const hasDaily = info.mcpDailyPath !== undefined
  const hasOps = info.mcpOpsPath !== undefined
  if ((hasDaily && info.mcpDailyPath !== basePath + '/daily')
      || (hasOps && (!hasDaily || info.mcpOpsPath !== basePath + '/ops'))) {
    throw new RuntimeError('INVALID_MANAGED_HANDSHAKE')
  }
  const loopback = (number) => 'http://127.0.0.1:' + number
  const mcpOrigin = loopback(info.mcpPort)
  return {
    version: info.version,
    mcpUrl: mcpOrigin + (info.mcpDailyPath ?? info.mcpPath),
    mcpSurface: hasDaily ? 'daily' : 'full',
    ...(hasOps ? { mcpOpsUrl: mcpOrigin + info.mcpOpsPath } : {}),
    inboxUrl: loopback(info.inboxPort),
    healthUrl: loopback(info.healthPort) + '/healthz',
    inboxId: info.inboxId,
    envFile: requireAbsolute(info.envFile),
  }
}

function launch(executable, args, cwd) {
  const child = spawn(executable, args, { cwd, stdio: ['pipe', 'pipe', 'ignore'], windowsHide: true })
  child.stdin.on('error', () => {})
  let finished = false
  const exited = new Promise((resolve) => {
    const done = () => {
      finished = true
      resolve()
    }
    child.once('error', done)
    child.once('close', done)
  })
  const ready = new Promise((resolve, reject) => {
    const output = createInterface({ input: child.stdout })
    output.on('line', (line) => {
      if (line === OCCUPIED) reject(new RuntimeError('INSTANCE_IN_USE'))
      if (!line.startsWith(READY)) return
      try {
        resolve(managedEndpoints(JSON.parse(line.slice(READY.length))))
      } catch {
        reject(new RuntimeError('INVALID_MANAGED_HANDSHAKE'))
      }
    })
    void exited.then(() => reject(new RuntimeError('CHILD_EXITED')))
  })
  return {
    ready,
    exited,
    get running() { return !finished },
    async stop() {
      if (finished) return
      child.stdin.end()
      const stopped = await Promise.race([exited.then(() => true), delay(STOP_TIMEOUT, false, { ref: false })])
      if (stopped) return
      child.kill('SIGTERM')
      const signaled = await Promise.race([exited.then(() => true), delay(STOP_TIMEOUT, false, { ref: false })])
      if (!signaled) {
        child.kill('SIGKILL')
        await exited
      }
    },
  }
}

async function safeWindowsCredential(path) {
  const stat = await lstat(path)
  if (!stat.isFile()) throw new RuntimeError('CREDENTIAL_FILE_UNSAFE')
  const powershell = join(process.env.SystemRoot, 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe')
  const check = `
    $ErrorActionPreference = 'Stop'
    $path = $env:BMA3_MANAGED_ENV_PATH
    if ([IO.File]::GetAttributes($path) -band [IO.FileAttributes]::ReparsePoint) { exit 1 }
    $acl = [IO.File]::GetAccessControl($path)
    $sidType = [Security.Principal.SecurityIdentifier]
    $user = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $entries = @($acl.GetAccessRules($true, $true, $sidType))
    if ($acl.GetOwner($sidType) -ne $user -or $entries.Count -ne 1 -or
        $entries[0].IdentityReference -ne $user -or $entries[0].AccessControlType -ne 'Allow') { exit 1 }
  `
  try {
    await run(powershell, ['-NoLogo', '-NoProfile', '-NonInteractive', '-Command', check], {
      env: { ...process.env, BMA3_MANAGED_ENV_PATH: path }, windowsHide: true, timeout: 5000,
    })
  } catch {
    throw new RuntimeError('CREDENTIAL_FILE_UNSAFE')
  }
  return stat
}

async function credentials(envFile) {
  const checked = windows ? await safeWindowsCredential(envFile) : null
  const flags = windows ? constants.O_RDONLY : constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK
  const file = await open(envFile, flags)
  try {
    const stat = await file.stat()
    const safe = checked ? stat.dev === checked.dev && stat.ino === checked.ino
      : (stat.mode & 0o777) === 0o600 && stat.uid === process.getuid()
    if (!stat.isFile() || !safe || stat.size > 16384) {
      throw new RuntimeError('CREDENTIAL_FILE_UNSAFE')
    }
    const content = await file.readFile('utf8')
    const token = (key) => content.split(/\r?\n/).find((line) => line.startsWith(key + '='))?.slice(key.length + 1)
    const mcp = token('MCP_TOKEN')
    const inbox = token('INBOX_TOKEN')
    if (!mcp || !inbox || !/^[!-~]+$/.test(mcp) || !/^[!-~]+$/.test(inbox)) {
      throw new RuntimeError('CREDENTIALS_MISSING')
    }
    return { mcp, inbox }
  } finally {
    await file.close()
  }
}

async function within(operation, timeout, code) {
  let timer
  try {
    return await Promise.race([operation, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new RuntimeError(code)), timeout)
    })])
  } finally {
    clearTimeout(timer)
  }
}

async function connect(ctx, config, root, configRoot, endpoint, backend) {
  const secret = await credentials(endpoint.envFile)
  const health = await fetch(endpoint.healthUrl, { redirect: 'error', signal: AbortSignal.timeout(3000) })
  await health.body?.cancel()
  if (!health.ok) throw new RuntimeError('HEALTH_UNAVAILABLE')
  const fingerprint = createHash('sha256').update(JSON.stringify([configRoot, endpoint.envFile])).digest('hex')
  if (backend) {
    return backend.activate({ ...endpoint, inboxFingerprint: fingerprint, mcpToken: secret.mcp })
  }
  const panel = await fetch(endpoint.inboxUrl + '/v1/inboxes/' + encodeURIComponent(endpoint.inboxId) + '/panel', {
    headers: { Authorization: 'Bearer ' + secret.inbox },
    redirect: 'error',
    signal: AbortSignal.timeout(3000),
  })
  if (!panel.ok || !(await panel.json()).inboxIdentity) throw new RuntimeError('INBOX_UNAVAILABLE')

  const mcp = mountMcp(ctx, endpoint.mcpUrl, secret.mcp)
  let inboxFiber
  try {
    await within(mcp.ready, 7000, 'MCP_UNAVAILABLE')
    let inboxMounted = false
    const mounted = new Promise((resolve) => {
      inboxFiber = ctx.plugin({
        name: 'bma3-managed-inbox',
        apply(scope) {
          const client = applyInbox(scope, {
            baseUrl: endpoint.inboxUrl,
            inboxId: endpoint.inboxId,
            envFile: endpoint.envFile,
            stateFile: join(root, 'inbox.json'),
            heartbeatMs: config.heartbeatMs,
          }, fingerprint)
          const status = client.status()
          inboxMounted = !status.lockError && !status.stateError && status.persistence?.enabled !== false
          resolve()
        },
      })
    })
    await within(mounted, 3000, 'INBOX_UNAVAILABLE')
    if (!inboxMounted) throw new RuntimeError('INBOX_STATE_UNAVAILABLE')
    return async () => { await Promise.allSettled([inboxFiber.dispose(), mcp.dispose()]) }
  } catch (error) {
    await Promise.allSettled([inboxFiber?.dispose(), mcp.dispose()])
    throw error instanceof RuntimeError ? error : new RuntimeError('MCP_UNAVAILABLE')
  }
}

function externalUrl(value) {
  if (value === undefined) return undefined
  try {
    const url = new URL(value)
    if (['http:', 'https:'].includes(url.protocol) && !url.username && !url.password
        && !url.search && !url.hash) return url.href
  } catch { /* controlled error below */ }
  throw new RuntimeError('INVALID_EXTERNAL_ENDPOINT')
}

async function externalEndpoints(config) {
  const inboxUrl = externalUrl(config.inboxUrl)
  const mcpUrl = externalUrl(config.mcpUrl)
  const mcpOpsUrl = externalUrl(config.mcpOpsUrl)
  if ((!inboxUrl && !mcpUrl) || (mcpOpsUrl && !mcpUrl)) throw new RuntimeError('EXTERNAL_ENDPOINT_REQUIRED')
  if (mcpOpsUrl && (!mcpUrl.endsWith('/daily') || mcpOpsUrl !== mcpUrl.slice(0, -6) + '/ops')) {
    throw new RuntimeError('INVALID_EXTERNAL_MCP_SURFACES')
  }
  const envFile = config.envFile === undefined ? undefined : requireAbsolute(config.envFile)
  const secret = envFile ? await credentials(envFile) : undefined
  return { inboxUrl, inboxId: config.inboxId, mcpUrl, mcpOpsUrl, envFile, mcpToken: secret?.mcp }
}

export function applyRuntime(ctx, config, backend) {
  const logger = ctx.logger('bma3-runtime')
  const profile = ctx.get('profileContext')
  let queue = Promise.resolve()
  let owned
  let disconnect
  let disposed = false
  const autoStart = config.autoStart ?? config.source === 'bundled'
  const external = Boolean(backend) && config.source === 'external'
  let desiredRunning = null
  let state = { phase: 'stopped', source: config.source, connected: false }
  const status = () => {
    if (!backend) return { ...state }
    const connections = backend.status()
    const connected = connections.inbox === 'connected' && connections.mcp === 'connected'
    return { ...state, ...connections, connected, desiredRunning: external ? null : desiredRunning ?? autoStart }
  }
  const serial = (run) => {
    const next = queue.then(run, run)
    queue = next.catch(() => {})
    return next
  }

  async function closeOwned() {
    const close = disconnect
    disconnect = undefined
    const child = owned
    owned = undefined
    try {
      await close?.()
    } finally {
      await child?.stop()
    }
  }

  async function start(remember) {
    if (disposed || owned?.running && state.phase === 'running') return status()
    state = { phase: 'starting', source: config.source, connected: false }
    try {
      if (!['bundled', 'local-jar'].includes(config.source)) throw new RuntimeError('INVALID_SOURCE')
      if (!windows && process.platform !== 'linux') throw new RuntimeError('MANAGED_PLATFORM_UNSUPPORTED')
      const root = runtimeRoot(profile, config)
      await mkdir(root, { recursive: true, mode: 0o700 })
      const configRoot = config.source === 'local-jar' ? requireAbsolute(config.configRoot) : root
      if (config.source === 'bundled') await prepareBundledConfig(root)
      const activeFile = join(root, 'active.json')
      const previous = config.source === 'bundled' ? await installedImage(root, await readJson(activeFile)) : null

      async function startFrom(executable, leadingArgs, expectedVersion) {
        if (disposed) throw new RuntimeError('RUNTIME_DISPOSED')
        const child = launch(executable, [
          ...leadingArgs, '--config', configRoot, '--managed-runtime', '--manager-state', root,
        ], configRoot)
        owned = child
        const endpoint = await within(child.ready, START_TIMEOUT, 'MANAGED_START_TIMEOUT')
        if (expectedVersion && endpoint.version !== expectedVersion) throw new RuntimeError('IMAGE_VERSION_MISMATCH')
        disconnect = await connect(ctx, config, root, configRoot, endpoint, backend)
        if (disposed || !child.running) throw new RuntimeError('CHILD_EXITED')
        void child.exited.then(() => serial(async () => {
          if (owned !== child) return
          await closeOwned()
          state = { phase: 'failed', source: config.source, connected: false, code: 'CHILD_EXITED' }
          logger.warn('BMA3 runtime: CHILD_EXITED')
        }))
        return endpoint
      }

      let endpoint
      let rollbackCode
      if (config.source === 'local-jar') {
        endpoint = await startFrom(requireAbsolute(config.javaPath), ['-jar', requireAbsolute(config.jarPath)])
      } else {
        let candidate
        try {
          candidate = await installImage(root)
          endpoint = await startFrom(candidate.executable, [], candidate.image.version)
          await writeJson(activeFile, candidate.image)
        } catch (error) {
          await closeOwned()
          if (!previous || candidate?.image.sha256 === previous.image.sha256
              || error.message === 'INSTANCE_IN_USE') throw error
          endpoint = await startFrom(previous.executable, [], previous.image.version)
          rollbackCode = error instanceof RuntimeError ? error.message : 'CANDIDATE_FAILED'
          logger.warn('BMA3 runtime restored the previous image: ' + rollbackCode)
        }
      }
      if (remember) {
        await writeJson(join(root, 'desired.json'), { running: true })
        desiredRunning = true
      }
      state = {
        phase: 'running', source: config.source, connected: true, version: endpoint.version,
        mcpSurface: endpoint.mcpSurface, ...(endpoint.mcpOpsUrl ? { mcpOpsUrl: endpoint.mcpOpsUrl } : {}),
        ...(rollbackCode ? { rollbackCode } : {}),
      }
      logger.info('BMA3 runtime ready: ' + endpoint.version)
    } catch (error) {
      await closeOwned()
      const code = error instanceof RuntimeError ? error.message : 'START_FAILED'
      state = { phase: 'failed', source: config.source, connected: false, code }
      logger.warn('BMA3 runtime: ' + code)
    }
    return status()
  }

  async function stop() {
    if (!owned && state.code === 'INSTANCE_IN_USE') return status()
    let code
    try {
      const root = runtimeRoot(profile, config)
      await mkdir(root, { recursive: true, mode: 0o700 })
      await writeJson(join(root, 'desired.json'), { running: false })
      desiredRunning = false
    } catch {
      code = 'DESIRED_STATE_UNAVAILABLE'
    }
    try {
      await closeOwned()
    } catch {
      return { ...status(), code: 'STOP_FAILED' }
    }
    state = { phase: 'stopped', source: config.source, connected: false, ...(code ? { code } : {}) }
    return status()
  }

  async function restart() {
    if (!owned && state.code === 'INSTANCE_IN_USE') return status()
    try {
      await closeOwned()
    } catch {
      return { ...status(), code: 'STOP_FAILED' }
    }
    return start(true)
  }

  const actions = backend ? ['status', 'start', 'stop', 'restart'] : ['status', 'start', 'stop']
  async function execute(action) {
    if (external && action !== 'status') return { ...status(), code: 'EXTERNAL_RUNTIME' }
    switch (action) {
      case 'start': return serial(() => start(true))
      case 'stop': return serial(stop)
      case 'restart': return backend ? serial(restart) : status()
      default: return status()
    }
  }

  ctx.effect(() => ctx.tools.register({
    name: 'bma3_runtime',
    description: backend ? '查看、启动、停止或重启当前 DSH profile 托管的 BMA3 进程。'
      : '查看、启动或停止当前 DSH profile 托管的 BMA3 进程。',
    parameters: {
      type: 'object',
      properties: { action: { type: 'string', enum: actions } },
      additionalProperties: false,
    },
    output: {
      schema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] },
      render: (_args, value) => [{ type: 'text', text: value.text }],
    },
    execute: async ({ action = 'status' } = {}) => ({
      text: JSON.stringify(await execute(action)),
    }),
  }))
  ctx.effect(() => async () => {
    disposed = true
    await serial(closeOwned)
  })
  void serial(async () => {
    try {
      if (external) {
        const endpoint = await externalEndpoints(config)
        if (disposed) return
        disconnect = await backend.activate(endpoint, true)
        state = { phase: 'external', source: config.source, connected: false }
        return
      }
      if (!['bundled', 'local-jar'].includes(config.source)) throw new RuntimeError('INVALID_SOURCE')
      const desired = await readJson(join(runtimeRoot(profile, config), 'desired.json'))
      if (desired !== null && typeof desired.running !== 'boolean') throw new RuntimeError('RUNTIME_CONFIG_INVALID')
      desiredRunning = desired?.running ?? null
      if (desiredRunning ?? autoStart) await start(false)
    } catch (error) {
      const code = error instanceof RuntimeError ? error.message : 'RUNTIME_CONFIG_INVALID'
      state = { phase: 'failed', source: config.source, connected: false, code }
      logger.warn('BMA3 runtime: ' + code)
    }
  })
}
