import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { constants } from 'node:fs'
import { mkdir, open, writeFile } from 'node:fs/promises'
import { isAbsolute, join, resolve } from 'node:path'
import { createInterface } from 'node:readline'
import { setTimeout as delay } from 'node:timers/promises'
import { apply as applyInbox } from './inbox.js'
import { hostToolRowName } from './host-tools.js'
import { installedImage, installImage, readJson, RuntimeError, writeJson } from './runtime-image.js'

const START_TIMEOUT = 20000
const STOP_TIMEOUT = 5000
const READY = 'BMA3_MANAGED_READY '
const OCCUPIED = 'BMA3_MANAGED_ERROR INSTANCE_IN_USE'
const MCP_CREDENTIAL = 'BMA3_MANAGED_MCP_TOKEN'

function requireAbsolute(value) {
  if (typeof value !== 'string' || !isAbsolute(value)) throw new RuntimeError('INVALID_RUNTIME_PATH')
  return resolve(value)
}

function runtimeRoot(profile, config) {
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

function endpoints(info) {
  const port = (value) => Number.isInteger(value) && value >= 1 && value <= 65535
  if (info.protocol !== 1 || typeof info.version !== 'string' || !info.version
      || ![info.mcpPort, info.inboxPort, info.healthPort].every(port)
      || typeof info.mcpPath !== 'string' || !info.mcpPath.startsWith('/')
      || typeof info.inboxId !== 'string' || !info.inboxId) {
    throw new RuntimeError('INVALID_MANAGED_HANDSHAKE')
  }
  const loopback = (number) => 'http://127.0.0.1:' + number
  return {
    version: info.version,
    mcpUrl: loopback(info.mcpPort) + info.mcpPath,
    inboxUrl: loopback(info.inboxPort),
    healthUrl: loopback(info.healthPort) + '/healthz',
    inboxId: info.inboxId,
    envFile: requireAbsolute(info.envFile),
  }
}

function launch(executable, args, cwd) {
  const child = spawn(executable, args, { cwd, stdio: ['pipe', 'pipe', 'ignore'] })
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
        resolve(endpoints(JSON.parse(line.slice(READY.length))))
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

async function credentials(envFile) {
  const file = await open(envFile, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK)
  try {
    const stat = await file.stat()
    if (!stat.isFile() || (stat.mode & 0o777) !== 0o600 || stat.uid !== process.getuid() || stat.size > 16384) {
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

async function connect(ctx, config, root, configRoot, endpoint) {
  const secret = await credentials(endpoint.envFile)
  const health = await fetch(endpoint.healthUrl, { redirect: 'error', signal: AbortSignal.timeout(3000) })
  await health.body?.cancel()
  if (!health.ok) throw new RuntimeError('HEALTH_UNAVAILABLE')
  const panel = await fetch(endpoint.inboxUrl + '/v1/inboxes/' + encodeURIComponent(endpoint.inboxId) + '/panel', {
    headers: { Authorization: 'Bearer ' + secret.inbox },
    redirect: 'error',
    signal: AbortSignal.timeout(3000),
  })
  if (!panel.ok || !(await panel.json()).inboxIdentity) throw new RuntimeError('INBOX_UNAVAILABLE')

  const native = await import(hostToolRowName('@deepseek-ai/dsh-mcp-client'))
  let resolveMcp
  let rejectMcp
  const ready = new Promise((resolve, reject) => { resolveMcp = resolve; rejectMcp = reject })
  const mcpFiber = ctx.isolate('credentials').plugin({
    name: 'bma3-managed-mcp',
    apply(scope) {
      scope.provide('credentials', {
        async resolve(ref) {
          return ref === MCP_CREDENTIAL ? { value: secret.mcp } : undefined
        },
      })
      // The provider must be active before the native client resolves its credentials.
      scope.plugin({
        name: 'bma3-native-mcp-client',
        inject: ['credentials', 'tools'],
        async apply(clientContext) {
          try {
            await native.apply(clientContext, native.Config({
              transport: 'streamable-http',
              serverName: 'bma3',
              url: endpoint.mcpUrl,
              headers: { Authorization: 'Bearer ${credential:BMA3_MANAGED_MCP_TOKEN}' },
              expandTemplates: true,
              startupTimeoutMs: 5000,
              failOnStartupError: true,
            }))
            resolveMcp()
          } catch {
            rejectMcp(new RuntimeError('MCP_UNAVAILABLE'))
            throw new RuntimeError('MCP_UNAVAILABLE')
          }
        },
      })
    },
  })

  let inboxFiber
  try {
    await within(ready, 7000, 'MCP_UNAVAILABLE')
    let inboxMounted = false
    const mounted = new Promise((resolve) => {
      const identity = createHash('sha256').update(JSON.stringify([configRoot, endpoint.envFile])).digest('hex')
      inboxFiber = ctx.plugin({
        name: 'bma3-managed-inbox',
        apply(scope) {
          const client = applyInbox(scope, {
            baseUrl: endpoint.inboxUrl,
            inboxId: endpoint.inboxId,
            envFile: endpoint.envFile,
            stateFile: join(root, 'inbox.json'),
            heartbeatMs: config.heartbeatMs,
          }, identity)
          const status = client.status()
          inboxMounted = !status.lockError && !status.stateError && status.persistence?.enabled !== false
          resolve()
        },
      })
    })
    await within(mounted, 3000, 'INBOX_UNAVAILABLE')
    if (!inboxMounted) throw new RuntimeError('INBOX_STATE_UNAVAILABLE')
    return async () => { await Promise.allSettled([inboxFiber.dispose(), mcpFiber.dispose()]) }
  } catch (error) {
    await Promise.allSettled([inboxFiber?.dispose(), mcpFiber.dispose()])
    throw error
  }
}

export function applyRuntime(ctx, config) {
  const logger = ctx.logger('bma3-runtime')
  const profile = ctx.get('profileContext')
  let queue = Promise.resolve()
  let owned
  let disconnect
  let disposed = false
  let state = { phase: 'stopped', source: config.source, connected: false }
  const status = () => ({ ...state })
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
    if (disposed || owned?.running && state.connected) return status()
    state = { phase: 'starting', source: config.source, connected: false }
    try {
      if (process.platform !== 'linux') throw new RuntimeError('MANAGED_PLATFORM_UNSUPPORTED')
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
        disconnect = await connect(ctx, config, root, configRoot, endpoint)
        if (disposed || !child.running) throw new RuntimeError('CHILD_EXITED')
        void child.exited.then(() => serial(async () => {
          if (owned !== child) return
          await closeOwned()
          state = { phase: 'failed', source: config.source, connected: false, code: 'CHILD_EXITED' }
          logger.warn('BMA3 runtime: CHILD_EXITED')
        }))
        return endpoint.version
      }

      let version
      let rollbackCode
      if (config.source === 'local-jar') {
        version = await startFrom(requireAbsolute(config.javaPath), ['-jar', requireAbsolute(config.jarPath)])
      } else {
        let candidate
        try {
          candidate = await installImage(root)
          version = await startFrom(candidate.executable, [], candidate.image.version)
          await writeJson(activeFile, candidate.image)
        } catch (error) {
          await closeOwned()
          if (!previous || candidate?.image.sha256 === previous.image.sha256
              || error.message === 'INSTANCE_IN_USE') throw error
          version = await startFrom(previous.executable, [], previous.image.version)
          rollbackCode = error instanceof RuntimeError ? error.message : 'CANDIDATE_FAILED'
          logger.warn('BMA3 runtime restored the previous image: ' + rollbackCode)
        }
      }
      if (remember) await writeJson(join(root, 'desired.json'), { running: true })
      state = { phase: 'running', source: config.source, connected: true, version, ...(rollbackCode ? { rollbackCode } : {}) }
      logger.info('BMA3 runtime ready: ' + version)
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

  ctx.effect(() => ctx.tools.register({
    name: 'bma3_runtime',
    description: '查看、启动或停止当前 DSH profile 托管的 BMA3 进程。',
    parameters: {
      type: 'object',
      properties: { action: { type: 'string', enum: ['status', 'start', 'stop'] } },
      additionalProperties: false,
    },
    output: {
      schema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] },
      render: (_args, value) => [{ type: 'text', text: value.text }],
    },
    execute: async ({ action = 'status' } = {}) => ({
      text: JSON.stringify(action === 'start' ? await serial(() => start(true))
        : action === 'stop' ? await serial(stop) : status()),
    }),
  }))
  ctx.effect(() => async () => {
    disposed = true
    await serial(closeOwned)
  })
  void serial(async () => {
    try {
      const desired = await readJson(join(runtimeRoot(profile, config), 'desired.json'))
      if (desired !== null && typeof desired.running !== 'boolean') throw new RuntimeError('RUNTIME_CONFIG_INVALID')
      if (desired?.running ?? (config.autoStart ?? config.source === 'bundled')) await start(false)
    } catch {
      state = { phase: 'failed', source: config.source, connected: false, code: 'RUNTIME_CONFIG_INVALID' }
      logger.warn('BMA3 runtime: RUNTIME_CONFIG_INVALID')
    }
  })
}
