#!/usr/bin/env node
/**
 * Isolated loopback verification for the Inbox ACK recovery contract (Task 3).
 *
 * Real components on the wire:
 *   - Java `SqliteInboxStore` + `InboxService` + `InboxHttpServer` (tools/e2e-local/InboxAckLoopbackHarness.java)
 *   - the real DSH plugin client `createInboxClient` from dsh-plugin/lib/inbox.js
 *
 * Fake boundaries only: the DSH session agent `followup` (records injections) and the deliberate
 * lost ACK response. No real QQ, no real DSH host, no network beyond 127.0.0.1. The harness uses a
 * fresh mkdtemp directory, a random token written to a 0600 env file, an ephemeral loopback port
 * and its own state snapshot + lock.
 *
 * Scenario (one pass, deliberately not a broad suite):
 *   1. one real push is injected and the first real ACK is committed by Java;
 *   2. the ACK response is dropped after the commit: the server is ACKED, the client keeps the
 *      exact pending record;
 *   3. the client is disposed and recreated from the same state file; it retries the exact same
 *      revision/IDs/identity and gets an `alreadyAcked` proof, with no second followup;
 *   4. after the fresh verification proves the old panel empty, a genuinely new push is injected
 *      once and ACKed, proving old confirmed content is not replayed and the watermark does not
 *      swallow the new push.
 *
 * Usage: node tools/e2e-local/e2e_inbox_ack_recovery.mjs
 * Prerequisite: Java 21, Node 22, local Maven repository with this project's dependencies.
 * Set BMA3_E2E_SKIP_MAVEN=1 only when the current Java sources were already compiled.
 */
import { spawn, spawnSync } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import {
  chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, statSync,
  writeFileSync,
} from 'node:fs'
import { homedir, tmpdir } from 'node:os'
import { basename, join, sep } from 'node:path'
import { createInterface } from 'node:readline'
import { fileURLToPath } from 'node:url'
import { createInboxClient, inboxStatePaths } from '../../dsh-plugin/lib/inbox.js'

const ROOT = fileURLToPath(new URL('../..', import.meta.url))
const HARNESS_SOURCE = join(ROOT, 'tools/e2e-local/InboxAckLoopbackHarness.java')
const HARNESS_CLASS = 'InboxAckLoopbackHarness'
const INBOX_ID = 'bma3'
const AGENT_ID = 'session-e2e-ack-recovery'
const JAVA_MAIN = 'InboxAckLoopbackHarness'

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

let tempRoot = null
let tempVerified = false
let child = null
let childExited = false
let childExit = null
let childPid = null
let readline = null
let client1 = null
let client2 = null
let port = null
let token = null
let envFile = null
let stateFile = null
let v2StateFile = null
let stateLockFile = null
let exitCode = 2
let ackLossInjected = false

const events = []
const stderrLines = []
const stdoutLines = []
const checks = []

function redact(value) {
  const text = String(value ?? '')
  return token ? text.split(token).join('<redacted-token>') : text
}

function check(name, ok, detail = '') {
  const entry = { name, ok: Boolean(ok), detail: redact(detail) }
  checks.push(entry)
  console.log(`${entry.ok ? 'PASS  ' : 'FAIL  '}${name}${entry.detail ? `  | ${entry.detail}` : ''}`)
  return entry.ok
}

function requireCheck(name, ok, detail = '') {
  if (!check(name, ok, detail)) throw new Error(`check failed: ${name}`)
}

function javaClasspath(classesDir) {
  const jars = [
    'com/fasterxml/jackson/core/jackson-databind/2.22.4/jackson-databind-2.22.4.jar',
    'com/fasterxml/jackson/core/jackson-core/2.22.4/jackson-core-2.22.4.jar',
    'com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22.jar',
    'com/fasterxml/jackson/datatype/jackson-datatype-jsr310/2.22.4/jackson-datatype-jsr310-2.22.4.jar',
    'org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0.jar',
    'org/slf4j/slf4j-api/2.0.20/slf4j-api-2.0.20.jar',
  ].map((relative) => join(homedir(), '.m2/repository', relative))
  const modules = [
    join(ROOT, 'bma3-inbox/target/classes'),
    join(ROOT, 'bma3-core/target/classes'),
  ]
  for (const path of [...modules, ...jars]) {
    if (!existsSync(path)) throw new Error(`missing Java classpath entry: ${path}`)
  }
  return [classesDir, ...modules, ...jars].filter(Boolean).join(':')
}

function runCompile() {
  if (process.env.BMA3_E2E_SKIP_MAVEN !== '1') {
    const maven = spawnSync(join(ROOT, 'mvnw'),
      ['-q', '-o', '-pl', 'bma3-inbox', '-am', 'compile', '-DskipTests'],
      { cwd: ROOT, encoding: 'utf8', timeout: 300_000 })
    console.log(`maven compile rc=${maven.status ?? 'signal:' + maven.signal}`)
    if (maven.status !== 0) {
      throw new Error(`maven compile failed: ${redact((maven.stdout ?? '') + (maven.stderr ?? '')).slice(-2000)}`)
    }
  } else {
    console.log('maven compile skipped (BMA3_E2E_SKIP_MAVEN=1; sources were compiled separately)')
  }
  const classesDir = join(tempRoot, 'harness-classes')
  mkdirSync(classesDir, { recursive: true })
  const javac = spawnSync('javac', ['-cp', javaClasspath(''), '-d', classesDir, HARNESS_SOURCE],
    { cwd: ROOT, encoding: 'utf8', timeout: 120_000 })
  console.log(`javac rc=${javac.status ?? 'signal:' + javac.signal}`)
  if (javac.status !== 0) {
    throw new Error(`javac failed: ${redact((javac.stdout ?? '') + (javac.stderr ?? '')).slice(-2000)}`)
  }
  return classesDir
}

function makeTempRoot() {
  const base = realpathSync(tmpdir())
  const created = realpathSync(mkdtempSync(join(base, 'bma3-ack-e2e-')))
  if (!created.startsWith(base + sep) || !basename(created).startsWith('bma3-ack-e2e-')) {
    throw new Error(`refusing unexpected temp path: ${created}`)
  }
  tempRoot = created
  chmodSync(tempRoot, 0o700)
  tempVerified = true
  return created
}

function makeIsolatedState() {
  token = randomBytes(32).toString('hex')
  envFile = join(tempRoot, 'token.env')
  writeFileSync(envFile, `INBOX_TOKEN=${token}\n`, { mode: 0o600 })
  chmodSync(envFile, 0o600)
  const envMode = statSync(envFile).mode & 0o777
  requireCheck('envFile is mode 0600', envMode === 0o600, `0${envMode.toString(8)}`)
  const stateDir = join(tempRoot, 'state')
  mkdirSync(stateDir, { recursive: true, mode: 0o700 })
  stateFile = join(stateDir, 'bma3-inbox.json')
  v2StateFile = inboxStatePaths({ stateFile }).v2
  stateLockFile = inboxStatePaths({ stateFile }).lock
}

function startJava(classesDir) {
  const classpath = javaClasspath(classesDir)
  child = spawn('java', ['-cp', classpath, JAVA_MAIN, tempRoot], {
    cwd: ROOT,
    env: { ...process.env, BMA3_INBOX_TOKEN: token },
    stdio: ['pipe', 'pipe', 'pipe'],
  })
  childPid = child.pid ?? null
  console.log(`java harness pid=${childPid ?? 'unknown'} (127.0.0.1, ephemeral port)`)
  readline = createInterface({ input: child.stdout, crlfDelay: Infinity })
  readline.on('line', (line) => {
    stdoutLines.push(line)
    try {
      const event = JSON.parse(line)
      events.push(event)
      if (event.event === 'error') {
        stderrLines.push(`harness error: ${String(event.error ?? 'unknown')}`)
      }
    } catch {
      stdoutLines.push(`(unparsed) ${line}`)
    }
  })
  child.stderr.on('data', (chunk) => {
    for (const line of String(chunk).split('\n')) {
      if (line.trim() !== '') stderrLines.push(line)
    }
  })
  child.on('error', (error) => {
    stderrLines.push(`java spawn error: ${error?.message ?? error}`)
  })
  child.on('exit', (code, signal) => {
    childExited = true
    childExit = { code, signal }
  })
}

function childTail() {
  return redact([...stderrLines.slice(-5), ...stdoutLines.slice(-5)].join(' | ')).slice(0, 1500)
}

async function waitFor(predicate, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await predicate()) return true
    if (childExited) throw new Error(`${label}: Java harness exited (${JSON.stringify(childExit)}) ${childTail()}`)
    await sleep(50)
  }
  throw new Error(`${label}: timeout after ${timeoutMs}ms ${childTail()}`)
}

function sendCommand(command) {
  if (!child || childExited || !child.stdin.writable) {
    throw new Error(`cannot send "${command}": Java harness is not writable`)
  }
  child.stdin.write(`${command}\n`)
}

function itemEvent(command) {
  return events.find((event) => event.event === 'item' && event.command === command)
}

async function httpJson(pathname) {
  const response = await fetch(`http://127.0.0.1:${port}${pathname}`, {
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
  })
  const text = await response.text()
  let body = null
  try { body = JSON.parse(text) } catch { body = null }
  return { status: response.status, body, text }
}

async function getPanel() {
  const result = await httpJson(`/v1/inboxes/${INBOX_ID}/panel`)
  if (result.status !== 200 || !result.body) throw new Error(`panel HTTP ${result.status}`)
  return result.body
}

async function getStatus() {
  const result = await httpJson(`/v1/inboxes/${INBOX_ID}/status`)
  if (result.status !== 200 || !result.body) throw new Error(`status HTTP ${result.status}`)
  return result.body
}

function makeFetch() {
  const ackExchanges = []
  const realFetch = globalThis.fetch
  const fetchImpl = async (url, init = {}) => {
    const textUrl = String(url)
    if (textUrl.endsWith('/ack') && init.method === 'POST') {
      const request = JSON.parse(String(init.body))
      const response = await realFetch(url, init)
      const clone = response.clone()
      const responseText = await clone.text()
      let responseBody = null
      try { responseBody = JSON.parse(responseText) } catch { responseBody = null }
      const lostAfterCommit = !ackLossInjected
      ackExchanges.push({
        request,
        status: response.status,
        body: responseBody,
        lostAfterCommit,
      })
      if (lostAfterCommit) {
        ackLossInjected = true
        // The Java service has already committed this ACK and returned a complete proof; the
        // plugin must see a transport failure, not the response. The next request is a genuine
        // retry over the real socket.
        throw new TypeError('simulated lost ACK response after server commit')
      }
      return response
    }
    return realFetch(url, init)
  }
  return { fetchImpl, ackExchanges }
}

function fakeDsh() {
  const injections = []
  const agent = {
    id: AGENT_ID,
    status: 'idle',
    followup(message) {
      injections.push(message)
      return Promise.resolve()
    },
  }
  const ctx = {
    agents: {
      get: (id) => (id === AGENT_ID ? agent : undefined),
      list: () => [agent],
    },
  }
  return { injections, agent, ctx }
}

function pluginConfig(fetchImpl, overrides = {}) {
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    inboxId: INBOX_ID,
    envFile,
    sessionId: AGENT_ID,
    stateFile,
    fetchImpl,
    pumpMs: 50,
    reconnectMinMs: 200,
    heartbeatMs: 60_000,
    panelTimeoutMs: 5_000,
    ackTimeoutMs: 5_000,
    ackRetryBaseMs: 60_000,
    ackRetryMaxMs: 60_000,
    ...overrides,
  }
}

function injectionText(message) {
  return String(message?.content?.[0]?.text ?? '')
}

function stateBytes() {
  return readFileSync(v2StateFile, 'utf8')
}

function parseState() {
  return JSON.parse(stateBytes())
}

async function stopJava() {
  if (!child) return
  if (!childExited) {
    try {
      if (child.stdin.writable) {
        child.stdin.write('shutdown\n')
        child.stdin.end()
      }
    } catch { /* child already gone */ }
    await Promise.race([
      new Promise((resolve) => child.once('exit', resolve)),
      sleep(5_000),
    ])
  }
  if (!childExited) {
    console.log('java harness did not exit after shutdown; sending SIGTERM')
    try { child.kill('SIGTERM') } catch { /* already gone */ }
    await Promise.race([
      new Promise((resolve) => child.once('exit', resolve)),
      sleep(2_000),
    ])
  }
  if (!childExited) {
    console.log('java harness still alive; sending SIGKILL')
    try { child.kill('SIGKILL') } catch { /* already gone */ }
    await Promise.race([
      new Promise((resolve) => child.once('exit', resolve)),
      sleep(2_000),
    ])
  }
  readline?.close()
  console.log(`java harness exit=${JSON.stringify(childExit)}`)
}

function removeTempRoot() {
  if (!tempRoot || !tempVerified) {
    if (tempRoot) console.log(`KEEP temp (not verified safe): ${tempRoot}`)
    return
  }
  try {
    const base = realpathSync(tmpdir())
    // Re-resolve immediately before deleting: the only path this script may remove is the
    // mkdtemp child created above, directly under the system temp directory.
    const resolved = realpathSync(tempRoot)
    if (!resolved.startsWith(base + sep) || !basename(resolved).startsWith('bma3-ack-e2e-')) {
      console.log(`KEEP temp (path changed): ${resolved}`)
      return
    }
    rmSync(resolved, { recursive: true, force: true })
    console.log('temp dir removed after verified absolute-path check')
  } catch (error) {
    console.log(`KEEP temp (cleanup failed): ${tempRoot} (${redact(error?.message ?? error)})`)
  }
}

async function main() {
  makeTempRoot()
  makeIsolatedState()
  console.log(`isolated temp dir: ${tempRoot}`)
  const classesDir = runCompile()
  startJava(classesDir)

  await waitFor(() => events.some((event) => event.event === 'ready'), 10_000, 'harness ready')
  const ready = events.find((event) => event.event === 'ready')
  port = Number(ready.port)
  requireCheck('Java HTTP bound an ephemeral loopback port',
    Number.isInteger(port) && port > 0 && port < 65536, `port=${port}`)

  sendCommand('seed-a')
  await waitFor(() => Boolean(itemEvent('seed-a')), 10_000, 'seed-a item')
  const itemA = itemEvent('seed-a').id
  requireCheck('first real item id is non-empty', typeof itemA === 'string' && itemA.length > 0, itemA)

  // Drive one real push before any client connects, so the first SSE snapshot is already the
  // frozen push. This calls the production awaitPush/flushPending path, not a fake panel.
  sendCommand('flush')
  await waitFor(() => events.some((event) => event.event === 'flushed'), 10_000, 'first real push')
  const flushed = events.find((event) => event.event === 'flushed')
  requireCheck('real push froze exactly the seeded item',
    flushed.pushRevision >= 1 && flushed.items === 1,
    JSON.stringify({ revision: flushed.revision, pushRevision: flushed.pushRevision, items: flushed.items }))

  await waitFor(async () => {
    const panel = await getPanel()
    return panel.items?.length === 1 && panel.items[0]?.id === itemA && panel.pushRevision >= 1
      && panel.items[0]?.frozenRevision === panel.pushRevision
  }, 10_000, 'first real push frozen')
  const firstPanel = await getPanel()
  const identity = firstPanel.inboxIdentity
  requireCheck('server panel exposes a durable inboxIdentity', typeof identity === 'string' && identity.length > 0,
    identity)
  requireCheck('first push is frozen for the same push revision',
    firstPanel.pushRevision >= 1 && firstPanel.items[0].frozenRevision === firstPanel.pushRevision,
    `push=${firstPanel.pushRevision} frozen=${firstPanel.items[0].frozenRevision}`)

  const { injections, ctx } = fakeDsh()
  const first = makeFetch()
  client1 = createInboxClient(ctx, pluginConfig(first.fetchImpl))
  client1.start()
  await waitFor(() => injections.length === 1, 15_000, 'first followup')
  requireCheck('first injection is the real panel', injectionText(injections[0]).includes('E2E-ACK-OLD-MARKER'))
  requireCheck('first injection carries the pushed revision',
    Number(injections[0]?.source?.pushRevision) >= 1,
    JSON.stringify({ revision: injections[0]?.source?.revision, push: injections[0]?.source?.pushRevision }))

  await waitFor(() => first.ackExchanges.length === 1, 15_000, 'first ACK exchange')
  const lost = first.ackExchanges[0]
  requireCheck('first ACK reached Java and committed before the response loss',
    lost.status === 200 && lost.body?.complete === true
      && Array.isArray(lost.body?.ackedItemIds) && lost.body.ackedItemIds.length === 1
      && lost.body.ackedItemIds[0] === itemA
      && Array.isArray(lost.body?.missingItemIds) && lost.body.missingItemIds.length === 0,
    JSON.stringify({ status: lost.status, complete: lost.body?.complete, acked: lost.body?.ackedItemIds }))
  requireCheck('first ACK request carried the exact revision/item/identity proof',
    lost.request?.revision === firstPanel.revision
      && JSON.stringify(lost.request?.itemIds) === JSON.stringify([itemA])
      && lost.request?.inboxIdentity === identity,
    JSON.stringify({ revision: lost.request?.revision, itemIds: lost.request?.itemIds }))

  await waitFor(() => {
    const status = client1.status()
    return Boolean(status.pendingAck) && status.pendingAck.attempts >= 1
      && status.lastError === 'ack_network'
  }, 10_000, 'client retains pending ACK')
  const pendingBefore = client1.status().pendingAck
  requireCheck('client kept the exact pending record after response loss',
    pendingBefore?.panelRevision === lost.request.revision
      && JSON.stringify(pendingBefore?.itemIds) === JSON.stringify([itemA])
      && pendingBefore?.inboxIdentity === identity
      && pendingBefore?.pushRevision >= 1,
    JSON.stringify({
      panelRevision: pendingBefore?.panelRevision,
      pushRevision: pendingBefore?.pushRevision,
      itemIds: pendingBefore?.itemIds,
      attempts: pendingBefore?.attempts,
      lastError: pendingBefore?.lastError,
    }))
  requireCheck('no second followup happened while the ACK was unresolved', injections.length === 1,
    `${injections.length} injection(s)`)

  const serverStatusAfterLoss = await getStatus()
  const serverPanelAfterLoss = await getPanel()
  requireCheck('server really committed the ACK (status acked=1, available=0)',
    serverStatusAfterLoss.acked === 1 && serverStatusAfterLoss.available === 0,
    JSON.stringify({ acked: serverStatusAfterLoss.acked, available: serverStatusAfterLoss.available }))
  requireCheck('server panel no longer contains the confirmed item',
    Array.isArray(serverPanelAfterLoss.items) && serverPanelAfterLoss.items.length === 0,
    `items=${serverPanelAfterLoss.items?.length ?? 'invalid'}`)

  requireCheck('v2 state lock is held while the client is alive', existsSync(stateLockFile))
  client1.dispose()
  client1 = null
  requireCheck('v2 state lock is released on dispose', !existsSync(stateLockFile))
  await sleep(100)
  const stateAfterDispose = parseState()
  const persistedPending = stateAfterDispose.pendingAck
  requireCheck('v2 state snapshot persists exactly one pending ACK',
    stateAfterDispose.version === 2 && Boolean(persistedPending)
      && persistedPending.panelRevision === lost.request.revision
      && JSON.stringify(persistedPending.itemIds) === JSON.stringify([itemA])
      && persistedPending.inboxIdentity === identity
      && persistedPending.targetSessionId === AGENT_ID,
    JSON.stringify({
      version: stateAfterDispose.version,
      targetSessionId: persistedPending?.targetSessionId,
      panelRevision: persistedPending?.panelRevision,
      pushRevision: persistedPending?.pushRevision,
      itemIds: persistedPending?.itemIds,
    }))
  requireCheck('state snapshot is mode 0600', (statSync(v2StateFile).mode & 0o777) === 0o600,
    `0${(statSync(v2StateFile).mode & 0o777).toString(8)}`)
  requireCheck('state snapshot contains no message body and no token',
    !stateBytes().includes('E2E-ACK-OLD-MARKER') && !stateBytes().includes(token),
    `${Buffer.byteLength(stateBytes())} bytes`)

  // Recreate the client from the same isolated state file; this is a genuinely new client
  // instance with a new in-memory identity-verification flag, so it must re-prove the server
  // identity before retrying.
  const second = makeFetch()
  client2 = createInboxClient(ctx, pluginConfig(second.fetchImpl, {
    ackRetryBaseMs: 300,
    ackRetryMaxMs: 1_000,
  }))
  const loadedPending = client2.status().pendingAck
  requireCheck('recreated client loaded the same pending ACK',
    loadedPending?.panelRevision === lost.request.revision
      && JSON.stringify(loadedPending?.itemIds) === JSON.stringify([itemA])
      && loadedPending?.inboxIdentity === identity,
    JSON.stringify({ panelRevision: loadedPending?.panelRevision, itemIds: loadedPending?.itemIds }))
  client2.start()
  await client2.ack()
  await waitFor(() => client2.status().pendingAck === null, 15_000, 'retry ACK complete')
  requireCheck('retry used exactly the same request (revision, IDs, identity)',
    second.ackExchanges.length === 1
      && second.ackExchanges[0].request.revision === lost.request.revision
      && JSON.stringify(second.ackExchanges[0].request.itemIds) === JSON.stringify(lost.request.itemIds)
      && second.ackExchanges[0].request.inboxIdentity === lost.request.inboxIdentity,
    JSON.stringify({
      exchanges: second.ackExchanges.length,
      revision: second.ackExchanges[0]?.request?.revision,
      itemIds: second.ackExchanges[0]?.request?.itemIds,
      identityMatches: second.ackExchanges[0]?.request?.inboxIdentity === lost.request.inboxIdentity,
      retryIdentity: second.ackExchanges[0]?.request?.inboxIdentity,
      originalIdentity: lost.request.inboxIdentity,
    }))
  const retry = second.ackExchanges[0]
  requireCheck('retry was answered with a complete alreadyAcked proof and no missing IDs',
    retry.status === 200 && retry.body?.complete === true
      && Array.isArray(retry.body?.ackedItemIds) && retry.body.ackedItemIds.length === 0
      && JSON.stringify(retry.body?.alreadyAckedItemIds) === JSON.stringify([itemA])
      && Array.isArray(retry.body?.missingItemIds) && retry.body.missingItemIds.length === 0
      && retry.body?.inboxIdentity === identity,
    JSON.stringify({
      complete: retry.body?.complete,
      acked: retry.body?.ackedItemIds,
      alreadyAcked: retry.body?.alreadyAckedItemIds,
      missing: retry.body?.missingItemIds,
    }))
  requireCheck('ACK retry did not repeat the followup', injections.length === 1,
    `${injections.length} injection(s)`)

  await waitFor(() => client2.status().freshVerification?.required === false, 10_000,
    'fresh panel verification')
  await sleep(500)
  requireCheck('fresh verification of the now-empty panel did not inject old content again',
    injections.length === 1, `${injections.length} injection(s)`)
  const afterRetryState = parseState()
  requireCheck('pending ACK was cleared from the durable snapshot after the proof',
    afterRetryState.pendingAck === null,
    JSON.stringify({ pendingAck: afterRetryState.pendingAck, injectedPushRevision: afterRetryState.injectedPushRevision }))

  // A genuinely new server push must not be swallowed by the old push/ACK watermarks.
  sendCommand('append-b')
  await waitFor(() => Boolean(itemEvent('append-b')), 10_000, 'append-b item')
  const itemB = itemEvent('append-b').id
  await waitFor(async () => {
    const panel = await getPanel()
    return panel.items?.some((item) => item.id === itemB) && panel.pushRevision >= 2
  }, 10_000, 'second real push frozen')
  await waitFor(() => injections.length === 2, 20_000, 'second followup')
  const secondInjection = injections[1]
  requireCheck('new push was injected exactly once (watermark did not swallow it)',
    injections.length === 2 && injectionText(secondInjection).includes('E2E-ACK-NEW-MARKER'),
    `injections=${injections.length}`)
  requireCheck('old confirmed content is absent from the new injection',
    !injectionText(secondInjection).includes('E2E-ACK-OLD-MARKER'))
  requireCheck('new injection carries a newer push revision',
    Number(secondInjection?.source?.pushRevision) > Number(injections[0]?.source?.pushRevision),
    JSON.stringify({ first: injections[0]?.source?.pushRevision, second: secondInjection?.source?.pushRevision }))

  await waitFor(async () => (await getStatus()).acked >= 2, 15_000, 'second item ACKed')
  await waitFor(() => client2.status().pendingAck === null, 10_000, 'second ACK complete')
  await sleep(500)
  const finalStatus = await getStatus()
  requireCheck('server has both items ACKED and none available',
    finalStatus.acked === 2 && finalStatus.available === 0,
    JSON.stringify({ acked: finalStatus.acked, available: finalStatus.available }))
  requireCheck('no duplicate injections at rest', injections.length === 2,
    `${injections.length} injection(s)`)
  requireCheck('exactly three real ACK HTTP exchanges happened (commit+lost, retry, new item)',
    first.ackExchanges.length + second.ackExchanges.length === 3,
    `${first.ackExchanges.length + second.ackExchanges.length}`)
  const lastAck = second.ackExchanges[1]
  requireCheck('the new push was ACKed with its own full proof',
    lastAck?.status === 200 && lastAck.body?.complete === true
      && JSON.stringify(lastAck.body?.ackedItemIds) === JSON.stringify([itemB])
      && lastAck.body?.inboxIdentity === identity,
    JSON.stringify({ status: lastAck?.status, acked: lastAck?.body?.ackedItemIds }))

  const finalState = parseState()
  requireCheck('durable state at rest has no pending/intent record and no body',
    finalState.pendingAck === null && finalState.injectionIntent === null
      && !stateBytes().includes('E2E-ACK-OLD-MARKER')
      && !stateBytes().includes('E2E-ACK-NEW-MARKER')
      && !stateBytes().includes(token),
    JSON.stringify({ pendingAck: finalState.pendingAck, injectionIntent: finalState.injectionIntent }))

  return 0
}

try {
  exitCode = await main()
} catch (error) {
  console.error(`ERROR ${redact(error?.stack ?? error)}`)
  exitCode = 1
} finally {
  try { client2?.dispose() } catch (error) { console.error(`client2 dispose: ${redact(error?.message ?? error)}`) }
  try { client1?.dispose() } catch (error) { console.error(`client1 dispose: ${redact(error?.message ?? error)}`) }
  await stopJava()
  removeTempRoot()
  const passed = checks.filter((entry) => entry.ok).length
  console.log(`SUMMARY ${passed}/${checks.length} checks passed`)
  console.log(`EVIDENCE script=tools/e2e-local/e2e_inbox_ack_recovery.mjs java=tools/e2e-local/InboxAckLoopbackHarness.java`)
  if (childExit && childExit.code !== 0) {
    console.log(`WARN java harness exit code ${childExit.code}`)
    exitCode = exitCode === 0 ? 1 : exitCode
  }
  process.exit(exitCode)
}

process.on('exit', () => {
  try { if (child && !childExited) child.kill('SIGKILL') } catch { /* best effort */ }
})
