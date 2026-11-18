import {
  closeSync, fstatSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, statSync,
  unlinkSync, writeFileSync,
} from 'node:fs'
import { createHash, randomUUID } from 'node:crypto'
import { homedir, hostname } from 'node:os'
import { dirname } from 'node:path'
import { createContextPusher, loadEventTextExtractor } from './context.js'

const WAKE_REVISION_HEADER = '[INBOX_PANEL]'
const ACTIVE_MARKER = '（消息更新活跃）'
const STATE_VERSION = 2
const LEGACY_STATE_VERSION = 1
const DEFAULT_ACK_RETRY_BASE_MS = 500
const DEFAULT_ACK_RETRY_MAX_MS = 30000
const PLUGIN_VERSION = (() => {
  try {
    return JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version
      || 'unknown'
  } catch {
    return 'unknown'
  }
})()

/**
 * Read the jar-owned token file (D-063). The plugin never generates or stores a token: BMA3 writes
 * `<configRoot>/.bma3env` and this side only reads `INBOX_TOKEN` out of it.
 *
 * @param config - loader row config carrying `envFile`
 * @returns the inbox token, or '' when the file/key is missing (server may run with auth:none)
 */
function tokenFor(config) {
  const token = readInboxToken(config)
  return token ?? ''
}

function readInboxToken(config) {
  if (!config.envFile) return undefined
  try {
    const text = readFileSync(config.envFile, 'utf8')
    for (const raw of text.split('\n')) {
      const line = raw.trim()
      if (line === '' || line.startsWith('#')) continue
      const split = line.indexOf('=')
      if (split <= 0) continue
      if (line.slice(0, split).trim() !== 'INBOX_TOKEN') continue
      const value = line.slice(split + 1).trim()
      return value === '' ? undefined : value
    }
    return undefined
  } catch {
    return undefined
  }
}

/** Bearer header, or nothing at all when the jar runs without auth (D-064). */
function authHeaders(config) {
  const token = tokenFor(config)
  return token === '' ? {} : { Authorization: `Bearer ${token}` }
}

/** Where the token comes from — never the value. */
function tokenSource(config) {
  if (!config.envFile) return 'none'
  return readInboxToken(config) === undefined ? 'env-file:missing' : 'env-file'
}

function defaultStateFile(config) {
  return config.stateFile ?? `${homedir()}/.dsh/storages/bma3-inbox.json`
}

/**
 * The configured path stays the *v1* path. Durable ACK evidence lives in a deterministic v2
 * sibling so an old plugin binary that keeps writing `state.json` cannot erase the v2 record.
 * The lock is a sibling of the v2 snapshot (never the v1 file).
 */
export function inboxStatePaths(config = {}) {
  const configured = String(defaultStateFile(config))
  const v2 = configured.endsWith('.json')
    ? `${configured.slice(0, -'.json'.length)}.v2.json`
    : `${configured}.v2`
  return { configured, v2, lock: `${v2}.lock` }
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function isNonEmptyString(value) {
  return typeof value === 'string' && value.trim() !== ''
}

function isRevision(value) {
  return Number.isSafeInteger(value) && value >= 0
}

function isPositiveRevision(value) {
  return Number.isSafeInteger(value) && value > 0
}

const TARGET_FINGERPRINT_VERSION = 1
const TARGET_FINGERPRINT_PATTERN = /^[0-9a-f]{64}$/

/**
 * Canonical target binding. A URL that carries userinfo/query/fragment is rejected rather than
 * silently normalized: the endpoint builder only appends a path, so those components could route
 * to a different real endpoint. Only the clean protocol/host/port/path is hashed; the raw baseUrl
 * is never persisted or logged.
 */
function canonicalTarget(baseUrl) {
  const raw = String(baseUrl ?? '').trim()
  if (raw === '') return { error: 'config_target_unsupported' }
  let url
  try {
    url = new URL(raw)
  } catch {
    return { error: 'config_target_unsupported' }
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return { error: 'config_target_unsupported' }
  }
  if (url.username !== '' || url.password !== '' || url.search !== '' || url.hash !== '') {
    return { error: 'config_target_unsupported' }
  }
  const path = url.pathname.replace(/\/+$/u, '')
  const canonical = `${url.protocol}//${url.host}${path}`
  return {
    baseUrl: canonical,
    fingerprint: createHash('sha256').update(canonical).digest('hex'),
    version: TARGET_FINGERPRINT_VERSION,
  }
}

function validIds(value) {
  return Array.isArray(value)
    && value.length > 0
    && value.every(isNonEmptyString)
    && new Set(value).size === value.length
}

function validBatchShape(batch) {
  return isPlainObject(batch)
    && isNonEmptyString(batch.inboxId)
    && isNonEmptyString(batch.inboxIdentity)
    && (batch.targetSessionId === null || isNonEmptyString(batch.targetSessionId))
    && isRevision(batch.panelRevision)
    && isPositiveRevision(batch.pushRevision)
    && validIds(batch.itemIds)
    && (batch.createdAt === undefined || typeof batch.createdAt === 'string')
    && (batch.sourceTargetFingerprint === undefined
      || batch.sourceTargetFingerprint === null
      || (typeof batch.sourceTargetFingerprint === 'string'
        && TARGET_FINGERPRINT_PATTERN.test(batch.sourceTargetFingerprint)))
    && (batch.sourceTargetFingerprintVersion === undefined
      || batch.sourceTargetFingerprintVersion === null
      || batch.sourceTargetFingerprintVersion === TARGET_FINGERPRINT_VERSION)
}

function validPendingAck(pending) {
  return validBatchShape(pending)
    && (pending.status === 'pending' || pending.status === 'uncertain')
    && Number.isInteger(pending.attempts) && pending.attempts >= 0
    && (pending.lastError === null || typeof pending.lastError === 'string')
    && typeof pending.retryable === 'boolean'
    && (pending.lastAttemptAt === undefined || pending.lastAttemptAt === null
      || typeof pending.lastAttemptAt === 'string')
}

function validDeferredPush(value) {
  return isPlainObject(value)
    && isRevision(Number(value.revision))
    && isRevision(Number(value.pushRevision))
    && isNonEmptyString(value.reason)
    && typeof value.at === 'string'
}

function validPendingPush(value) {
  return isPlainObject(value)
    && isNonEmptyString(value.inboxId)
    && isNonEmptyString(value.inboxIdentity)
    && typeof value.sourceTargetFingerprint === 'string'
    && TARGET_FINGERPRINT_PATTERN.test(value.sourceTargetFingerprint)
    && value.sourceTargetFingerprintVersion === TARGET_FINGERPRINT_VERSION
    && (value.targetSessionId === null || isNonEmptyString(value.targetSessionId))
    && isRevision(value.panelRevision)
    && isPositiveRevision(value.pushRevision)
    && validIds(value.itemIds)
    && typeof value.createdAt === 'string'
}

function samePendingPush(left, right) {
  return left.inboxId === right.inboxId
    && left.inboxIdentity === right.inboxIdentity
    && left.sourceTargetFingerprint === right.sourceTargetFingerprint
    && left.sourceTargetFingerprintVersion === right.sourceTargetFingerprintVersion
    && left.targetSessionId === right.targetSessionId
    && left.panelRevision === right.panelRevision
    && left.pushRevision === right.pushRevision
    && left.itemIds.length === right.itemIds.length
    && left.itemIds.every((id, index) => id === right.itemIds[index])
}

function validFreshVerification(value) {
  return isPlainObject(value)
    && typeof value.required === 'boolean'
    && Number.isInteger(value.attempts) && value.attempts >= 0
    && (value.lastError === null || value.lastError === undefined
      || typeof value.lastError === 'string')
    && (value.lastAttemptAt === null || value.lastAttemptAt === undefined
      || typeof value.lastAttemptAt === 'string')
    && (value.nextAt === null || value.nextAt === undefined
      || Number.isFinite(value.nextAt))
    && (value.at === undefined || typeof value.at === 'string')
    && (value.inboxId === undefined || value.inboxId === null || isNonEmptyString(value.inboxId))
    && (value.sourceTargetFingerprint === undefined || value.sourceTargetFingerprint === null
      || (typeof value.sourceTargetFingerprint === 'string'
        && TARGET_FINGERPRINT_PATTERN.test(value.sourceTargetFingerprint)))
    && (value.sourceTargetFingerprintVersion === undefined
      || value.sourceTargetFingerprintVersion === null
      || value.sourceTargetFingerprintVersion === TARGET_FINGERPRINT_VERSION)
}

function validV2(snapshot) {
  if (!isPlainObject(snapshot) || snapshot.version !== STATE_VERSION) return false
  if (!(snapshot.targetSessionId === null || snapshot.targetSessionId === undefined
    || isNonEmptyString(snapshot.targetSessionId))) return false
  if (typeof snapshot.paused !== 'boolean' || typeof snapshot.stopped !== 'boolean') return false
  for (const field of ['injectedPushRevision', 'lastPushRevision', 'ackedRevision']) {
    if (!isRevision(snapshot[field])) return false
  }
  if (!(snapshot.lastSeenIdentity === null || snapshot.lastSeenIdentity === undefined
    || isNonEmptyString(snapshot.lastSeenIdentity))) return false
  if (snapshot.pendingAck !== null && snapshot.pendingAck !== undefined
    && !validPendingAck(snapshot.pendingAck)) return false
  if (snapshot.injectionIntent !== null && snapshot.injectionIntent !== undefined
    && !validBatchShape(snapshot.injectionIntent)) return false
  if (snapshot.identityMismatch !== null && snapshot.identityMismatch !== undefined) {
    if (!isPlainObject(snapshot.identityMismatch)
      || !isNonEmptyString(snapshot.identityMismatch.seen)) return false
  }
  if (snapshot.deferredPush !== null && snapshot.deferredPush !== undefined
    && !validDeferredPush(snapshot.deferredPush)) return false
  if (snapshot.freshVerification !== null && snapshot.freshVerification !== undefined
    && !validFreshVerification(snapshot.freshVerification)) return false
  if (snapshot.pendingPush !== null && snapshot.pendingPush !== undefined
    && !validPendingPush(snapshot.pendingPush)) return false
  return true
}

function validV1(snapshot) {
  return isPlainObject(snapshot)
    && snapshot.version === LEGACY_STATE_VERSION
    && (snapshot.targetSessionId === null || snapshot.targetSessionId === undefined
      || typeof snapshot.targetSessionId === 'string')
    && (snapshot.injectedPushRevision === undefined
      || isRevision(Number(snapshot.injectedPushRevision)))
    && (snapshot.lastPushRevision === undefined
      || isRevision(Number(snapshot.lastPushRevision)))
    && (snapshot.ackedRevision === undefined
      || isRevision(Number(snapshot.ackedRevision)))
}

function migrateV1(snapshot) {
  return {
    version: STATE_VERSION,
    targetSessionId: snapshot.targetSessionId ?? null,
    paused: Boolean(snapshot.paused),
    stopped: Boolean(snapshot.stopped),
    injectedPushRevision: Number(snapshot.injectedPushRevision ?? 0),
    lastPushRevision: Number(snapshot.lastPushRevision ?? 0),
    ackedRevision: Number(snapshot.ackedRevision ?? 0),
    lastMcpStartId: String(snapshot.lastMcpStartId ?? ''),
    appliedControlId: String(snapshot.appliedControlId ?? ''),
    restoredAt: snapshot.restoredAt ?? null,
    lastSeenIdentity: null,
    pendingAck: null,
    injectionIntent: null,
    identityMismatch: null,
    deferredPush: null,
    freshVerification: null,
    pendingPush: null,
    updatedAt: new Date().toISOString(),
  }
}

function readStateFile(path) {
  let raw
  try {
    raw = readFileSync(path, 'utf8')
  } catch (error) {
    if (error?.code === 'ENOENT') return { missing: true }
    return { error: 'state_unreadable' }
  }
  try {
    return { value: JSON.parse(raw) }
  } catch {
    return { error: 'state_corrupt' }
  }
}

/**
 * Atomically create the per-state-file lock. `wx` is the actual mutual-exclusion primitive:
 * a second writer loses the race and fails closed; a crash leaves the file behind and it is
 * never stolen automatically (the caller must verify no old instance remains and remove it).
 */
function acquireStateLock(path) {
  mkdirSync(dirname(path), { recursive: true })
  let fd
  try {
    fd = openSync(path, 'wx', 0o600)
  } catch (error) {
    if (error?.code === 'EEXIST') {
      const held = new Error('state_lock_held')
      held.code = 'state_lock_held'
      throw held
    }
    const unavailable = new Error('state_lock_unavailable')
    unavailable.code = 'state_lock_unavailable'
    throw unavailable
  }
  try {
    const token = randomUUID()
    const owner = JSON.stringify({
      pid: process.pid,
      host: hostname(),
      startedAt: new Date().toISOString(),
      token,
      stateFile: path,
    })
    writeFileSync(fd, owner)
    fsyncSync(fd)
    return { fd, path, token, owner }
  } catch (error) {
    try { closeSync(fd) } catch { /* already closed */ }
    try { unlinkSync(path) } catch { /* best effort */ }
    const ambiguous = new Error('state_lock_ambiguous')
    ambiguous.code = 'state_lock_ambiguous'
    throw ambiguous
  }
}

function readLockToken(path) {
  try {
    return JSON.parse(readFileSync(path, 'utf8'))?.token ?? null
  } catch {
    return null
  }
}

/** True only while the path still names the inode and token this client created. */
function lockStillOurs(lock) {
  if (!lock) return false
  try {
    const pathStat = statSync(lock.path)
    const fdStat = fstatSync(lock.fd)
    return pathStat.dev === fdStat.dev
      && pathStat.ino === fdStat.ino
      && readLockToken(lock.path) === lock.token
  } catch {
    return false
  }
}

/**
 * Only remove the lock when the path still contains OUR token. A replaced lock belongs to a
 * different writer; deleting it would break mutual exclusion for the new owner.
 */
function releaseStateLock(lock) {
  if (!lockStillOurs(lock)) {
    try { closeSync(lock.fd) } catch { /* already closed */ }
    return readLockToken(lock.path) === null ? 'state_lock_lost' : 'state_lock_replaced'
  }
  try {
    unlinkSync(lock.path)
  } catch (error) {
    try { closeSync(lock.fd) } catch { /* already closed */ }
    if (error?.code !== 'ENOENT') return 'state_lock_release_failed'
    return null
  }
  try { closeSync(lock.fd) } catch { /* already closed */ }
  return null
}

/** temp file with mode 0600 + fsync + rename; the previous snapshot stays intact on failure. */
function writeSnapshot(path, snapshot) {
  mkdirSync(dirname(path), { recursive: true })
  const serialized = JSON.stringify(snapshot)
  const temporary = `${path}.tmp-${process.pid}-${Math.random().toString(16).slice(2)}`
  let fd = null
  try {
    fd = openSync(temporary, 'w', 0o600)
    writeFileSync(fd, serialized)
    fsyncSync(fd)
    closeSync(fd)
    fd = null
    renameSync(temporary, path)
    try {
      const directory = openSync(dirname(path), 'r')
      try { fsyncSync(directory) } finally { closeSync(directory) }
    } catch { /* directory fsync is best effort on every platform */ }
  } catch (error) {
    if (fd !== null) {
      try { closeSync(fd) } catch { /* already closed */ }
    }
    try { unlinkSync(temporary) } catch { /* may not exist */ }
    throw error
  }
}

function panelEnvelope(panel) {
  const revision = Number(panel?.revision)
  const push = Number(panel?.pushRevision ?? revision)
  const ackedRevision = Number(panel?.ackedRevision ?? 0)
  const items = Array.isArray(panel?.items) ? panel.items : []
  if (!isRevision(revision) || !isRevision(push) || !isRevision(ackedRevision)) {
    return { error: 'panel_revision_invalid' }
  }
  return { revision, push, ackedRevision, items, empty: items.length === 0 }
}

function batchFromPanel(panel, targetSessionId, inboxId, targetFingerprint = null, {
  allowLiveSnapshot = false,
} = {}) {
  const revision = Number(panel?.revision)
  const push = Number(panel?.pushRevision ?? revision)
  const items = Array.isArray(panel?.items) ? panel.items : []
  const itemIds = items.map((item) => item?.id)
  if (!isRevision(revision)) return { error: 'invalid panel revision' }
  if (!isRevision(push)) return { error: 'invalid push revision' }
  if (items.length === 0) return { error: 'empty panel' }
  if (!isPositiveRevision(push)) return { error: 'panel has no push revision' }
  if (!itemIds.every(isNonEmptyString)) return { error: 'panel item id is missing or empty' }
  if (new Set(itemIds).size !== itemIds.length) return { error: 'duplicate panel item id' }
  if (allowLiveSnapshot) {
    // A live GET is a coherent current snapshot, not a push batch. Older servers may omit
    // frozenRevision entirely, and multiple earlier pushes may legitimately leave mixed positive
    // revisions in one unacked panel. Both are recoverable here; an explicitly unpushed item
    // (frozenRevision=0) or partially missing metadata is still rejected, and guarded ACK remains
    // the server-side authority for whether each item was really pushed.
    const frozenValues = items.map((item) => item?.frozenRevision)
    const supplied = frozenValues.filter((value) => value !== undefined && value !== null)
    if (supplied.length !== 0 && supplied.length !== items.length) {
      return { error: 'live panel mixes missing and present frozen metadata' }
    }
    if (supplied.length > 0
      && !supplied.every((value) => isPositiveRevision(Number(value)))) {
      return { error: 'live panel contains an explicitly unpushed item' }
    }
  } else {
    if (!items.every((item) => isPositiveRevision(Number(item?.frozenRevision)))) {
      return { error: 'panel item is not demonstrably frozen for a push' }
    }
    if (!items.every((item) => Number(item.frozenRevision) === push)) {
      return { error: 'panel batch mixes frozen revisions' }
    }
  }
  return {
    batch: {
      inboxId: String(inboxId || 'bma3'),
      inboxIdentity: String(panel.inboxIdentity).trim(),
      targetSessionId,
      panelRevision: revision,
      pushRevision: push,
      itemIds: [...itemIds],
      createdAt: new Date().toISOString(),
      sourceTargetFingerprint: targetFingerprint,
      sourceTargetFingerprintVersion: targetFingerprint ? TARGET_FINGERPRINT_VERSION : null,
    },
    revision,
    push,
    items,
  }
}

/** A live GET snapshot may only be used when every displayed item belongs to one newer push. */
function provableFreshPush(panel, targetFingerprint = null) {
  const shape = batchFromPanel(panel, null, panel?.inboxId, targetFingerprint)
  if (shape.error) return { ok: false, reason: shape.error }
  const { batch, items } = shape
  if (!items.every((item) => Number(item.frozenRevision) === batch.pushRevision)) {
    return { ok: false, reason: 'fresh panel mixes frozen revisions (cannot preserve one batch)' }
  }
  return { ok: true, batch: { ...batch, targetSessionId: null } }
}

/**
 * A live GET after ACK may recover a coherent current panel even when it spans multiple earlier
 * push revisions or comes from a server that predates frozenRevision. The ACK path still requires
 * the server to prove every item was frozen before accepting it.
 */
function provableLiveSnapshot(panel, targetFingerprint = null) {
  const shape = batchFromPanel(panel, null, panel?.inboxId, targetFingerprint, {
    allowLiveSnapshot: true,
  })
  if (shape.error) return { ok: false, reason: shape.error }
  return { ok: true, batch: { ...shape.batch, targetSessionId: null } }
}

function ackProof(body, pending) {
  if (!isPlainObject(body)) return { ok: false, reason: 'ACK response is not an object' }
  if (body.complete !== true) return { ok: false, reason: 'ACK response did not confirm complete=true' }
  if (!isNonEmptyString(body.inboxIdentity) || body.inboxIdentity !== pending.inboxIdentity) {
    return { ok: false, reason: 'ACK response identity echo does not match the stored identity' }
  }
  const arrays = [body.ackedItemIds, body.alreadyAckedItemIds, body.missingItemIds]
  if (!arrays.every((value) => Array.isArray(value) && value.every(isNonEmptyString))) {
    return { ok: false, reason: 'ACK response is missing per-item proof arrays' }
  }
  const [acked, already, missing] = arrays
  if (new Set(acked).size !== acked.length || new Set(already).size !== already.length) {
    return { ok: false, reason: 'ACK response contains duplicate item ids' }
  }
  if (missing.length > 0) return { ok: false, reason: 'ACK response reports missing item ids' }
  if (acked.some((id) => already.includes(id))) {
    return { ok: false, reason: 'ACK response reuses an id in both proof arrays' }
  }
  const requested = new Set(pending.itemIds)
  const union = new Set([...acked, ...already])
  if (union.size !== requested.size || [...requested].some((id) => !union.has(id))) {
    return { ok: false, reason: 'ACK response proof does not cover exactly the requested item ids' }
  }
  return { ok: true }
}

function retryableStatus(status) {
  return Number.isInteger(status) && (status >= 500 || status === 408 || status === 429)
}

/** Only known protocol codes may appear in durable/status text; anything else is classified. */
const SAFE_ACK_ERROR_CODES = new Set([
  'inbox_identity_mismatch',
  'inbox_identity_required',
  'inbox_identity_invalid',
  'item_missing',
  'item_not_pushed',
  'revision_conflict',
  'invalid_item_ids',
  'item_ids_required',
])

function safeAckHttpCode(status, body) {
  const code = typeof body?.error === 'string' ? body.error : ''
  return SAFE_ACK_ERROR_CODES.has(code)
    ? `ack_http_${status}_${code}`
    : `ack_http_${status}`
}

/**
 * Render one bounded panel as the wake text. Full history stays in the message board;
 * this text carries only source labels, relation markers and a short omitted count.
 */
export function renderPanel(panel) {
  const items = Array.isArray(panel?.items) ? panel.items : []
  const includeOmitted = panel?.includeOmittedCount !== false
  const total = includeOmitted && Number.isInteger(panel?.totalItems)
    ? panel.totalItems : items.length
  const lines = [
    `${WAKE_REVISION_HEADER} inbox=${panel?.inboxId ?? 'bma3'}`
      + ` rev=${panel?.revision ?? 0} push=${panel?.pushRevision ?? 0}`
      + ` unread=${total} items=${items.length}/${total}`,
  ]
  for (let position = 0; position < items.length; position++) {
    const item = items[position]
    const label = item?.sourceLabel ?? item?.source ?? 'unknown'
    const entries = Array.isArray(item?.entries) ? item.entries : []
    // The 1-based position is what inbox_ack(indices/range) expects; it is only valid for this
    // panel revision, which is exactly why the agent gets the revision in the header.
    const index = Number.isInteger(item?.index) ? item.index : position + 1
    const active = item?.continued ? ACTIVE_MARKER : ''
    lines.push(`[${label}] #${index} priority=${item?.priority ?? 0}`
      + ` total=${item?.totalMessages ?? entries.length} shown=${entries.length}${active}`)
    for (const entry of entries) {
      lines.push(`  ${entry?.line ?? fallbackLine(entry)}`)
    }
    if (includeOmitted && Number(item?.omittedMessages) > 0) {
      lines.push(`  …（省略 ${item.omittedMessages} 条）`)
    }
    if (Number(item?.truncatedChars) > 0) {
      lines.push(`  …（已截断 ${item.truncatedChars} 字）`)
    }
  }
  if (includeOmitted && Number(panel?.omittedItems) > 0) {
    lines.push(`…（另有 ${panel.omittedItems} 条未显示；需要时用 inbox_list 拉全部）`)
  }
  return lines.join('\n')
}

function fallbackLine(entry) {
  const seq = entry?.localSeq ?? 0
  const sender = entry?.sender ?? ''
  const text = entry?.text ?? ''
  const markers = Array.isArray(entry?.markers) ? entry.markers.join(' ') : ''
  return `#${seq} ${sender}：${text}${markers ? ` ${markers}` : ''}`
}

/** Minimal SSE frame parser; exposed for tests and deterministic reconnect handling. */
export class SseParser {
  constructor(onEvent) {
    this.onEvent = onEvent
    this.buffer = ''
  }

  push(chunk) {
    this.buffer += String(chunk ?? '')
    let index
    while ((index = this.buffer.search(/\r?\n\r?\n/)) >= 0) {
      const frame = this.buffer.slice(0, index)
      this.buffer = this.buffer.slice(index).replace(/^\r?\n\r?\n/, '')
      this.dispatch(frame)
    }
  }

  dispatch(frame) {
    let id = ''
    let event = 'message'
    const data = []
    for (const rawLine of frame.split(/\r?\n/)) {
      if (rawLine.startsWith(':')) continue
      const colon = rawLine.indexOf(':')
      const field = colon < 0 ? rawLine : rawLine.slice(0, colon)
      let value = colon < 0 ? '' : rawLine.slice(colon + 1)
      if (value.startsWith(' ')) value = value.slice(1)
      if (field === 'id') id = value
      else if (field === 'event') event = value
      else if (field === 'data') data.push(value)
    }
    if (data.length > 0) this.onEvent?.({ id, event, data: data.join('\n') })
  }
}

/**
 * One inbox = one SSE stream. Source items are just panel entries; there is no per-source
 * subscription.
 *
 * <p>0.0.7 adds a durable ACK state machine: an injection intent is persisted *before*
 * `followup`, a confirmed followup atomically becomes `pendingAck` plus the injection watermark,
 * and only a complete per-item ACK proof removes that record. If any durable step is uncertain
 * the client fails closed instead of repeating a cross-system side effect.</p>
 */
export function createInboxClient(ctx, config = {}, resolveSessionQuery = () => ctx?.get?.('sessionQuery')) {
  const fetchImpl = config.fetchImpl ?? fetch
  const paths = inboxStatePaths(config)
  const stateFile = paths.v2
  const persistenceEnabled = config.persistState !== false

  let lock = null
  let lockError = null
  let stateError = null
  let loaded = null
  if (persistenceEnabled) {
    try {
      lock = acquireStateLock(paths.lock)
    } catch (error) {
      lockError = error?.code === 'state_lock_held'
        ? 'state_lock_held'
        : (error?.code === 'state_lock_ambiguous' ? 'state_lock_ambiguous' : 'state_lock_unavailable')
    }
    if (!lockError) {
      const v2 = readStateFile(paths.v2)
      if (v2.missing) {
        const v1 = readStateFile(paths.configured)
        if (v1.missing) {
          loaded = null
        } else if (v1.error) {
          stateError = `v1 state ${v1.error}: ${paths.configured}`
        } else if (!validV1(v1.value)) {
          stateError = `v1 state unsupported/corrupt: ${paths.configured}`
        } else {
          loaded = migrateV1(v1.value)
        }
      } else if (v2.error) {
        stateError = `v2 state ${v2.error}: ${paths.v2}`
      } else if (!validV2(v2.value)) {
        stateError = `v2 state unsupported/corrupt: ${paths.v2}`
      } else {
        loaded = v2.value
      }
    }
  }

  const initial = loaded ?? {}
  const state = {
    connection: 'idle',
    lastRevision: 0,
    injectedRevision: 0,
    injectedPushRevision: Number(initial.injectedPushRevision ?? 0),
    ackedRevision: Number(initial.ackedRevision ?? 0),
    lastPushRevision: Number(initial.lastPushRevision ?? 0),
    pendingRevision: 0,
    lastEventAt: null,
    lastError: null,
    targetSessionId: initial.targetSessionId
      ?? (config.sessionId ? String(config.sessionId) : null),
    paused: Boolean(initial.paused),
    stopped: Boolean(initial.stopped),
    lastMcpStartId: String(initial.lastMcpStartId ?? ''),
    appliedControlId: String(initial.appliedControlId ?? ''),
    restoredAt: initial.restoredAt ?? null,
    // Durable database identity and recovery records (never message body or credentials).
    lastSeenIdentity: initial.lastSeenIdentity ?? null,
    pendingAck: initial.pendingAck ?? null,
    injectionIntent: initial.injectionIntent ?? null,
    identityMismatch: initial.identityMismatch ?? null,
    deferredPush: initial.deferredPush ?? null,
    freshVerification: initial.freshVerification ?? null,
    pendingPush: initial.pendingPush ?? null,
    // Runtime-only diagnostics.
    lockError,
    stateError,
    persistenceEnabled,
    persistenceError: null,
    hasSnapshot: Boolean(loaded),
    serverMissingIdentity: false,
    ackIdentityVerified: false,
    recentAcked: [],
    targetError: canonicalTarget(config.baseUrl).error ?? null,
  }

  let lastPersisted = ''
  let stopped = true
  let abort = null
  let pending = null
  let pendingSource = null
  let pendingPushMismatchKey = null
  let pendingPushMismatchCode = null
  // Set only by an explicit `/inbox ack` (or tool ack) while a pendingPush is blocked. It lets
  // that ONE manual recovery adopt the processId of the currently answering server as the new
  // instance baseline, then clears immediately. It never loosens the exact identity/target/
  // session/revision/IDs/frozen match below, and it cannot detect a same-database clone/rollback
  // (a copied DB served by a new process is indistinguishable from a legitimate restart).
  let pendingPushRebaseline = false
  // Runtime-only report of what the last `ack` call actually did, so the command/tool surfaces
  // never claim a retry that was stopped/in-flight/unpersistable.
  let lastAckResult = null
  // Instance proofs are runtime-only: /runtime processId changes on every server start, so it is
  // never persisted as a durable identity. It only proves "the same server process that produced
  // this panel/ACK is still answering" while this plugin process has that evidence in memory.
  let connectionProcessId = null
  let pendingAckProcessId = null
  let pendingPushProcessId = null
  let retryTimer = null
  let pumpTimer = null
  let heartbeatTimer = null
  let injecting = false
  let resuming = null
  let resumeHandle = null
  let retryMs = Math.max(200, Number(config.reconnectMinMs ?? 1000))
  const pumpMs = Math.max(100, Number(config.pumpMs ?? 500))
  const heartbeatMs = Math.max(1000, Number(config.heartbeatMs ?? 15000))
  let ackTimer = null
  let ackInFlight = false
  let ackNextAt = 0
  let ackRetryRequested = false
  let ackRetryDelay = 0
  let ackAbort = null
  let ackCancelEpoch = 0
  let ackSuspended = false
  let disposed = false
  let toolRegistration = null
  let agentToolRegistrar = null
  let generation = 0
  let controlEpoch = 0
  let connectionEpoch = 0
  let verifying = false
  let verifyTimer = null
  let verifyNextAt = 0
  let verifyAbort = null
  let identityAbort = null
  let runtimeAbort = null
  let verifyRetryRequested = false
  let verifyRetryDelay = 0

  const targetContext = () => {
    const target = canonicalTarget(config.baseUrl)
    state.targetError = target.error ?? null
    return target
  }

  const requestUrl = (inboxId, suffix = '') => {
    const target = targetContext()
    if (target.error) return null
    return `${target.baseUrl}/v1/inboxes/${encodeURIComponent(inboxId ?? config.inboxId ?? 'bma3')}${suffix}`
  }

  // The rolling context copy (D-079): the host already wrote every event to its session log; this
  // side only reads it and forwards a bounded slice. No model is called and nothing is generated —
  // `extractSessionEventText` is the host's own renderer, so the copy says exactly what the model
  // was told, in the host's own words. Without `sessionQuery` (older host, minimal profile) the
  // pusher stays silent and the jar's copy simply stops ageing.
  const contextPusher = createContextPusher({
    resolveSessionQuery,
    resolveExtractText: () => loadEventTextExtractor(),
    fetchImpl,
    url: (suffix) => requestUrl(config.inboxId, suffix),
    headers: () => authHeaders(config),
    sessionId: () => state.targetSessionId ?? '',
    limits: {
      maxBatch: config.contextMaxBatch,
      maxEntries: config.contextMaxEntries,
      maxEntryChars: config.contextMaxEntryChars,
    },
  })

  const buildSnapshot = () => ({
    version: STATE_VERSION,
    targetSessionId: state.targetSessionId,
    paused: state.paused,
    // Persist the USER INTENT, never the runtime engine flag: teardown calls stop(false),
    // which flips the closure `stopped` but must not look like "the user pressed stop" on the
    // next load — that silently disabled the inbox after every plugin reload.
    stopped: state.stopped,
    injectedPushRevision: state.injectedPushRevision,
    lastPushRevision: state.lastPushRevision,
    ackedRevision: state.ackedRevision,
    lastMcpStartId: state.lastMcpStartId,
    appliedControlId: state.appliedControlId,
    restoredAt: state.restoredAt,
    lastSeenIdentity: state.lastSeenIdentity,
    pendingAck: state.pendingAck,
    injectionIntent: state.injectionIntent,
    identityMismatch: state.identityMismatch,
    deferredPush: state.deferredPush,
    freshVerification: state.freshVerification,
    pendingPush: state.pendingPush,
    updatedAt: new Date().toISOString(),
  })

  const persist = () => {
    if (disposed) return false
    if (!persistenceEnabled) return false
    if (state.lockError || state.stateError) return false
    if (!lockStillOurs(lock)) {
      state.persistenceError = 'state_lock_lost'
      state.lastError = 'state_lock_lost'
      return false
    }
    const snapshot = buildSnapshot()
    const serialized = JSON.stringify(snapshot)
    if (serialized === lastPersisted) return true
    try {
      writeSnapshot(stateFile, snapshot)
      lastPersisted = serialized
      state.hasSnapshot = true
      state.persistenceError = null
      return true
    } catch (error) {
      state.persistenceError = 'state_write_failed'
      state.lastError = 'state_write_failed'
      return false
    }
  }

  const blockedReason = () => {
    const reasons = []
    if (!persistenceEnabled) {
      reasons.push('persistence disabled: inbox injection and ACK are disabled')
    }
    if (state.lockError) reasons.push(state.lockError)
    if (state.stateError) reasons.push(state.stateError)
    if (persistenceEnabled && !state.hasSnapshot && !state.lockError && !state.stateError) {
      reasons.push('state snapshot could not be persisted; refusing to inject or ACK')
    }
    if (state.injectionIntent) {
      reasons.push('injection intent is uncertain (followup may have happened); manual discard required')
    }
    if (state.identityMismatch) {
      reasons.push(`inbox identity changed from ${state.identityMismatch.previous ?? 'unknown'}`
        + ` to ${state.identityMismatch.seen}; manual accept required`)
    }
    if (state.serverMissingIdentity) {
      reasons.push('server panel has no inboxIdentity (old server): no followup and no ACK')
    }
    const currentTarget = canonicalTarget(config.baseUrl)
    if (currentTarget.error) reasons.push(currentTarget.error)
    return reasons.length > 0 ? reasons.join('; ') : null
  }

  // The first durable write is the initial snapshot (or the v1 -> v2 migration). A failure here
  // means there is no durable state at all, so the client must not inject.
  if (persistenceEnabled && !state.lockError && !state.stateError) {
    if (!persist()) state.hasSnapshot = false
  }

  const clearAckTimer = () => {
    if (ackTimer) clearTimeout(ackTimer)
    ackTimer = null
  }

  const status = () => ({
    ...state,
    pluginVersion: PLUGIN_VERSION,
    toolRegistration,
    pendingRevision: pending?.revision ?? 0,
    connected: state.connection === 'open',
    running: !stopped,
    stopped: state.stopped,
    agentLive: Boolean(findAgent()),
    lastAckResult,
    stateFile,
    stateFileV1: paths.configured,
    lockFile: paths.lock,
    blockedReason: blockedReason(),
    targetError: canonicalTarget(config.baseUrl).error ?? null,
    persistence: {
      enabled: persistenceEnabled,
      lockHeld: lockStillOurs(lock),
      error: state.lockError ?? state.stateError ?? state.persistenceError,
    },
    // 配置只报来源，不回显 token 值或文件路径中的凭据。
    config: {
      baseUrl: canonicalTarget(config.baseUrl).error ? null : canonicalTarget(config.baseUrl).baseUrl,
      targetError: canonicalTarget(config.baseUrl).error ?? null,
      inboxId: config.inboxId ?? 'bma3',
      autoStart: config.autoStart !== false,
      tokenSource: tokenSource(config),
      sessionId: config.sessionId ? String(config.sessionId) : null,
    },
  })

  /**
   * A bound target must never fall back to "some other idle agent": injecting the wrong session
   * is worse than waiting. Unbound mode keeps the old "first idle" behaviour.
   */
  const findAgent = () => {
    const agents = ctx?.agents
    if (state.targetSessionId) return agents?.get?.(state.targetSessionId)
    return agents?.list?.().find((candidate) => candidate?.status === 'idle')
  }

  /**
   * Persist the target chosen for an otherwise-unbound client before followup. A pending push
   * created while the client was unbound carries targetSessionId=null; attach it to the same
   * transaction so a restart cannot recover the push without knowing where it was meant to go.
   */
  const adoptRuntimeBinding = (targetSessionId) => {
    if (!isNonEmptyString(targetSessionId)) {
      state.lastError = 'target_session_unresolved'
      return false
    }
    if (state.targetSessionId && state.targetSessionId !== targetSessionId) {
      state.lastError = 'bound_target_mismatch'
      return false
    }
    if (state.pendingPush?.targetSessionId
      && state.pendingPush.targetSessionId !== targetSessionId) {
      state.lastError = 'pending_push_session_mismatch'
      return false
    }
    const attachPending = Boolean(state.pendingPush && !state.pendingPush.targetSessionId)
    const bindNow = !state.targetSessionId
    if (!bindNow && !attachPending) return true
    const previousTarget = state.targetSessionId
    const previousPendingPush = state.pendingPush
    state.targetSessionId = targetSessionId
    if (attachPending) {
      state.pendingPush = { ...state.pendingPush, targetSessionId }
    }
    if (!persist()) {
      state.targetSessionId = previousTarget
      state.pendingPush = previousPendingPush
      state.lastError = 'auto_bind_persist_failed'
      return false
    }
    return true
  }

  /** Resume a persisted session when it is not live yet (DSH restart). */
  const ensureAgent = async (sessionId) => {
    if (disposed) return null
    const operation = generation
    const controlAtStart = controlEpoch
    const agents = ctx?.agents
    if (!agents || !sessionId) return null
    const live = agents.get?.(sessionId)
    if (live) return live
    if (typeof agents.resume !== 'function') return null
    if (resuming) return resuming
    resuming = (async () => {
      try {
        const handle = await agents.resume({ resumeSessionId: sessionId })
        if (disposed || generation !== operation || controlEpoch !== controlAtStart) {
          Promise.resolve(handle?.dispose?.()).catch(() => {})
          return null
        }
        if (handle) {
          resumeHandle = handle
          ctx?.effect?.(() => () => {
            Promise.resolve(handle?.dispose?.()).catch(() => {})
          })
        }
        return handle?.agent ?? agents.get?.(sessionId) ?? null
      } catch (error) {
        if (!disposed && generation === operation) state.lastError = 'resume_failed'
        return null
      } finally {
        resuming = null
      }
    })()
    return resuming
  }

  const remind = async (text) => {
    if (!text || disposed || (persistenceEnabled && !lockStillOurs(lock))) return true
    const operation = generation
    const agent = findAgent()
    if (!agent || agent.status !== 'idle' || typeof agent.followup !== 'function') {
      return false
    }
    try {
      await agent.followup({
        id: `bma3-inbox-remind-${Date.now()}`,
        role: 'user',
        content: [{ type: 'text', text }],
        source: { kind: 'bma3-inbox', reminder: true },
      })
      return !(disposed || generation !== operation)
    } catch (error) {
      if (!disposed && generation === operation) state.lastError = 'remind_failed'
      return false
    }
  }

  /** Remember the database identity; a change stops all automatic action until accepted. */
  const noteIdentity = (panel) => {
    const identity = typeof panel?.inboxIdentity === 'string' ? panel.inboxIdentity.trim() : ''
    if (!identity) {
      state.serverMissingIdentity = true
      state.ackIdentityVerified = false
      state.lastError = 'panel: inboxIdentity missing (old server); refusing to inject or ACK'
      return false
    }
    state.serverMissingIdentity = false
    if (!state.lastSeenIdentity) {
      state.lastSeenIdentity = identity
      state.ackIdentityVerified = true
      persist()
      return true
    }
    if (state.lastSeenIdentity !== identity) {
      if (state.identityMismatch?.seen === identity) return false
      state.identityMismatch = {
        previous: state.lastSeenIdentity,
        seen: identity,
        at: new Date().toISOString(),
      }
      state.ackIdentityVerified = false
      state.lastError = `inbox identity changed: ${state.lastSeenIdentity} -> ${identity};`
        + ' automatic injection/ACK stopped until /inbox accept-identity confirm'
      persist()
      return false
    }
    state.ackIdentityVerified = true
    return true
  }

  const applyControl = async (control) => {
    if (disposed) return false
    if (!control || !control.id || control.id === state.appliedControlId) return true
    const operation = generation
    let controlAtStart = controlEpoch
    let applied = true
    if (control.action === 'restore') {
      const target = String(control.sessionId ?? state.targetSessionId ?? '').trim()
      if (!target) {
        state.lastError = 'control_empty_session'
        return false
      }
      const targetConfig = targetContext()
      if (targetConfig.error) {
        state.lastError = targetConfig.error
        return false
      }
      const previous = {
        targetSessionId: state.targetSessionId,
        stopped: state.stopped,
        ackSuspended,
        pending,
      }
      state.targetSessionId = target
      ackSuspended = false
      pending = null
      if (stopped) state.stopped = false
      if (persistenceEnabled && !persist()) {
        state.targetSessionId = previous.targetSessionId
        state.stopped = previous.stopped
        ackSuspended = previous.ackSuspended
        pending = previous.pending
        state.lastError = `control_state_write_failed：${state.persistenceError ?? 'state_write_failed'}`
        return false
      }
      controlAtStart = ++controlEpoch
      const agent = await ensureAgent(target)
      if (disposed || generation !== operation || controlEpoch !== controlAtStart) return false
      if (!agent) {
        // Keep the SSE up anyway: the session may become live later (e.g. the user opens it),
        // and the binding must already be attached. The control stays pending for a retry.
        applied = false
        state.lastError = 'resume_failed'
      } else {
        state.restoredAt = new Date().toISOString()
      }
      startEngine()
      if (applied && control.remind) {
        applied = await remind(control.remind)
      }
      if (disposed || generation !== operation || controlEpoch !== controlAtStart) return false
    } else if (control.action === 'recover') {
      try {
        recoverFromPendingPush(true)
        applied = true
      } catch (error) {
        state.lastError = `control_recover_failed: ${error?.message ?? error}`
        applied = false
      }
    } else if (control.action === 'remind') {
      // A reminder does not supersede an in-flight followup; do not advance controlEpoch.
      applied = await remind(control.remind)
      if (disposed || generation !== operation) return false
    } else {
      // Unknown actions must not advance the epoch or be marked applied.
      state.lastError = 'control_unknown'
      return false
    }
    if (applied) {
      const previousControl = state.appliedControlId
      state.appliedControlId = control.id
      if (persistenceEnabled && !persist()) {
        state.appliedControlId = previousControl
        state.lastError = 'control_state_write_failed'
        return false
      }
    }
    return applied
  }

  const heartbeatDiagnostics = () => {
    const short = (value, limit = 160) => {
      const text = String(value ?? '')
      return text.length <= limit ? text : text.slice(0, limit) + '…'
    }
    const deferred = state.deferredPush
    const fresh = state.freshVerification
    return {
      version: PLUGIN_VERSION,
      toolRegistration: toolRegistration
        ? {
            registered: toolRegistration.registered === true,
            attempts: Number(toolRegistration.attempts ?? 0),
            lastError: toolRegistration.lastError ? short(toolRegistration.lastError) : null,
            scopes: Array.isArray(toolRegistration.scopes)
              ? toolRegistration.scopes.slice(0, 4).map((scope) => short(scope, 80)) : [],
            at: toolRegistration.at ?? null,
          }
        : null,
      deferredPush: deferred
        ? {
            revision: Number(deferred.revision ?? 0),
            pushRevision: Number(deferred.pushRevision ?? 0),
            reason: short(deferred.reason),
            at: deferred.at ?? null,
          }
        : null,
      freshVerification: fresh
        ? {
            required: fresh.required === true,
            attempts: Number(fresh.attempts ?? 0),
            lastError: fresh.lastError ? short(fresh.lastError) : null,
            nextAt: Number.isFinite(fresh.nextAt) ? fresh.nextAt : null,
            lastAttemptAt: fresh.lastAttemptAt ?? null,
          }
        : null,
      pendingAckItems: Array.isArray(state.pendingAck?.itemIds)
        ? state.pendingAck.itemIds.length : 0,
      pendingPush: state.pendingPush
        ? {
            targetSessionId: state.pendingPush.targetSessionId ?? null,
            panelRevision: Number(state.pendingPush.panelRevision ?? 0),
            pushRevision: Number(state.pendingPush.pushRevision ?? 0),
            itemCount: Array.isArray(state.pendingPush.itemIds)
              ? state.pendingPush.itemIds.length : 0,
          }
        : null,
      // Rolling context copy (D-079): counters only — the jar stores the bodies, the heartbeat
      // never carries one (`PluginHeartbeat`: "Only ids/status/counters are carried").
      context: contextPusher.snapshot(),
      recentAcked: Array.isArray(state.recentAcked) ? state.recentAcked.length : 0,
    }
  }

  const heartbeat = async () => {
    if (disposed || (stopped && !state.targetSessionId)) return null
    if (persistenceEnabled && !lockStillOurs(lock)) {
      state.persistenceError = 'state_lock_lost'
      return null
    }
    const operation = generation
    const controlAtStart = controlEpoch
    const url = requestUrl(config.inboxId, '/plugin/heartbeat')
    if (!url) return null
    try {
      const response = await fetchImpl(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...authHeaders(config),
        },
        body: JSON.stringify({
          sessionId: state.targetSessionId ?? '',
          bound: Boolean(state.targetSessionId),
          sse: state.connection,
          lastPushRevision: state.injectedPushRevision,
          lastMcpStartId: state.lastMcpStartId,
          lastError: state.lastError ?? '',
          appliedControlId: state.appliedControlId,
          tokenSource: tokenSource(config),
          tokenOk: state.lastAuthFailed !== true,
          diagnostics: heartbeatDiagnostics(),
        }),
      })
      if (disposed || generation !== operation || controlEpoch !== controlAtStart) return null
      if (response.status === 401) {
        state.lastAuthFailed = true
      } else if (response.ok) {
        state.lastAuthFailed = false
      }
      if (!response.ok) {
        state.lastError = `heartbeat: HTTP ${response.status}`
        return null
      }
      const body = await response.json()
      if (disposed || generation !== operation || controlEpoch !== controlAtStart) return null
      if (body?.mcpStartId && body.mcpStartId !== state.lastMcpStartId) {
        const previous = state.lastMcpStartId
        state.lastMcpStartId = String(body.mcpStartId)
        if (previous) {
          // BMA3 restarted: the DSH MCP client may still hold the old tool list.
          await remind('BMA3 已重启。如果 qq_*/inbox_* MCP 工具不可用，请先重连 MCP'
            + '（mcp_connector_reconnect），再继续处理 inbox。')
          if (disposed || generation !== operation || controlEpoch !== controlAtStart) return null
        }
        persist()
      }
      if (body?.control) {
        await applyControl(body.control)
      }
      // Roll the context copy forward on the heartbeat cadence: the beat that proves this plugin is
      // alive is also the one that says what it can see. Fire-and-forget — a slow session log must
      // not delay the heartbeat, and the pusher keeps its own watermark when a push fails.
      void contextPusher.tick()
      return body
    } catch (error) {
      if (!disposed && generation === operation) state.lastError = 'heartbeat_failed'
      return null
    }
  }

  const timeoutError = () => Object.assign(new Error('timeout'), { name: 'TimeoutError' })

  /** A timer race that never depends on the underlying promise honoring AbortSignal. */
  const withHardTimeout = (promise, timeoutMs) => {
    let timer = null
    const timeout = new Promise((_resolve, reject) => {
      timer = setTimeout(() => reject(timeoutError()), Math.max(1, timeoutMs))
      timer.unref?.()
    })
    promise.catch(() => {})
    return Promise.race([promise, timeout]).finally(() => {
      if (timer) clearTimeout(timer)
    })
  }

  /**
   * The timeout covers both the fetch and response.json(). A body that ignores abort cannot hang
   * the caller: the hard timer rejects independently. The controller is kept registered until the
   * body is read so stop/dispose can still abort the network resource.
   */
  const fetchJsonWithTimeout = async (url, init, timeoutMs, kind) => {
    const controller = new AbortController()
    if (kind === 'ack') ackAbort = controller
    else if (kind === 'identity') identityAbort = controller
    else if (kind === 'runtime') runtimeAbort = controller
    else verifyAbort = controller
    try {
      const response = await withHardTimeout(
        fetchImpl(url, { ...init, signal: controller.signal }),
        timeoutMs,
      )
      let body = null
      try {
        body = await withHardTimeout(response.json(), timeoutMs)
      } catch (error) {
        if (error?.name === 'TimeoutError') {
          controller.abort()
          throw error
        }
        body = null
      }
      return { response, body }
    } catch (error) {
      controller.abort()
      throw error
    } finally {
      if (kind === 'ack' && ackAbort === controller) ackAbort = null
      if (kind === 'identity' && identityAbort === controller) identityAbort = null
      if (kind === 'runtime' && runtimeAbort === controller) runtimeAbort = null
      if (kind === 'verify' && verifyAbort === controller) verifyAbort = null
    }
  }

  const ackRetryDelayFor = (attempts) => {
    const base = Math.max(10, Number(config.ackRetryBaseMs ?? DEFAULT_ACK_RETRY_BASE_MS))
    const cap = Math.max(base, Number(config.ackRetryMaxMs ?? DEFAULT_ACK_RETRY_MAX_MS))
    return Math.min(base * (2 ** Math.max(0, attempts - 1)), cap)
  }

  const requestAckRetry = (attempts) => {
    const delay = ackRetryDelayFor(attempts)
    ackNextAt = Date.now() + delay
    if (ackInFlight) {
      ackRetryRequested = true
      ackRetryDelay = delay
    } else {
      scheduleAck(delay)
    }
  }

  const scheduleAck = (delay) => {
    if (ackSuspended || disposed || stopped) return
    if (ackTimer || ackInFlight) return
    if (!state.pendingAck || state.injectionIntent) return
    if (state.lockError || state.stateError || !persistenceEnabled) return
    if (!lockStillOurs(lock)) {
      state.persistenceError = 'state_lock_lost'
      return
    }
    if (state.identityMismatch) return
    const pendingAck = state.pendingAck
    if (pendingAck.retryable === false) return
    const maxAttempts = Math.max(0, Number(config.ackMaxAttempts ?? 0))
    if (maxAttempts > 0 && pendingAck.attempts >= maxAttempts) {
      pendingAck.retryable = false
      pendingAck.lastError = pendingAck.lastError ?? 'retry_budget_exhausted'
      persist()
      return
    }
    const wait = delay === undefined ? Math.max(0, ackNextAt - Date.now()) : Math.max(0, delay)
    ackNextAt = Date.now() + wait
    ackTimer = setTimeout(() => {
      ackTimer = null
      void attemptAck()
    }, wait)
    ackTimer.unref?.()
  }

  const resolvePendingAck = async (pendingAck, body) => {
    const previous = {
      pendingAck: state.pendingAck,
      injectionIntent: state.injectionIntent,
      injectedPushRevision: state.injectedPushRevision,
      lastPushRevision: state.lastPushRevision,
      ackedRevision: state.ackedRevision,
      lastError: state.lastError,
      freshVerification: state.freshVerification,
      pendingPush: state.pendingPush,
    }
    const previousPendingPushProcessId = pendingPushProcessId
    const previousMismatchKey = pendingPushMismatchKey
    const previousMismatchCode = pendingPushMismatchCode
    const acknowledgedRevision = body?.ackedRevision
    state.pendingAck = null
    state.injectionIntent = null
    state.pendingPush = null
    pendingPushProcessId = null
    pendingPushMismatchKey = null
    pendingPushMismatchCode = null
    state.injectedPushRevision = Math.max(state.injectedPushRevision, pendingAck.pushRevision)
    state.lastPushRevision = Math.max(state.lastPushRevision, pendingAck.pushRevision)
    state.ackedRevision = isRevision(acknowledgedRevision)
      ? acknowledgedRevision : state.ackedRevision
    state.lastError = null
    state.recentAcked = [...pendingAck.itemIds].slice(-200)
    state.freshVerification = {
      required: true,
      attempts: 0,
      lastError: null,
      lastAttemptAt: null,
      nextAt: null,
      at: new Date().toISOString(),
      inboxId: pendingAck.inboxId,
      sourceTargetFingerprint: pendingAck.sourceTargetFingerprint ?? null,
      sourceTargetFingerprintVersion: pendingAck.sourceTargetFingerprintVersion ?? null,
    }
    if (!persist()) {
      Object.assign(state, previous)
      pendingPushProcessId = previousPendingPushProcessId
      pendingPushMismatchKey = previousMismatchKey
      pendingPushMismatchCode = previousMismatchCode
      state.pendingAck = {
        ...pendingAck,
        attempts: pendingAck.attempts + 1,
        retryable: true,
        lastError: state.persistenceError ?? 'state removal failed',
      }
      state.lastError = 'state_removal_failed'
      requestAckRetry(state.pendingAck.attempts)
      return false
    }
    scheduleVerify(0)
    return true
  }

  const invalidateAckTarget = (pendingAck, code) => {
    state.ackIdentityVerified = false
    pendingAck.lastError = code
    pendingAck.retryable = false
    state.lastError = code
    persist()
    return false
  }

  /**
   * Target configuration is part of the ACK identity. A changed inboxId/baseUrl (or an unsupported
   * URL with userinfo/query/fragment) must never be redirected to the old or new endpoint.
   */
  const validateAckTarget = (pendingAck) => {
    const target = targetContext()
    if (target.error) return invalidateAckTarget(pendingAck, target.error)
    if (String(config.inboxId ?? 'bma3') !== pendingAck.inboxId) {
      return invalidateAckTarget(pendingAck, 'pending_inbox_id_mismatch')
    }
    if (!pendingAck.sourceTargetFingerprint) {
      return invalidateAckTarget(pendingAck, 'pending_target_unverified')
    }
    if (pendingAck.sourceTargetFingerprintVersion !== TARGET_FINGERPRINT_VERSION) {
      return invalidateAckTarget(pendingAck, 'pending_target_unverified')
    }
    if (target.fingerprint !== pendingAck.sourceTargetFingerprint) {
      return invalidateAckTarget(pendingAck, 'pending_base_url_mismatch')
    }
    return true
  }

  /**
   * Before the first ACK after a restart the plugin must prove the *current* server still has the
   * durable identity stored in the pending record. Without this, a downgraded/old server that
   * ignores `inboxIdentity` could accept a legacy ACK. Missing identity => zero ACKs.
   */
  const verifyServerIdentity = async () => {
    if (state.ackIdentityVerified) return true
    if (disposed || stopped || ackSuspended) return false
    const operation = generation
    const controlAtStart = controlEpoch
    const pendingAck = state.pendingAck
    if (!pendingAck) return false
    const url = requestUrl(pendingAck.inboxId, '/panel')
    if (!url) return false
    try {
      const { response, body: panel } = await fetchJsonWithTimeout(url, {
        method: 'GET',
        headers: {
          Accept: 'application/json',
          ...authHeaders(config),
        },
      }, Number(config.panelTimeoutMs ?? 10000), 'identity')
      if (disposed || stopped || ackSuspended || generation !== operation
        || controlEpoch !== controlAtStart) return false
      if (!response.ok) {
        state.lastError = `ack_identity_http_${response.status}`
        return false
      }
      if (!panel) {
        state.lastError = 'ack_identity_invalid'
        return false
      }
      if (!noteIdentity(panel)) return false
      state.ackIdentityVerified = true
      return true
    } catch (error) {
      if (!disposed && !stopped && !ackSuspended && generation === operation) {
        state.lastError = error?.name === 'TimeoutError'
          ? 'ack_identity_timeout' : 'ack_identity_failed'
      }
      return false
    }
  }

  const attemptAck = async () => {
    if (ackInFlight || ackSuspended || stopped || disposed) return
    if (!state.pendingAck || state.injectionIntent) return
    if (state.lockError || state.stateError || !persistenceEnabled) return
    if (!lockStillOurs(lock)) {
      state.persistenceError = 'state_lock_lost'
      return
    }
    if (state.identityMismatch) return
    clearAckTimer()
    const operation = generation
    const controlAtStart = controlEpoch
    const cancelAtStart = ackCancelEpoch
    ackInFlight = true
    const pendingAck = state.pendingAck
    const stale = () => disposed || ackSuspended || generation !== operation
      || controlEpoch !== controlAtStart || ackCancelEpoch !== cancelAtStart
      || state.pendingAck !== pendingAck
    try {
      if (!validateAckTarget(pendingAck)) return
      if (!state.ackIdentityVerified) {
        const verified = await verifyServerIdentity()
        if (stale()) return
        if (!verified) {
          pendingAck.attempts += 1
          pendingAck.lastAttemptAt = new Date().toISOString()
          pendingAck.retryable = true
          pendingAck.lastError = state.lastError ?? 'ack_identity_unverified'
          state.lastError = pendingAck.lastError
          persist()
          requestAckRetry(pendingAck.attempts)
          return
        }
      }
      if (!validateAckTarget(pendingAck)) return
      const payload = {
        revision: pendingAck.panelRevision,
        itemIds: [...pendingAck.itemIds],
        inboxIdentity: pendingAck.inboxIdentity,
      }
      const url = requestUrl(pendingAck.inboxId, '/ack')
      if (!url) {
        invalidateAckTarget(pendingAck, 'config_target_unsupported')
        return
      }
      let response
      let body
      try {
        ({ response, body } = await fetchJsonWithTimeout(url, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            ...authHeaders(config),
          },
          body: JSON.stringify(payload),
        }, Number(config.ackTimeoutMs ?? 10000), 'ack'))
      } catch (error) {
        if (stale()) return
        pendingAck.attempts += 1
        pendingAck.lastAttemptAt = new Date().toISOString()
        pendingAck.retryable = true
        pendingAck.lastError = error?.name === 'TimeoutError' ? 'ack_timeout' : 'ack_network'
        state.lastError = pendingAck.lastError
        persist()
        requestAckRetry(pendingAck.attempts)
        return
      }
      if (stale()) return
      if (!response.ok) {
        if (stale()) return
        pendingAck.attempts += 1
        pendingAck.lastAttemptAt = new Date().toISOString()
        pendingAck.lastError = safeAckHttpCode(response.status, body)
        pendingAck.retryable = retryableStatus(response.status)
        state.lastError = pendingAck.lastError
        if (response.status === 409 && body?.error === 'inbox_identity_mismatch') {
          const seen = isNonEmptyString(body?.inboxIdentity) ? body.inboxIdentity : null
          if (seen && seen !== pendingAck.inboxIdentity) {
            state.identityMismatch = {
              previous: pendingAck.inboxIdentity,
              seen,
              at: new Date().toISOString(),
            }
          }
        }
        persist()
        if (pendingAck.retryable) requestAckRetry(pendingAck.attempts)
        return
      }
      if (body === null) {
        // 200 but the body was lost/unreadable: this is indistinguishable from a lost response,
        // so retry the exact original request (already-ACKed is accepted by the server).
        pendingAck.attempts += 1
        pendingAck.lastAttemptAt = new Date().toISOString()
        pendingAck.retryable = true
        pendingAck.lastError = 'ack_response_unreadable'
        state.lastError = pendingAck.lastError
        persist()
        requestAckRetry(pendingAck.attempts)
        return
      }
      const proof = ackProof(body, pendingAck)
      if (!proof.ok) {
        pendingAck.attempts += 1
        pendingAck.lastAttemptAt = new Date().toISOString()
        pendingAck.retryable = false
        pendingAck.lastError = 'ack_proof_incomplete'
        state.lastError = pendingAck.lastError
        persist()
        return
      }
      if (!isRevision(body?.ackedRevision)) {
        pendingAck.attempts += 1
        pendingAck.lastAttemptAt = new Date().toISOString()
        pendingAck.retryable = false
        pendingAck.lastError = 'ack_revision_invalid'
        state.lastError = pendingAck.lastError
        persist()
        return
      }
      await resolvePendingAck(pendingAck, body)
    } finally {
      ackInFlight = false
      if (ackRetryRequested) {
        const delay = ackRetryDelay
        ackRetryRequested = false
        ackRetryDelay = 0
        scheduleAck(delay)
      }
    }
  }

  const verifyRetryDelayFor = (attempts) => {
    const base = Math.max(10, Number(config.verifyRetryBaseMs ?? 500))
    const cap = Math.max(base, Number(config.verifyRetryMaxMs ?? 30000))
    return Math.min(base * (2 ** Math.max(0, attempts - 1)), cap)
  }

  const scheduleVerify = (delay) => {
    if (disposed || stopped || verifying || verifyTimer) return
    if (state.freshVerification?.required !== true) return
    if (state.lockError || state.stateError || !persistenceEnabled) return
    if (!lockStillOurs(lock)) {
      state.persistenceError = 'state_lock_lost'
      return
    }
    const wait = delay === undefined ? Math.max(0, verifyNextAt - Date.now()) : Math.max(0, delay)
    verifyNextAt = Date.now() + wait
    verifyTimer = setTimeout(() => {
      verifyTimer = null
      void verifyFreshPanel()
    }, wait)
    verifyTimer.unref?.()
  }

  const requestVerifyRetry = (attempts) => {
    const delay = verifyRetryDelayFor(attempts)
    verifyNextAt = Date.now() + delay
    if (verifying) {
      verifyRetryRequested = true
      verifyRetryDelay = delay
    } else {
      scheduleVerify(delay)
    }
  }

  const markFreshVerificationDone = (lastError = null) => {
    if (!state.freshVerification) return
    state.freshVerification = {
      ...state.freshVerification,
      required: false,
      lastError,
      lastAttemptAt: new Date().toISOString(),
      nextAt: null,
    }
    persist()
  }

  const pendingPushFromPanel = (panel, source, options = {}) => {
    if (!source) return null
    if (panel?.inboxId && String(panel.inboxId) !== source.inboxId) return null
    const shape = batchFromPanel(panel, state.targetSessionId, source.inboxId, source.fingerprint,
      options)
    if (shape.error) return null
    return {
      inboxId: source.inboxId,
      inboxIdentity: String(panel.inboxIdentity).trim(),
      sourceTargetFingerprint: source.fingerprint,
      sourceTargetFingerprintVersion: TARGET_FINGERPRINT_VERSION,
      targetSessionId: state.targetSessionId ?? null,
      panelRevision: shape.revision,
      pushRevision: shape.push,
      itemIds: [...shape.batch.itemIds],
      createdAt: new Date().toISOString(),
    }
  }

  const pendingPushKey = (pendingPush = state.pendingPush) => (pendingPush
    ? `${pendingPush.inboxId}|${pendingPush.sourceTargetFingerprint}`
      + `|${state.targetSessionId ?? ''}`
    : null)

  /** Persist the verified-but-not-injected push before clearing the verification diagnostic. */
  const commitVerifiedPush = (panel, source, options = {}) => {
    if (state.pendingAck || state.injectionIntent) return false
    const candidate = pendingPushFromPanel(panel, source, options)
    if (!candidate) {
      state.lastError = 'panel_not_injectable'
      persist()
      return false
    }
    if (state.pendingPush && !samePendingPush(state.pendingPush, candidate)) {
      state.lastError = 'pending_push_conflict'
      persist()
      return false
    }
    const previous = {
      pendingPush: state.pendingPush,
      freshVerification: state.freshVerification,
      lastError: state.lastError,
    }
    state.pendingPush = candidate
    if (state.freshVerification) {
      state.freshVerification = {
        ...state.freshVerification,
        required: false,
        lastError: null,
        lastAttemptAt: new Date().toISOString(),
        nextAt: null,
      }
    }
    if (!persist()) {
      Object.assign(state, previous)
      return false
    }
    pendingPushProcessId = source.processId ?? connectionProcessId ?? null
    pendingPushRebaseline = false
    pendingSource = options.allowLiveSnapshot === true
      ? { ...source, allowLiveSnapshot: true }
      : source
    return true
  }

  /**
   * After a confirmed ACK the plugin must look at a fresh server panel before injecting again.
   * A live GET is never treated as a push; it is only usable when every displayed item is frozen
   * for the same newer push revision, preserving server batching.
   */
  const verifyFreshPanel = async () => {
    if (verifying || disposed || stopped) return
    if (state.freshVerification?.required !== true) return
    if (state.lockError || state.stateError || !persistenceEnabled) return
    if (!lockStillOurs(lock)) {
      state.persistenceError = 'state_lock_lost'
      return
    }
    const operation = generation
    const controlAtStart = controlEpoch
    verifying = true
    const current = state.freshVerification
    current.attempts = Number(current.attempts ?? 0) + 1
    current.lastAttemptAt = new Date().toISOString()
    const expectedInbox = current.inboxId ?? config.inboxId
    const expectedFingerprint = current.sourceTargetFingerprint
    try {
      // The fresh GET must go to the same target that the ACK resolved against. A config change
      // (inboxId/baseUrl) or an unsupported URL is fail-closed, never redirected.
      const target = targetContext()
      if (target.error) {
        current.lastError = target.error
        current.nextAt = Date.now() + verifyRetryDelayFor(current.attempts)
        state.lastError = target.error
        persist()
        requestVerifyRetry(current.attempts)
        return
      }
      if (!expectedFingerprint
        || current.sourceTargetFingerprintVersion !== TARGET_FINGERPRINT_VERSION) {
        markFreshVerificationDone('fresh_target_unverified')
        state.lastError = 'fresh_target_unverified'
        return
      }
      if (String(config.inboxId ?? 'bma3') !== expectedInbox) {
        current.lastError = 'fresh_inbox_id_mismatch'
        current.nextAt = Date.now() + verifyRetryDelayFor(current.attempts)
        state.lastError = current.lastError
        persist()
        requestVerifyRetry(current.attempts)
        return
      }
      if (target.fingerprint !== expectedFingerprint) {
        current.lastError = 'fresh_base_url_mismatch'
        current.nextAt = Date.now() + verifyRetryDelayFor(current.attempts)
        state.lastError = current.lastError
        persist()
        requestVerifyRetry(current.attempts)
        return
      }
      const url = requestUrl(expectedInbox, '/panel')
      if (!url) {
        current.lastError = 'config_target_unsupported'
        current.nextAt = Date.now() + verifyRetryDelayFor(current.attempts)
        state.lastError = current.lastError
        persist()
        requestVerifyRetry(current.attempts)
        return
      }
      const { response, body: panel } = await fetchJsonWithTimeout(url, {
        method: 'GET',
        headers: {
          Accept: 'application/json',
          ...authHeaders(config),
        },
      }, Number(config.panelTimeoutMs ?? 10000), 'verify')
      if (disposed || stopped || generation !== operation || controlEpoch !== controlAtStart) return
      if (state.freshVerification !== current || current.required !== true) return
      const targetAfter = targetContext()
      const targetAfterError = targetAfter.error
        ?? (String(config.inboxId ?? 'bma3') !== expectedInbox
          ? 'fresh_inbox_id_mismatch'
          : (targetAfter.fingerprint !== expectedFingerprint ? 'fresh_base_url_mismatch' : null))
      if (targetAfterError) {
        current.lastError = targetAfterError
        current.nextAt = Date.now() + verifyRetryDelayFor(current.attempts)
        state.lastError = targetAfterError
        persist()
        requestVerifyRetry(current.attempts)
        return
      }
      if (!response.ok) {
        current.lastError = `fresh_verify_http_${response.status}`
        current.nextAt = Date.now() + verifyRetryDelayFor(current.attempts)
        state.lastError = current.lastError
        persist()
        requestVerifyRetry(current.attempts)
        return
      }
      if (!panel) {
        current.lastError = 'fresh_verify_invalid'
        current.nextAt = Date.now() + verifyRetryDelayFor(current.attempts)
        state.lastError = current.lastError
        persist()
        requestVerifyRetry(current.attempts)
        return
      }
      if (!noteIdentity(panel)) {
        // Identity mismatch/missing identity cannot be fixed by another GET. Stop the loop;
        // a later panel/identity acceptance decides when injection may resume.
        markFreshVerificationDone('fresh_verify_identity')
        state.lastError = 'fresh_verify_identity'
        return
      }
      const envelope = panelEnvelope(panel)
      if (envelope.error) {
        markFreshVerificationDone('fresh_panel_revision_invalid')
        state.lastError = 'fresh_panel_revision_invalid'
        return
      }
      // A live GET may include items still held by a source quiet window
      // (frozenRevision=0). They are visible to the Agent, but they are not part of
      // any push batch yet; proof/recovery must ignore them or every later ACK
      // would see a mixed panel and stop.
      const proofItems = envelope.items
        .filter((item) => item?.waitingForQuiet !== true)
      const ids = proofItems.map((item) => item?.id).filter(isNonEmptyString)
      const proofPanel = { ...panel, items: proofItems }
      if (state.pendingPush) {
        const pendingPush = state.pendingPush
        const pendingStillVisible = pendingPush.itemIds.some((id) => ids.includes(id))
        // The operator or another client may ACK the item while this plugin was recovering.
        // Once the server has acked past the panel that produced the pendingPush and none of
        // its IDs remain visible, the old record is proving a state that no longer exists. Clear
        // it and continue with the same fresh panel instead of retrying a forever-stale match.
        if (!pendingStillVisible && envelope.ackedRevision >= pendingPush.panelRevision) {
          const previous = {
            pendingPush: state.pendingPush,
            deferredPush: state.deferredPush,
            lastError: state.lastError,
            lastPushRevision: state.lastPushRevision,
            pendingPushProcessId,
            pendingPushRebaseline,
            pendingPushMismatchKey,
            pendingPushMismatchCode,
          }
          state.pendingPush = null
          state.deferredPush = null
          state.lastError = null
          state.lastPushRevision = Math.max(state.lastPushRevision, pendingPush.pushRevision)
          pendingPushProcessId = null
          pendingPushRebaseline = false
          pendingPushMismatchKey = null
          pendingPushMismatchCode = null
          if (!persist()) {
            Object.assign(state, previous)
            pendingPushProcessId = previous.pendingPushProcessId
            pendingPushRebaseline = previous.pendingPushRebaseline
            pendingPushMismatchKey = previous.pendingPushMismatchKey
            pendingPushMismatchCode = previous.pendingPushMismatchCode
            current.lastError = 'pending_push_clear_persist_failed'
            state.lastError = current.lastError
            return
          }
          // Fall through to normal fresh-panel handling. If the operator acked the old push and
          // a newer push is present, it can be injected now; an empty panel simply completes.
        } else {
          // An explicit manual retry may drop a processId that only the previous server run had
          // (a legitimate restart changes it permanently). In that one case the baseline is
          // re-established by wake()'s /runtime probe; every other field is still matched exactly.
          const rebaseline = pendingPushRebaseline
          const expectedInstance = rebaseline
            ? null : (pendingPushProcessId ?? connectionProcessId ?? null)
          if (!rebaseline && expectedInstance) pendingPushProcessId = expectedInstance
          const exact = pendingPush.inboxId === expectedInbox
            && pendingPush.sourceTargetFingerprint === expectedFingerprint
            && pendingPush.inboxIdentity === panel.inboxIdentity
            && pendingPush.panelRevision === envelope.revision
            && pendingPush.pushRevision === envelope.push
            && pendingPush.itemIds.length === ids.length
            && pendingPush.itemIds.every((id, index) => id === ids[index])
          const proof = exact ? provableFreshPush(proofPanel, target.fingerprint) : { ok: false }
          if (!exact || !proof.ok) {
            pendingPushRebaseline = false
            pendingPushMismatchKey = pendingPushKey()
            pendingPushMismatchCode = 'unverified'
            markFreshVerificationDone('pending_push_unverified')
            state.lastError = 'pending_push_unverified'
            return
          }
          pendingPushMismatchKey = null
          pendingPushMismatchCode = null
          markFreshVerificationDone(null)
          pending = proofPanel
          pendingSource = {
            inboxId: expectedInbox,
            fingerprint: expectedFingerprint,
            processId: expectedInstance,
          }
          return
        }
      }
      const stillAcked = (state.recentAcked ?? []).filter((id) => ids.includes(id))
      if (stillAcked.length > 0) {
        state.deferredPush = {
          revision: envelope.revision,
          pushRevision: envelope.push,
          reason: 'confirmed ACK ids are still present in the fresh panel',
          at: new Date().toISOString(),
        }
        markFreshVerificationDone('fresh_verify_acked_present')
        state.lastError = 'fresh_verify_acked_present'
        return
      }
      if (proofItems.length === 0) {
        markFreshVerificationDone(null)
        return
      }
      if (envelope.push <= state.injectedPushRevision) {
        markFreshVerificationDone(null)
        return
      }
      const proof = provableLiveSnapshot(proofPanel, target.fingerprint)
      if (!proof.ok) {
        state.deferredPush = {
          revision: envelope.revision,
          pushRevision: envelope.push,
          reason: proof.reason,
          at: new Date().toISOString(),
        }
        // A proof failure is a retryable observation, not a terminal ACK verdict. Marking the
        // verification done here used to stop all recovery forever (`nextAt=null`), leaving the
        // push watermark frozen with new items still on the server.
        current.lastError = 'fresh_verify_batch_mixed'
        current.nextAt = Date.now() + verifyRetryDelayFor(current.attempts)
        state.lastError = current.lastError
        persist()
        requestVerifyRetry(current.attempts)
        return
      }
      if (!commitVerifiedPush(proofPanel, {
        inboxId: expectedInbox,
        fingerprint: expectedFingerprint,
        processId: pendingAckProcessId ?? connectionProcessId ?? null,
      }, { allowLiveSnapshot: true })) {
        return
      }
      pending = proofPanel
      pendingSource = {
        inboxId: expectedInbox,
        fingerprint: expectedFingerprint,
        processId: pendingPushProcessId ?? null,
        allowLiveSnapshot: true,
      }
    } catch (error) {
      if (disposed || stopped || generation !== operation || controlEpoch !== controlAtStart) return
      if (state.freshVerification !== current || current.required !== true) return
      current.lastError = error?.name === 'TimeoutError' ? 'fresh_verify_timeout' : 'fresh_verify_failed'
      current.nextAt = Date.now() + verifyRetryDelayFor(current.attempts)
      state.lastError = current.lastError
      persist()
      requestVerifyRetry(current.attempts)
    } finally {
      verifying = false
      if (verifyRetryRequested) {
        const delay = verifyRetryDelay
        verifyRetryRequested = false
        verifyRetryDelay = 0
        scheduleVerify(delay)
      }
    }
  }

  const recoverPendingPush = () => {
    if (disposed || stopped || state.paused) return
    const pendingPush = state.pendingPush
    if (!pendingPush || state.pendingAck || state.injectionIntent) return
    const target = targetContext()
    const mismatchKey = pendingPushKey(pendingPush)
    if (target.error || target.fingerprint !== pendingPush.sourceTargetFingerprint
      || String(config.inboxId ?? 'bma3') !== pendingPush.inboxId) {
      if (pendingPushMismatchKey !== mismatchKey || pendingPushMismatchCode !== 'target') {
        pendingPushMismatchKey = mismatchKey
        pendingPushMismatchCode = 'target'
        state.lastError = 'pending_push_target_mismatch'
        persist()
      }
      return
    }
    if (pendingPushMismatchKey === mismatchKey && pendingPushMismatchCode) return
    // A null target is the legacy "chosen at first wake" case. A concrete mismatch is still
    // fail-closed; wake() will attach the legacy record to the same session in one persist.
    if (pendingPush.targetSessionId
      && pendingPush.targetSessionId !== state.targetSessionId) {
      if (pendingPushMismatchKey !== mismatchKey || pendingPushMismatchCode !== 'session') {
        pendingPushMismatchKey = mismatchKey
        pendingPushMismatchCode = 'session'
        state.lastError = 'pending_push_session_mismatch'
        persist()
      }
      return
    }
    pendingPushMismatchKey = null
    pendingPushMismatchCode = null
    const current = state.freshVerification
    if (!current || current.required !== true
      || current.inboxId !== pendingPush.inboxId
      || current.sourceTargetFingerprint !== pendingPush.sourceTargetFingerprint) {
      const previous = state.freshVerification
      state.freshVerification = {
        required: true,
        attempts: 0,
        lastError: null,
        lastAttemptAt: null,
        nextAt: null,
        at: new Date().toISOString(),
        inboxId: pendingPush.inboxId,
        sourceTargetFingerprint: pendingPush.sourceTargetFingerprint,
        sourceTargetFingerprintVersion: pendingPush.sourceTargetFingerprintVersion,
      }
      if (!persist()) {
        state.freshVerification = previous
        return
      }
    }
    // A transient instance-probe failure leaves `nextAt` set; respect that bounded backoff
    // instead of forcing an immediate GET/probe loop.
    scheduleVerify()
  }

  /**
   * A runtime probe can fail for two very different reasons:
   * - `transient`: timeout/network/5xx/response loss. The push stays durable and is retried with
   *   bounded backoff; it is NOT evidence of a different instance.
   * - `mismatch`/`unverified`: a valid response proves another process, or no longer provides a
   *   processId we previously proved. Fail closed, retain the push, and require manual handling.
   */
  const handleConnectionInstanceFailure = (kind, panel) => {
    const key = pendingPushKey()
    pending = null
    pendingSource = null
    if (kind === 'transient') {
      const attempts = Math.max(1, Number(state.freshVerification?.attempts ?? 0))
      const delay = verifyRetryDelayFor(attempts)
      const code = state.pendingPush
        ? 'pending_push_instance_unverified' : 'connection_instance_unverified'
      const pendingPush = state.pendingPush
      const now = new Date().toISOString()
      if (pendingPush) {
        const base = state.freshVerification ?? {}
        state.freshVerification = {
          ...base,
          required: true,
          attempts,
          lastError: code,
          lastAttemptAt: now,
          nextAt: Date.now() + delay,
          at: base.at ?? now,
          inboxId: base.inboxId ?? pendingPush.inboxId,
          sourceTargetFingerprint: base.sourceTargetFingerprint
            ?? pendingPush.sourceTargetFingerprint,
          sourceTargetFingerprintVersion: base.sourceTargetFingerprintVersion
            ?? pendingPush.sourceTargetFingerprintVersion,
        }
      }
      state.lastError = code
      persist()
      if (state.freshVerification?.required === true) requestVerifyRetry(attempts)
      return false
    }
    if (key) {
      pendingPushMismatchKey = key
      pendingPushMismatchCode = kind
    }
    // The explicit re-baseline did not establish a usable instance proof (a valid 200 without
    // processId, or a non-retryable probe failure). Stop treating the next attempt as manual.
    pendingPushRebaseline = false
    state.lastError = kind === 'mismatch'
      ? 'stale_connection_instance' : 'connection_instance_unverified'
    if (state.freshVerification) {
      state.freshVerification = {
        ...state.freshVerification,
        required: false,
        lastError: state.lastError,
        lastAttemptAt: new Date().toISOString(),
        nextAt: null,
      }
    }
    state.deferredPush = {
      revision: Number(panel?.revision ?? 0),
      pushRevision: Number(panel?.pushRevision ?? 0),
      reason: state.lastError,
      at: new Date().toISOString(),
    }
    persist()
    return false
  }

  /**
   * Persist the exact injection intent, then followup. A confirmed followup atomically becomes
   * pendingAck + watermark; a followup error or a failed snapshot write leaves an uncertain
   * intent and never ACKs or repeats in-process.
   */
  const wake = async (panel, source = pendingSource) => {
    if (disposed) return false
    if (!panel || !Array.isArray(panel.items) || panel.items.length === 0) return false
    if (blockedReason()) return false
    const operation = generation
    const controlAtStart = controlEpoch
    const agent = findAgent()
    if (!agent || agent.status !== 'idle' || typeof agent.followup !== 'function') return false
    try {
      agentToolRegistrar?.(agent)
    } catch {
      toolRegistration = toolRegistration ?? {
        registered: false, attempts: 0, lastError: 'agent_tool_register_failed', at: null, scopes: [],
      }
    }
    const targetSessionId = isNonEmptyString(agent.id) ? String(agent.id) : state.targetSessionId
    if (!isNonEmptyString(targetSessionId)) {
      state.lastError = 'wake: target session id is missing; refusing an unattributable injection'
      return false
    }
    if (!adoptRuntimeBinding(targetSessionId)) return false
    const target = targetContext()
    if (target.error) {
      state.lastError = target.error
      return false
    }
    if (!source || target.fingerprint !== source.fingerprint
      || String(config.inboxId ?? 'bma3') !== source.inboxId) {
      state.lastError = 'stale_panel_target'
      state.deferredPush = {
        revision: Number(panel?.revision ?? 0),
        pushRevision: Number(panel?.pushRevision ?? 0),
        reason: 'stale_panel_target',
        at: new Date().toISOString(),
      }
      pending = null
      pendingSource = null
      persist()
      return false
    }
    // A pendingPush that was verified while this plugin process still had the old /runtime
    // processId can be re-baselined by one explicit manual retry; the probe below then adopts
    // the currently answering process. A panel from a normal SSE connection always carries its
    // own proven processId and is still compared (never adopted).
    const rebaseline = pendingPushRebaseline && Boolean(state.pendingPush)
    let provenInstance = source.processId ?? null
    if (source.processId || rebaseline) {
      const runtimeUrl = requestUrl(source.inboxId, '/runtime')
      if (!runtimeUrl) return handleConnectionInstanceFailure('unverified', panel)
      let response = null
      let body = null
      try {
        const probed = await fetchJsonWithTimeout(
          runtimeUrl,
          { method: 'GET', headers: { Accept: 'application/json', ...authHeaders(config) } },
          Number(config.panelTimeoutMs ?? 10000),
          'runtime',
        )
        response = probed.response
        body = probed.body
      } catch {
        if (disposed || generation !== operation || controlEpoch !== controlAtStart) return false
        return handleConnectionInstanceFailure('transient', panel)
      }
      if (disposed || generation !== operation || controlEpoch !== controlAtStart) return false
      if (!response.ok) {
        return handleConnectionInstanceFailure(
          retryableStatus(response.status) ? 'transient' : 'unverified',
          panel,
        )
      }
      if (body === null) return handleConnectionInstanceFailure('transient', panel)
      if (!isNonEmptyString(body?.processId)) {
        return handleConnectionInstanceFailure('unverified', panel)
      }
      const observed = String(body.processId)
      if (rebaseline) {
        // Explicit manual recovery only: adopt the current instance so a legitimate server
        // restart is not a permanent lock. The exact push match below still decides injection.
        pendingPushProcessId = observed
        pendingPushRebaseline = false
        provenInstance = observed
      } else if (observed !== source.processId) {
        return handleConnectionInstanceFailure('mismatch', panel)
      }
    }
    const allowLiveSnapshot = source.allowLiveSnapshot === true
    const shape = batchFromPanel(panel, targetSessionId,
      source.inboxId, source.fingerprint, { allowLiveSnapshot })
    if (shape.error) {
      state.lastError = `panel: ${shape.error}`
      persist()
      return false
    }
    const { batch, revision, push } = shape
    if (push <= state.injectedPushRevision) return false
    if (state.pendingPush) {
      const candidate = pendingPushFromPanel(panel, source, { allowLiveSnapshot })
      if (!candidate || !samePendingPush(state.pendingPush, candidate)) {
        state.lastError = 'pending_push_mismatch'
        pending = null
        pendingSource = null
        persist()
        return false
      }
    }
    const text = renderPanel(panel)

    const previousIntent = state.injectionIntent
    const previousPendingPush = state.pendingPush
    const previousPendingPushProcessId = pendingPushProcessId
    const previousPendingPushRebaseline = pendingPushRebaseline
    state.injectionIntent = batch
    state.pendingAck = null
    state.pendingPush = null
    pendingPushProcessId = null
    pendingPushRebaseline = false
    pendingPushMismatchKey = null
    pendingPushMismatchCode = null
    state.deferredPush = null
    if (!persist()) {
      state.injectionIntent = previousIntent
      state.pendingPush = previousPendingPush
      pendingPushProcessId = previousPendingPushProcessId
      pendingPushRebaseline = previousPendingPushRebaseline
      pending = null
      pendingSource = null
      return false
    }
    try {
      await agent.followup({
        id: `bma3-inbox-${revision}-${push}`,
        role: 'user',
        content: [{ type: 'text', text }],
        source: { kind: 'bma3-inbox', revision, pushRevision: push },
      })
    } catch (error) {
      // The call may or may not have reached the session. Keep the persisted intent: it is the
      // honest "uncertain" record and blocks both automatic reinjection and ACK.
      if (disposed || generation !== operation || controlEpoch !== controlAtStart) return false
      state.lastError = 'followup_failed'
      state.injectionIntent = batch
      pending = null
      persist()
      return false
    }
    if (disposed || generation !== operation || controlEpoch !== controlAtStart) return false
    state.injectionIntent = null
    state.pendingAck = {
      ...batch,
      status: 'pending',
      attempts: 0,
      lastError: null,
      retryable: true,
      lastAttemptAt: null,
    }
    pendingAckProcessId = provenInstance ?? connectionProcessId ?? null
    pendingPushProcessId = null
    pendingPushRebaseline = false
    pendingPushMismatchKey = null
    pendingPushMismatchCode = null
    state.injectedRevision = Math.max(state.injectedRevision, revision)
    state.injectedPushRevision = Math.max(state.injectedPushRevision, push)
    state.lastPushRevision = Math.max(state.lastPushRevision, push)
    state.lastError = null
    state.freshVerification = null
    if (!persist()) {
      // The followup happened but the pending record did not reach disk. Downgrade to the
      // uncertain intent: never ACK, never repeat in-process.
      state.pendingAck = null
      state.injectionIntent = batch
      pendingAckProcessId = null
      state.lastError = 'state: pending ACK snapshot failed after followup; ACK withheld (uncertain)'
      pending = null
      return false
    }
    pending = null
    scheduleAck(0)
    return true
  }

  const pump = async () => {
    if (stopped || state.paused || disposed) return
    const operation = generation
    const controlAtStart = controlEpoch
    if (state.pendingPush && !state.pendingAck && !state.injectionIntent && !pending) {
      recoverPendingPush()
      return
    }
    if (state.pendingAck) {
      if (!ackTimer && !ackInFlight) scheduleAck(0)
      return
    }
    if (state.injectionIntent) return
    if (state.freshVerification?.required === true) {
      scheduleVerify()
      return
    }
    if (injecting || !pending) return
    if (blockedReason()) return
    injecting = true
    try {
      const panel = pending
      const source = pendingSource
      const injected = await wake(panel, source)
      if (!disposed && generation === operation && controlEpoch === controlAtStart
        && injected && pending === panel) {
        pending = null
        pendingSource = null
      }
    } catch (error) {
      if (!disposed && generation === operation && controlEpoch === controlAtStart) {
        state.lastError = 'wake_failed'
      }
    } finally {
      injecting = false
    }
  }

  const retryPendingPush = () => {
    const at = new Date().toISOString()
    if (!state.pendingPush) return { ok: false, reason: 'no_pending_push', at }
    if (state.injectionIntent) return { ok: false, reason: 'intent_uncertain', at }
    if (disposed) return { ok: false, reason: 'disposed', at }
    // A stopped client issues no recovery GET. Do not record a "request" that will never run:
    // the operator must start first, and the command/tool text says so.
    if (stopped) return { ok: false, reason: 'stopped', at }
    if (!persistenceEnabled || state.lockError || state.stateError) {
      return { ok: false, reason: 'persistence_unavailable', at }
    }
    if (!lockStillOurs(lock)) {
      state.persistenceError = 'state_lock_lost'
      return { ok: false, reason: 'state_lock_lost', at }
    }
    // Both an automatic verify GET and an in-flight wake already own the instance proof; do not
    // overwrite their baseline or claim a second request from this call.
    if (verifying) return { ok: false, reason: 'verify_in_flight', at }
    if (injecting) return { ok: false, reason: 'injection_in_flight', at }
    // Explicit manual handling resets the automatic-retry block (target/session/instance
    // diagnostics) and performs one fresh GET + instance probe. It never changes the push IDs,
    // revisions, target session or the exact match requirements stored in the record.
    const previous = {
      pendingPushProcessId,
      pendingPushRebaseline,
      pendingPushMismatchKey,
      pendingPushMismatchCode,
      freshVerification: state.freshVerification,
      lastError: state.lastError,
      verifyNextAt,
    }
    pendingPushMismatchKey = null
    pendingPushMismatchCode = null
    pendingPushProcessId = null
    pendingPushRebaseline = true
    const pendingPush = state.pendingPush
    state.freshVerification = {
      required: true,
      attempts: 0,
      lastError: null,
      lastAttemptAt: null,
      nextAt: null,
      at,
      inboxId: pendingPush.inboxId,
      sourceTargetFingerprint: pendingPush.sourceTargetFingerprint,
      sourceTargetFingerprintVersion: pendingPush.sourceTargetFingerprintVersion,
    }
    if (!persist()) {
      pendingPushProcessId = previous.pendingPushProcessId
      pendingPushRebaseline = previous.pendingPushRebaseline
      pendingPushMismatchKey = previous.pendingPushMismatchKey
      pendingPushMismatchCode = previous.pendingPushMismatchCode
      state.freshVerification = previous.freshVerification
      state.lastError = previous.lastError
      verifyNextAt = previous.verifyNextAt
      return { ok: false, reason: 'persist_failed', at }
    }
    verifyNextAt = 0
    scheduleVerify(0)
    return { ok: true, reason: 'verify_requested', at }
  }

  const ack = async () => {
    lastAckResult = null
    if (state.pendingAck) {
      clearAckTimer()
      ackNextAt = 0
      const attemptsBefore = state.pendingAck.attempts ?? 0
      await attemptAck()
      if (!state.pendingAck) {
        lastAckResult = { kind: 'ack', ok: true, reason: 'ack_complete', at: new Date().toISOString() }
      } else if ((state.pendingAck.attempts ?? 0) > attemptsBefore) {
        lastAckResult = { kind: 'ack', ok: true, reason: 'ack_requested', at: new Date().toISOString() }
      } else {
        lastAckResult = { kind: 'ack', ok: false, reason: 'ack_not_sent', at: new Date().toISOString() }
      }
    } else if (state.pendingPush) {
      lastAckResult = {
        kind: 'push_retry',
        ...(state.injectionIntent
          ? { ok: false, reason: 'intent_uncertain', at: new Date().toISOString() }
          : retryPendingPush()),
      }
    } else if (state.injectionIntent) {
      lastAckResult = {
        kind: 'none', ok: false, reason: 'intent_uncertain', at: new Date().toISOString(),
      }
    } else {
      lastAckResult = {
        kind: 'none', ok: false, reason: 'no_pending_record', at: new Date().toISOString(),
      }
    }
    return status()
  }

  const handleEvent = async ({ id, event, data }, connectionSource = null) => {
    if (disposed || stopped) return
    const operation = generation
    const controlAtStart = controlEpoch
    state.lastEventAt = new Date().toISOString()
    if (event === 'heartbeat') {
      const revision = Number(id)
      if (isRevision(revision)) state.lastRevision = revision
      return
    }
    if (event === 'control') {
      try {
        await applyControl(JSON.parse(data))
      } catch (error) {
        if (!disposed && generation === operation && controlEpoch === controlAtStart) {
          state.lastError = 'control_failed'
        }
      }
      return
    }
    if (event !== 'panel') return
    let panel
    try {
      panel = JSON.parse(data)
    } catch {
      state.lastError = 'invalid panel JSON'
      return
    }
    if (disposed || generation !== operation || controlEpoch !== controlAtStart) return
    const target = targetContext()
    if (target.error) return
    const envelope = panelEnvelope(panel)
    if (envelope.error) {
      state.lastError = envelope.error
      return
    }
    const currentSource = targetContext()
    if (currentSource.error || !connectionSource
      || currentSource.fingerprint !== connectionSource.fingerprint
      || String(config.inboxId ?? 'bma3') !== connectionSource.inboxId) {
      state.lastError = 'stale_connection_target'
      state.deferredPush = {
        revision: envelope.revision,
        pushRevision: envelope.push,
        reason: 'stale_connection_target',
        at: new Date().toISOString(),
      }
      persist()
      return
    }
    if (state.pendingPush) {
      state.deferredPush = {
        revision: envelope.revision,
        pushRevision: envelope.push,
        reason: 'pending_push_waiting_for_recovery',
        at: new Date().toISOString(),
      }
      state.lastError = 'pending_push_waiting_for_recovery'
      persist()
      return
    }
    state.lastRevision = envelope.revision
    state.lastPushRevision = Math.max(state.lastPushRevision, envelope.push)
    // Remember the database identity even for an empty panel (no pending ACK yet). This is what
    // makes a later identity change observable instead of looking like a first use.
    const identityOk = noteIdentity(panel)
    persist()
    if (envelope.empty) {
      pending = null
      pendingSource = null
      return
    }
    if (!identityOk) return
    if (envelope.push <= 0) {
      state.lastError = 'panel_not_pushed'
      persist()
      return
    }
    if (state.injectionIntent || state.pendingAck) {
      const shape = batchFromPanel(panel, null, connectionSource.inboxId, connectionSource.fingerprint)
      const provable = !shape.error
        && shape.push > state.injectedPushRevision
        && shape.items.every((item) => Number(item.frozenRevision) === shape.push)
      if (provable) {
        state.deferredPush = {
          revision: envelope.revision,
          pushRevision: envelope.push,
          reason: 'newer push arrived while a previous injection is unresolved',
          at: new Date().toISOString(),
        }
        persist()
      } else {
        state.deferredPush = {
          revision: envelope.revision,
          pushRevision: envelope.push,
          reason: shape.error ?? 'newer push is not safely reconstructable',
          at: new Date().toISOString(),
        }
        persist()
      }
      return
    }
    const shape = batchFromPanel(panel, state.targetSessionId,
      connectionSource.inboxId, connectionSource.fingerprint)
    if (shape.error) {
      state.lastError = `panel: ${shape.error}`
      persist()
      return
    }
    if (shape.push <= state.injectedPushRevision) return
    // A panel event can be queued before a confirmed ACK and delivered after it. Never inject a
    // queued snapshot that still contains an id this client already confirmed: that would repeat
    // displayed content. Only a snapshot without those ids is accepted as the fresh panel.
    const stillAcked = (state.recentAcked ?? [])
      .filter((id) => shape.items.some((item) => item?.id === id))
    if (stillAcked.length > 0) {
      state.deferredPush = {
        revision: shape.revision,
        pushRevision: shape.push,
        reason: 'queued panel still contains confirmed ACK ids',
        at: new Date().toISOString(),
      }
      state.lastError = state.deferredPush.reason
      persist()
      return
    }
    if (!commitVerifiedPush(panel, connectionSource)) return
    pending = panel
    pendingSource = connectionSource
    await pump()
    if (disposed || generation !== operation || controlEpoch !== controlAtStart) return
  }

  const scheduleReconnect = () => {
    if (stopped || disposed) return
    state.connection = 'retrying'
    const delay = retryMs
    retryMs = Math.min(Math.max(retryMs * 2, 1000), Number(config.reconnectMaxMs ?? 30000))
    retryTimer = setTimeout(() => {
      retryTimer = null
      void connect()
    }, delay)
    retryTimer.unref?.()
  }

  const readStream = async (response, connectionAtStart, connectionSource) => {
    const reader = response.body?.getReader?.()
    if (!reader) throw new Error('SSE response body is not readable')
    const decoder = new TextDecoder()
    const parser = new SseParser((event) => { void handleEvent(event, connectionSource) })
    for (;;) {
      const { value, done } = await reader.read()
      if (disposed || stopped || connectionEpoch !== connectionAtStart) return
      if (done) break
      parser.push(decoder.decode(value, { stream: true }))
    }
    parser.push(decoder.decode())
  }

  const connect = async () => {
    if (stopped || disposed) return
    const operation = generation
    const connectionAtStart = connectionEpoch
    const connectionTarget = targetContext()
    if (connectionTarget.error) return
    const connectionSource = {
      inboxId: String(config.inboxId ?? 'bma3'),
      fingerprint: connectionTarget.fingerprint,
      processId: null,
    }
    const url = requestUrl(connectionSource.inboxId, '/events')
    if (!url) return
    state.connection = 'connecting'
    abort = new AbortController()
    try {
      const response = await fetchImpl(url, {
        method: 'GET',
        headers: {
          Accept: 'text/event-stream',
          ...authHeaders(config),
          ...(state.lastRevision > 0 ? { 'Last-Event-ID': String(state.lastRevision) } : {}),
        },
        signal: abort.signal,
      })
      if (disposed || stopped || generation !== operation
        || connectionEpoch !== connectionAtStart) return
      const currentSource = targetContext()
      if (currentSource.error || currentSource.fingerprint !== connectionSource.fingerprint
        || String(config.inboxId ?? 'bma3') !== connectionSource.inboxId) {
        state.lastError = 'stale_connection_target'
        abort?.abort()
        return
      }
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }
      retryMs = Math.max(200, Number(config.reconnectMinMs ?? 1000))
      state.connection = 'open'
      state.lastError = null
      void heartbeat()
      try {
        const runtimeUrl = requestUrl(connectionSource.inboxId, '/runtime')
        if (runtimeUrl) {
          const { response: runtimeResponse, body: runtimeBody } = await fetchJsonWithTimeout(
            runtimeUrl,
            { method: 'GET', headers: { Accept: 'application/json', ...authHeaders(config) } },
            Number(config.panelTimeoutMs ?? 10000),
            'runtime',
          )
          if (runtimeResponse.ok && isNonEmptyString(runtimeBody?.processId)) {
            connectionSource.processId = String(runtimeBody.processId)
            connectionProcessId = connectionSource.processId
          }
        }
      } catch { /* target binding still applies; panels stay conservative if runtime is absent */ }
      if (disposed || stopped || generation !== operation
        || connectionEpoch !== connectionAtStart) return
      await readStream(response, connectionAtStart, connectionSource)
      if (!stopped && !disposed) throw new Error('SSE stream ended')
    } catch (error) {
      if (!stopped && !disposed && generation === operation
        && connectionEpoch === connectionAtStart) {
        state.lastError = error?.name === 'AbortError' ? state.lastError : 'sse_failed'
        scheduleReconnect()
      }
    }
  }

  const stopEngine = () => {
    stopped = true
    ackSuspended = true
    ackCancelEpoch += 1
    connectionEpoch += 1
    clearAckTimer()
    abort?.abort()
    abort = null
    ackAbort?.abort()
    ackAbort = null
    verifyAbort?.abort()
    verifyAbort = null
    identityAbort?.abort()
    identityAbort = null
    runtimeAbort?.abort()
    runtimeAbort = null
    if (verifyTimer) clearTimeout(verifyTimer)
    verifyTimer = null
    if (retryTimer) clearTimeout(retryTimer)
    if (pumpTimer) clearInterval(pumpTimer)
    if (heartbeatTimer) clearInterval(heartbeatTimer)
    retryTimer = null
    pumpTimer = null
    heartbeatTimer = null
    state.connection = 'closed'
  }

  const startEngine = () => {
    if (disposed || !stopped) return
    stopped = false
    connectionEpoch += 1
    ackSuspended = false
    pumpTimer = setInterval(() => { void pump() }, pumpMs)
    pumpTimer.unref?.()
    if (!heartbeatTimer) {
      heartbeatTimer = setInterval(() => { void heartbeat() }, heartbeatMs)
      heartbeatTimer.unref?.()
    }
    ackNextAt = 0
    verifyNextAt = 0
    if (state.pendingAck) scheduleAck(0)
    if (state.freshVerification?.required === true) scheduleVerify(0)
    if (state.pendingPush) recoverPendingPush()
    void connect()
  }

  const start = (persistStopped = true) => {
    if (disposed) throw new Error('client disposed')
    const target = targetContext()
    if (target.error) return
    if (!stopped) {
      // unbind suspends ACK but leaves the SSE running; an explicit start must resume ACK
      // recovery without opening a second stream or rebuilding timers.
      ackSuspended = false
      ackNextAt = 0
      verifyNextAt = 0
      if (state.pendingAck) scheduleAck(0)
      if (state.freshVerification?.required === true) scheduleVerify(0)
      if (state.pendingPush) recoverPendingPush()
      return
    }
    // A corrupt/unreadable state or a foreign lock is a fail-closed mode, not a user write
    // failure; keep `start` inert and let status report why (existing fail-closed contract).
    if (state.lockError || state.stateError) return
    const previousStopped = state.stopped
    if (persistStopped) state.stopped = false
    if (persistenceEnabled && !persist()) {
      state.stopped = previousStopped
      throw new Error(`无法持久化 start：${state.persistenceError ?? 'state_write_failed'}`)
    }
    controlEpoch += 1
    startEngine()
  }

  const stop = (persistStopped = true) => {
    if (disposed) throw new Error('client disposed')
    const previousStopped = state.stopped
    if (persistStopped) state.stopped = true
    if (persistenceEnabled && !persist()) {
      state.stopped = previousStopped
      throw new Error(`无法持久化 stop：${state.persistenceError ?? 'state_write_failed'}`)
    }
    controlEpoch += 1
    stopEngine()
  }

  if (config.sessionId) state.targetSessionId = String(config.sessionId)
  const pause = () => {
    if (disposed) throw new Error('client disposed')
    const previous = state.paused
    const previousPending = pending
    state.paused = true
    pending = null
    pendingSource = null
    if (persistenceEnabled && !persist()) {
      state.paused = previous
      pending = previousPending
      throw new Error(`无法持久化 pause：${state.persistenceError ?? 'state_write_failed'}`)
    }
    return status()
  }

  const resume = () => {
    if (disposed) throw new Error('client disposed')
    const previous = state.paused
    state.paused = false
    if (persistenceEnabled && !persist()) {
      state.paused = previous
      throw new Error(`无法持久化 resume：${state.persistenceError ?? 'state_write_failed'}`)
    }
    if (state.pendingPush) recoverPendingPush()
    return status()
  }

  /** Bind + resume (if needed) + start. Safe to call repeatedly. */
  const restore = async (sessionId = state.targetSessionId) => {
    if (disposed) throw new Error('client disposed')
    const operation = generation
    const operationEpoch = controlEpoch
    const target = String(sessionId ?? '').trim()
    if (!target) return status()
    const targetConfig = targetContext()
    if (targetConfig.error) {
      state.lastError = targetConfig.error
      return status()
    }
    const previous = {
      targetSessionId: state.targetSessionId,
      stopped: state.stopped,
      ackSuspended,
      pending,
    }
    const wasStopped = stopped
    state.targetSessionId = target
    ackSuspended = false
    pending = null
    if (stopped) state.stopped = false
    // Binding and the "start" intent are written in ONE snapshot. A second snapshot after the
    // async resume could fail after the new binding was already durable, which would report a
    // failure while silently changing what the next restart binds to.
    if (persistenceEnabled && !persist()) {
      state.targetSessionId = previous.targetSessionId
      state.stopped = previous.stopped
      ackSuspended = previous.ackSuspended
      pending = previous.pending
      throw new Error(`无法持久化 restore：${state.persistenceError ?? 'state_write_failed'}`)
    }
    const agent = await ensureAgent(target)
    if (disposed || generation !== operation || controlEpoch !== operationEpoch) return status()
    if (!agent) {
      state.lastError = state.lastError ?? 'resume_failed'
    } else {
      try {
        agentToolRegistrar?.(agent)
      } catch {
        toolRegistration = toolRegistration ?? {
          registered: false, attempts: 0, lastError: 'agent_tool_register_failed', at: null, scopes: [],
        }
      }
    }
    // Start the SSE even when resume failed: the binding is durable and the session may be
    // opened later; starting now means the first push is not missed.
    controlEpoch += 1
    if (wasStopped) startEngine()
    ackNextAt = 0
    verifyNextAt = 0
    if (state.pendingAck) scheduleAck(0)
    if (state.freshVerification?.required === true) scheduleVerify(0)
    if (state.pendingPush) recoverPendingPush()
    if (!stopped) {
      void heartbeat()
    }
    return status()
  }

  /**
   * Explicit operator recovery for a stale verified-push record. This is intentionally not
   * silent: it clears pendingPush/deferredPush, then re-fetches the current panel. It refuses if
   * an ACK or injection intent is unresolved, and it does not advance push watermarks, so a still
   * server-visible item can be injected once from the fresh snapshot.
   */
  const recoverFromPendingPush = (confirm = false) => {
    if (confirm !== true) {
      throw new Error('recover requires explicit confirmation: 可能重复注入/丢失旧 pendingPush')
    }
    if (disposed) throw new Error('client disposed')
    if (stopped) throw new Error('Inbox 已停止：先 /inbox start，再 recover')
    if (state.pendingAck || state.injectionIntent) {
      throw new Error('存在待 ACK/注入意图，不能强制 recover；先按现有流程处置')
    }
    if (verifying || injecting) throw new Error('校验/注入在途，稍后重试 recover')
    if (state.lockError || state.stateError || !persistenceEnabled) {
      throw new Error('状态持久化不可用，拒绝 recover')
    }
    const target = targetContext()
    if (target.error) throw new Error(target.error)
    const pendingPush = state.pendingPush
    const fingerprint = pendingPush?.sourceTargetFingerprint ?? target.fingerprint
    if (!fingerprint) throw new Error('没有可验证的目标指纹，拒绝 recover')
    if (pendingPush?.sourceTargetFingerprint
      && pendingPush.sourceTargetFingerprint !== target.fingerprint) {
      throw new Error('pendingPush 目标与当前 baseUrl 不一致，拒绝 recover')
    }
    if (!pendingPush && !state.deferredPush && state.freshVerification?.required !== true) {
      return status()
    }
    const previous = {
      pendingPush: state.pendingPush,
      deferredPush: state.deferredPush,
      freshVerification: state.freshVerification,
      lastError: state.lastError,
      pending,
      pendingSource,
      pendingPushProcessId,
      pendingPushRebaseline,
      pendingPushMismatchKey,
      pendingPushMismatchCode,
    }
    state.pendingPush = null
    state.deferredPush = null
    state.lastError = null
    pending = null
    pendingSource = null
    pendingPushProcessId = null
    pendingPushRebaseline = false
    pendingPushMismatchKey = null
    pendingPushMismatchCode = null
    state.freshVerification = {
      required: true,
      attempts: 0,
      lastError: null,
      lastAttemptAt: null,
      nextAt: null,
      at: new Date().toISOString(),
      inboxId: pendingPush?.inboxId ?? config.inboxId ?? 'bma3',
      sourceTargetFingerprint: fingerprint,
      sourceTargetFingerprintVersion: pendingPush?.sourceTargetFingerprintVersion
        ?? TARGET_FINGERPRINT_VERSION,
    }
    if (!persist()) {
      Object.assign(state, previous)
      pending = previous.pending
      pendingSource = previous.pendingSource
      pendingPushProcessId = previous.pendingPushProcessId
      pendingPushRebaseline = previous.pendingPushRebaseline
      pendingPushMismatchKey = previous.pendingPushMismatchKey
      pendingPushMismatchCode = previous.pendingPushMismatchCode
      throw new Error(`无法持久化 recover：${state.persistenceError ?? 'state_write_failed'}`)
    }
    controlEpoch += 1
    verifyNextAt = 0
    scheduleVerify(0)
    return status()
  }

  const discard = (confirm = false) => {
    if (confirm !== true) {
      throw new Error('discard requires explicit confirmation: 这可能造成重复注入或遗留未确认条目')
    }
    const hadPending = Boolean(state.pendingAck)
    const hadIntent = Boolean(state.injectionIntent)
    const hadPush = Boolean(state.pendingPush)
    if (!hadPending && !hadIntent && !hadPush) return '当前没有待 ACK 记录、注入意图或待注入 push。'
    const previous = {
      pendingAck: state.pendingAck,
      injectionIntent: state.injectionIntent,
      injectedPushRevision: state.injectedPushRevision,
      injectedRevision: state.injectedRevision,
      lastPushRevision: state.lastPushRevision,
      lastError: state.lastError,
      deferredPush: state.deferredPush,
      freshVerification: state.freshVerification,
      pendingPush: state.pendingPush,
      pendingAckProcessId,
      pendingPushProcessId,
      pendingPushRebaseline,
      pendingPushMismatchKey,
      pendingPushMismatchCode,
      pending,
    }
    const uncertain = state.injectionIntent
    const verifiedPush = state.pendingPush
    state.pendingAck = null
    state.injectionIntent = null
    state.pendingPush = null
    pendingAckProcessId = null
    pendingPushProcessId = null
    pendingPushRebaseline = false
    pendingPushMismatchKey = null
    pendingPushMismatchCode = null
    state.deferredPush = null
    pending = null
    pendingSource = null
    if (uncertain || verifiedPush) {
      // The followup may have happened. Advancing the old identity's watermarks is the only
      // safe way to guarantee this same push is not replayed after the explicit discard; if the
      // followup never happened, that one push is intentionally lost and the warning says so.
      const lost = uncertain ?? verifiedPush
      state.injectedPushRevision = Math.max(state.injectedPushRevision, lost.pushRevision)
      state.injectedRevision = Math.max(state.injectedRevision, lost.panelRevision)
      state.lastPushRevision = Math.max(state.lastPushRevision, lost.pushRevision)
    }
    const target = targetContext()
    state.freshVerification = {
      required: true,
      attempts: 0,
      lastError: null,
      lastAttemptAt: null,
      nextAt: null,
      at: new Date().toISOString(),
      inboxId: config.inboxId ?? 'bma3',
      sourceTargetFingerprint: target.error ? null : target.fingerprint,
      sourceTargetFingerprintVersion: target.error ? null : TARGET_FINGERPRINT_VERSION,
    }
    if (persistenceEnabled && !persist()) {
      Object.assign(state, previous)
      pendingAckProcessId = previous.pendingAckProcessId
      pendingPushProcessId = previous.pendingPushProcessId
      pendingPushRebaseline = previous.pendingPushRebaseline
      pendingPushMismatchKey = previous.pendingPushMismatchKey
      pendingPushMismatchCode = previous.pendingPushMismatchCode
      throw new Error(`无法持久化放弃操作：${state.persistenceError ?? 'state_write_failed'}`)
    }
    controlEpoch += 1
    if (!stopped && state.freshVerification?.required === true) scheduleVerify(0)
    clearAckTimer()
    return '已放弃待处理记录：服务端条目可能仍未确认，后续可能被再次推送/注入（重复风险）；'
      + '若曾发生 followup，也可能已注入但未 ACK。'
      + (verifiedPush ? '已验证但未注入的 push 也已放弃，可能丢失。' : '')
      + '已推进旧 push 水位：该 push 不会自动重放；请人工核对。'
  }

  const acceptIdentity = (confirm = false) => {
    if (confirm !== true) {
      throw new Error('accept-identity requires explicit confirmation')
    }
    if (!state.identityMismatch) return '当前没有身份变化需要接受。'
    if (state.pendingAck || state.injectionIntent || state.pendingPush) {
      throw new Error('请先人工处置待 ACK 记录/注入意图/已验证未注入的 push，再接受新身份')
    }
    const previous = {
      lastSeenIdentity: state.lastSeenIdentity,
      identityMismatch: state.identityMismatch,
      ackIdentityVerified: state.ackIdentityVerified,
      injectedPushRevision: state.injectedPushRevision,
      injectedRevision: state.injectedRevision,
      lastPushRevision: state.lastPushRevision,
      ackedRevision: state.ackedRevision,
      deferredPush: state.deferredPush,
      freshVerification: state.freshVerification,
      lastError: state.lastError,
      pending,
      pendingAckProcessId,
      pendingPushProcessId,
      pendingPushMismatchKey,
      pendingPushMismatchCode,
    }
    const target = targetContext()
    if (target.error) throw new Error(target.error)
    state.lastSeenIdentity = state.identityMismatch.seen
    state.identityMismatch = null
    state.ackIdentityVerified = true
    // Watermarks belong to the database identity. Reusing them after an explicit accept would
    // silently suppress every push of the new database whose revision is below the old one.
    state.injectedPushRevision = 0
    state.injectedRevision = 0
    state.lastPushRevision = 0
    state.ackedRevision = 0
    state.deferredPush = null
    // The new database may already have a current push that was dropped while the identity was
    // mismatched. Re-fetch it instead of waiting for another SSE push.
    state.freshVerification = {
      required: true,
      attempts: 0,
      lastError: null,
      lastAttemptAt: null,
      nextAt: null,
      at: new Date().toISOString(),
      inboxId: config.inboxId ?? 'bma3',
      sourceTargetFingerprint: target.fingerprint,
      sourceTargetFingerprintVersion: TARGET_FINGERPRINT_VERSION,
    }
    state.lastError = null
    pending = null
    pendingAckProcessId = null
    pendingPushProcessId = null
    pendingPushMismatchKey = null
    pendingPushMismatchCode = null
    if (persistenceEnabled && !persist()) {
      Object.assign(state, previous)
      pendingAckProcessId = previous.pendingAckProcessId
      pendingPushProcessId = previous.pendingPushProcessId
      pendingPushMismatchKey = previous.pendingPushMismatchKey
      pendingPushMismatchCode = previous.pendingPushMismatchCode
      throw new Error(`无法持久化身份接受操作：${state.persistenceError ?? 'state_write_failed'}`)
    }
    controlEpoch += 1
    if (!stopped) scheduleVerify(0)
    return `已接受新的 inbox 身份 ${state.lastSeenIdentity}；旧数据库的 push/ACK 水位不可沿用，`
      + '已重置为 0，并会重新校验当前面板。旧库未确认条目仍需人工核对。'
  }

  const dispose = () => {
    if (disposed) return
    disposed = true
    generation += 1
    controlEpoch += 1
    stopEngine()
    pending = null
    if (lock) {
      const releaseError = releaseStateLock(lock)
      lock = null
      if (releaseError) state.lastError = releaseError
    }
  }

  return {
    start,
    stop,
    connect,
    status,
    heartbeat,
    restore,
    ack,
    pause,
    resume,
    recover: (confirm = false) => recoverFromPendingPush(confirm),
    discard,
    acceptIdentity,
    dispose,
    attachToolRegistrar: (registrar) => {
      agentToolRegistrar = typeof registrar === 'function' ? registrar : null
      return status()
    },
    setToolRegistration: (next) => {
      if (!next || typeof next !== 'object') {
        toolRegistration = null
        return status()
      }
      toolRegistration = {
        registered: next.registered === true,
        attempts: Number.isFinite(Number(next.attempts)) ? Number(next.attempts) : 0,
        lastError: next.lastError == null ? null : String(next.lastError).slice(0, 160),
        at: next.at ?? null,
        scopes: Array.isArray(next.scopes)
          ? next.scopes.filter((scope) => typeof scope === 'string').slice(0, 8) : [],
      }
      return status()
    },
    bind: (sessionId) => {
      if (disposed) throw new Error('client disposed')
      const target = String(sessionId ?? '').trim() || null
      const previous = {
        targetSessionId: state.targetSessionId,
        ackSuspended,
        pending,
        deferredPush: state.deferredPush,
      }
      state.targetSessionId = target
      ackSuspended = false
      pending = null
      pendingSource = null
      if (persistenceEnabled && !persist()) {
        state.targetSessionId = previous.targetSessionId
        ackSuspended = previous.ackSuspended
        pending = previous.pending
        throw new Error(`无法持久化 bind：${state.persistenceError ?? 'state_write_failed'}`)
      }
      controlEpoch += 1
      if (target) {
        void restore(target).catch(() => {
          state.lastError = state.lastError ?? 'restore_failed'
        })
      }
      if (state.pendingAck && !target) ackSuspended = true
      return status()
    },
    unbind: () => {
      if (disposed) throw new Error('client disposed')
      const previous = {
        targetSessionId: state.targetSessionId,
        ackSuspended,
        pending,
        deferredPush: state.deferredPush,
      }
      state.targetSessionId = null
      ackSuspended = true
      pending = null
      pendingSource = null
      if (persistenceEnabled && !persist()) {
        state.targetSessionId = previous.targetSessionId
        ackSuspended = previous.ackSuspended
        pending = previous.pending
        throw new Error(`无法持久化 unbind：${state.persistenceError ?? 'state_write_failed'}`)
      }
      controlEpoch += 1
      clearAckTimer()
      ackCancelEpoch += 1
      ackAbort?.abort()
      ackAbort = null
      return status()
    },
  }
}

const OUTPUT = {
  schema: {
    type: 'object',
    properties: {
      text: { type: 'string', description: 'Human-readable Inbox status.' },
    },
    required: ['text'],
    additionalProperties: false,
  },
  render(_args, value) {
    return [{ type: 'text', text: value.text }]
  },
}

const INBOX_SCHEMA = {
  type: 'object',
  properties: {
    action: {
      type: 'string',
      enum: [
        'status', 'bind', 'unbind', 'restore', 'pause', 'resume', 'start', 'stop', 'ack',
        'recover', 'discard', 'accept-identity',
      ],
      description: '查看或修改本会话的 inbox 挂载：status 看状态；bind/unbind 改目标会话；'
        + 'restore 恢复持久化绑定（必要时 resume 会话）并启动 SSE；pause/resume 暂停或恢复注入；'
        + 'start/stop 开关 SSE；ack 重试待 ACK；recover 需 confirm=true，清除 stale '
        + 'pendingPush/deferredPush 并从当前面板重试；discard 需 confirm=true，放弃待 ACK/注入意图；'
        + 'accept-identity 需 confirm=true，接受变化的数据库身份。默认 status。',
    },
    sessionId: {
      type: 'string',
      description: 'bind/restore 时可指定目标 DSH 会话；省略则用调用本工具的会话或已持久化绑定。',
    },
    confirm: {
      type: 'boolean',
      description: 'discard/accept-identity 的显式确认；省略或 false 会被拒绝。',
    },
  },
  additionalProperties: false,
}

const COMMAND_VERBS = Object.freeze([
  'status', 'bind', 'unbind', 'detach', 'restore', 'pause', 'resume', 'start', 'stop', 'ack',
  'recover', 'discard', 'accept-identity',
])

const COMMAND_USAGE = '/inbox [' + COMMAND_VERBS.join('|') + ']'

/** Short session label: enough to tell two sessions apart, never the whole uuid. */
function shortSession(id) {
  const text = String(id ?? '')
  if (!text) return ''
  return text.length <= 10 ? text : text.slice(0, 8) + '…'
}

/**
 * Honest wording for an explicit pendingPush recovery request. The retry only re-establishes
 * the *instance baseline* against the currently answering server; the exact match below still
 * decides whether anything is injected, and a 200 without processId keeps it blocked.
 */
const PUSH_RETRY_REQUESTED_TEXT = '已发起显式恢复校验：以当前运行实例作为新基线；'
  + '恢复仍要求精确匹配 identity/目标/会话/revision/IDs/frozen，'
  + '服务端不提供 processId 或匹配失败时保持阻断，不会自动重放。结果见 /inbox status。'

function pushRetryFailureText(reason) {
  switch (reason) {
    case 'stopped':
      return '未发出恢复校验：Inbox 已停止。先 /inbox start，再 /inbox ack；记录仍保留。'
    case 'verify_in_flight':
      return '已有新鲜面板校验在途；本次未发起新请求，请等结果后再 /inbox ack。'
    case 'injection_in_flight':
      return '注入正在进行；本次未发起恢复请求，请等结果后再 /inbox ack。'
    case 'persistence_unavailable':
      return '状态持久化不可用：未发起恢复校验；记录仍保留。'
    case 'state_lock_lost':
      return '状态文件锁不属于本实例：未发起恢复校验。'
    case 'persist_failed':
      return '无法持久化恢复请求：push 记录仍保留，不会自动重放。'
    case 'intent_uncertain':
      return '存在未确认的注入意图，不会发起恢复校验；请人工核对后用 /inbox discard confirm。'
    case 'disposed':
      return '客户端已销毁：未发起恢复校验。'
    default:
      return '未发起恢复校验；记录仍保留，详情见 /inbox status。'
  }
}

/**
 * Human-readable mount status. Deliberately excludes the token value: only the *source*
 * (`env:NAME` / `config`) is reported (red line 2).
 */
export function renderInboxStatus(status, callingSession = null) {
  const lines = []
  const running = status.running && !status.stopped
  lines.push(`Inbox ${status.inboxId ?? status.config?.inboxId ?? 'bma3'} · ${running ? '运行中' : '已停止'}`)
  const target = status.targetSessionId ? String(status.targetSessionId) : ''
  if (target) {
    const mine = callingSession && target === callingSession ? '（本会话）' : ''
    lines.push(`绑定:     ${shortSession(target)}${mine}`)
  } else {
    lines.push('绑定:     未绑定')
  }
  lines.push(`SSE:      ${status.connection ?? 'closed'}${status.stopped ? '（用户已 stop）' : ''}`)
  lines.push(`游标:     push=${status.injectedPushRevision ?? status.lastPushRevision ?? 0}`
    + ` ack=${status.ackedRevision ?? 0}`
    + ` 待注入=${status.pendingRevision ?? 0}`)
  lines.push(`注入:     ${status.paused ? '已暂停' : '未暂停'} · MCP ${status.lastMcpStartId ? '已连接' : '未连接'}`)
  if (status.pendingAck) {
    const pending = status.pendingAck
    lines.push(`待 ACK:   rev=${pending.panelRevision} push=${pending.pushRevision}`
      + ` items=${pending.itemIds?.length ?? 0} target=${shortSession(pending.targetSessionId) || '-'}`
      + ` identity=${pending.inboxIdentity ?? '-'} 尝试=${pending.attempts ?? 0}`
      + (pending.retryable === false ? ' · 自动重试已停止' : ''))
    if (pending.lastError) lines.push(`ACK 错误: ${pending.lastError}`)
    if (pending.lastError === 'pending_target_unverified') {
      lines.push('  旧记录缺少目标指纹，无法安全重试原 ACK；请核对后用 /inbox discard confirm'
        + ' 放弃并按当前目标重建，不会自动重发。')
    } else if (/pending_(base_url|inbox_id)_mismatch/.test(String(pending.lastError))) {
      lines.push('  恢复原目标配置后可用 /inbox ack 显式重试原记录；不会自动重发。')
    } else if (pending.retryable === false && pending.lastError) {
      lines.push('  自动重试已停止；请按错误码核对服务端状态后用 /inbox ack 显式重试'
        + ' 或 /inbox discard confirm。')
    }
    lines.push('⚠ 新面板注入已暂停；人工核对后用 /inbox discard confirm 放弃'
      + '（可能重复注入或遗留未确认条目）。')
  }
  if (status.injectionIntent) {
    lines.push('⚠ 注入意图未确认：followup 可能已发生也可能未发生；不会自动重试或 ACK。')
    lines.push('  人工核对目标会话与消息板后，用 /inbox discard confirm 明确处置。')
  }
  if (status.identityMismatch) {
    lines.push(`⚠ Inbox 数据库身份变化: ${status.identityMismatch.previous ?? 'unknown'}`
      + ` → ${status.identityMismatch.seen}；自动注入/ACK 已停止。`)
    lines.push('  确认这是预期换库/轮换后，用 /inbox accept-identity confirm。')
  }
  if (status.deferredPush?.reason) {
    lines.push(`⚠ 延迟/未注入的 push: rev=${status.deferredPush.revision}`
      + ` push=${status.deferredPush.pushRevision}（${status.deferredPush.reason}）`)
    lines.push('  不会自动重放；请人工核对消息板，确认后再处理后续 push。')
  }
  if (status.pendingPush) {
    const pendingPush = status.pendingPush
    lines.push(`已验证待注入: rev=${pendingPush.panelRevision}`
      + ` push=${pendingPush.pushRevision} items=${pendingPush.itemIds?.length ?? 0}`
      + ` target=${shortSession(pendingPush.targetSessionId) || '-'}`
      + ` identity=${pendingPush.inboxIdentity ?? '-'}`)
    const error = String(status.lastError ?? '')
    if (!pendingPush.targetSessionId) {
      lines.push('  ⚠ 记录没有目标会话，无法自动恢复；不会注入到别的会话。'
        + '请人工核对后用 /inbox discard confirm 放弃。')
    } else if (status.targetSessionId !== pendingPush.targetSessionId) {
      lines.push(`  ⚠ 当前绑定不是原目标 ${shortSession(pendingPush.targetSessionId)}；`
        + '请先 /inbox bind 回原会话（或 restore），再 /inbox ack 显式重试实例校验。'
        + '不会投递到当前会话。')
    } else if (error === 'stale_connection_instance') {
      lines.push('  ⚠ 当前服务端实例与已证明实例不同（如服务端正常重启）：不会自动注入。'
        + '确认是正常重启后，可 /inbox ack 显式重试一次：以当前运行实例作为新基线，'
        + '仍精确匹配 identity/目标/会话/revision/IDs/frozen；无法识别同身份克隆/回滚，'
        + '不匹配不会注入。也可 /inbox discard confirm 放弃（可能丢失该 push）。')
    } else if (error === 'connection_instance_unverified') {
      lines.push('  ⚠ 服务端不提供可验证的 processId（或无法安全判定）：不会注入；'
        + '/inbox ack 也无法建立实例证明。需先让服务端 /runtime 提供 processId 后再 '
        + '/inbox ack，或核对后用 /inbox discard confirm 放弃（可能丢失该 push）。')
    } else if (error === 'pending_push_instance_unverified') {
      lines.push('  暂时无法校验服务端实例：push 已保留，将按有界退避重试；'
        + '也可用 /inbox ack 立即重试。')
    } else if (error === 'pending_push_unverified') {
      lines.push('  ⚠ 服务端已越过该 pendingPush（条目可能已被外部 ACK）或面板不再匹配；'
        + '自动恢复会清除 stale 记录并重试当前面板。也可执行 /inbox recover confirm。')
    } else if (/pending_push_(target|session)_mismatch/.test(error)) {
      lines.push('  ⚠ 目标/会话与记录不一致：不会自动重放。恢复原目标配置后'
        + '用 /inbox ack 显式重试，或 /inbox discard confirm。')
    } else {
      lines.push('  恢复会重新 GET 并精确匹配；不匹配需人工核对，不会自动重放。')
      lines.push('  已确认服务端条目不存在/已 ACK 时，可用 /inbox recover confirm 强制清除 stale 记录。')
    }
  }
  if (status.freshVerification?.required === true) {
    lines.push(`· 新鲜面板校验待完成: 尝试=${status.freshVerification.attempts ?? 0}`
      + (status.freshVerification.lastError ? ` 最近=${status.freshVerification.lastError}` : ''))
  } else if (status.freshVerification?.lastError) {
    lines.push(`· 新鲜面板校验诊断: ${status.freshVerification.lastError}`)
  }
  if (status.blockedReason) lines.push(`阻断:     ${status.blockedReason}`)
  if (status.stateFile) lines.push(`状态文件: ${status.stateFile}`)
  lines.push(`配置:     ${status.config?.baseUrl ?? '-'} · token 来源 ${status.config?.tokenSource ?? 'unknown'}`)
  if (status.lastError) lines.push(`最近错误: ${status.lastError}`)
  if (!target) {
    if (status.stopped) {
      lines.push('已停止：不会注入。用 /inbox start 或 /inbox bind <sessionId> 恢复。')
    } else if (status.pendingAck || status.injectionIntent || status.pendingPush
      || status.freshVerification?.required === true) {
      lines.push('未绑定且当前有待处理记录：新面板不会注入；先按上面的提示处置记录，'
        + '再 /inbox bind <sessionId>。')
    } else if (status.blockedReason) {
      lines.push('未绑定且有阻断：当前不会注入；先解除阻断，再 /inbox bind <sessionId>。')
    } else {
      lines.push('⚠ 未绑定状态下，新面板会注入到第一个 idle 会话；要彻底停下用 /inbox detach')
    }
  }
  return lines.join('\n')
}

/**
 * `/inbox` slash command. Registered only when the host exposes a `commands` service; the
 * package-level `inject` is shared by the preset/self modules, so declaring `commands` there
 * would gate those modules on hosts that have no command surface at all (spec I5).
 */
export function registerInboxCommand(ctx, client) {
  const definition = {
    name: 'inbox',
    description: '查看或修改 BMA3 inbox 挂载（绑定会话 / SSE / 暂停 / 待 ACK 处置）',
    input: { hint: `[${COMMAND_VERBS.join('|')}]` },
    handler: async (invocation) => {
      const raw = String(invocation?.rawInput ?? '').trim()
      const parts = raw.length > 0 ? raw.split(/\s+/u) : []
      const verb = (parts[0] ?? 'status').toLowerCase()
      const argument = parts.slice(1).join(' ').trim()
      const callingSession = invocation?.agent?.id ? String(invocation.agent.id) : null
      try {
        switch (verb) {
          case 'status':
            return { kind: 'success', text: renderInboxStatus(client.status(), callingSession) }
          case 'bind': {
            const target = argument || callingSession
            if (!target) {
              return {
                kind: 'error',
                text: 'usage: /inbox bind <sessionId>\n'
                  + '（在会话输入框里直接 /inbox bind 会绑到当前会话；无参且无会话上下文时必须显式给 id）',
              }
            }
            client.bind(target)
            return {
              kind: 'success',
              text: `已绑定到 ${shortSession(target)}\n${renderInboxStatus(client.status(), callingSession)}`,
            }
          }
          case 'unbind':
            client.unbind()
            return {
              kind: 'success',
              text: '已解绑；自动 ACK 重试已暂停（记录仍保留，重新 bind 后恢复）。\n'
                + '⚠ SSE 仍在运行：未绑定状态下新面板会注入到第一个 idle 会话。\n'
                + '要彻底脱手用 /inbox detach（解绑+停止）。',
            }
          case 'detach':
            client.unbind()
            client.stop()
            return {
              kind: 'success',
              text: '已解绑并停止 SSE；自动 ACK 重试已停止（记录仍保留）。要重新接入：/inbox bind',
            }
          case 'restore':
            await client.restore(argument || client.status().targetSessionId)
            return { kind: 'success', text: `已恢复绑定\n${renderInboxStatus(client.status(), callingSession)}` }
          case 'pause':
            client.pause()
            return { kind: 'success', text: '已暂停推送注入（SSE 保持连接，面板继续累积）。' }
          case 'resume':
            client.resume()
            return { kind: 'success', text: '已恢复推送注入。' }
          case 'start': {
            client.start()
            const started = client.status()
            if (!started.running) {
              return {
                kind: 'error',
                text: `未启动：${started.blockedReason ?? started.lastError ?? 'state_write_failed'}`,
              }
            }
            if (started.targetError || started.config?.targetError
              || started.persistence?.error || started.persistence?.enabled === false) {
              return {
                kind: 'error',
                text: `未启动/不可安全运行：${started.targetError
                  ?? started.config?.targetError
                  ?? started.persistence?.error
                  ?? 'persistence disabled'}`,
              }
            }
            return {
              kind: 'success',
              text: started.blockedReason
                ? `SSE 已启动，但仍有阻断：${started.blockedReason}`
                : 'SSE 已启动。',
            }
          }
          case 'stop':
            client.stop()
            return { kind: 'success', text: 'SSE 已停止（绑定保留；自动 ACK 重试暂停）。' }
          case 'ack': {
            const before = client.status()
            const hadAck = Boolean(before.pendingAck)
            const beforeAttempts = before.pendingAck?.attempts ?? 0
            const hadPush = Boolean(before.pendingPush)
            await client.ack()
            const after = client.status()
            if (hadAck) {
              if (!after.pendingAck) {
                return { kind: 'success', text: 'ACK 已完成，待 ACK 记录已清除；新鲜面板校验已排队。' }
              }
              if ((after.pendingAck.attempts ?? 0) <= beforeAttempts) {
                return {
                  kind: 'error',
                  text: `未发出 ACK 请求：${after.pendingAck.lastError ?? '当前条件不允许发送'}。`
                    + '不会自动重发；恢复原目标配置后可用 /inbox ack 重试原记录，'
                    + '旧记录无目标指纹时只能 /inbox discard confirm 后重建。',
                }
              }
              return {
                kind: 'success',
                text: '已请求重试原待 ACK 记录；结果见 /inbox status。',
              }
            }
            if (hadPush && after.pendingPush) {
              const recovery = after.lastAckResult
              if (!recovery || recovery.kind !== 'push_retry') {
                return {
                  kind: 'error',
                  text: '未发起恢复校验；记录仍保留，详情见 /inbox status。',
                }
              }
              if (!recovery.ok) {
                return { kind: 'error', text: pushRetryFailureText(recovery.reason) }
              }
              return { kind: 'success', text: PUSH_RETRY_REQUESTED_TEXT }
            }
            if (hadPush && !after.pendingPush) {
              return { kind: 'success', text: '已验证待注入的 push 已恢复处理；结果见 /inbox status。' }
            }
            if (after.injectionIntent) {
              return {
                kind: 'error',
                text: '存在未确认的注入意图，不会发送 ACK；请人工核对后用 /inbox discard confirm。',
              }
            }
            return {
              kind: 'success',
              text: '当前没有待 ACK 记录；不会用 revision-only 推进游标。',
            }
          }
          case 'recover': {
            if (argument !== 'confirm') {
              return {
                kind: 'error',
                text: 'usage: /inbox recover confirm\n'
                  + '会清除 stale pendingPush/deferredPush 并重试当前面板；可能重复注入，先核对消息板。',
              }
            }
            client.recover(true)
            return { kind: 'success', text: '已清除 stale pendingPush/deferredPush，正在重新校验当前面板。' }
          }
          case 'discard': {
            if (argument !== 'confirm') {
              return {
                kind: 'error',
                text: '放弃待处理记录需要显式确认：/inbox discard confirm\n'
                  + '⚠ 可能造成重复注入，或留下未确认的服务端条目。',
              }
            }
            const text = client.discard(true)
            return { kind: 'success', text: `${text}\n${renderInboxStatus(client.status(), callingSession)}` }
          }
          case 'accept-identity': {
            if (argument !== 'confirm') {
              return {
                kind: 'error',
                text: '接受新的 inbox 数据库身份需要显式确认：/inbox accept-identity confirm',
              }
            }
            const text = client.acceptIdentity(true)
            return { kind: 'success', text: `${text}\n${renderInboxStatus(client.status(), callingSession)}` }
          }
          default:
            return {
              kind: 'error',
              text: `未知子命令: ${verb}\nusage: ${COMMAND_USAGE}`,
            }
        }
      } catch (error) {
        return { kind: 'error', text: `/inbox ${verb} 失败: ${error?.message ?? error}` }
      }
    },
  }
  // `ctx.commands` THROWS when `commands` is not in inject ("cannot get property ... without
  // inject"), and `?? ctx.get(...)` cannot rescue a throw. The optional-dependency pattern is
  // `ctx.inject`: a child fiber that waits for the service and never gates the parent module —
  // declaring `commands` in the package-level `inject` would gate preset/self too (spec I5).
  if (typeof ctx?.inject !== 'function') {
    ctx?.logger?.('bma3-dsh')?.warn?.('/inbox command skipped: ctx.inject unavailable')
    return null
  }
  try {
    return ctx.inject(['commands'], (child) => {
      child.effect(() => child.commands.register(definition))
    })
  } catch (error) {
    // A broken command surface must never take down the inbox module (spec I5).
    ctx?.logger?.('bma3-dsh')?.warn?.(`/inbox command skipped: ${error?.message ?? error}`)
    return null
  }
}

function inboxToolDefinition(client) {
  return {
      name: 'bma3_inbox',
      description:
        '查看/修改当前会话的 inbox 挂载状态。action=status 返回绑定会话、SSE 连接、游标、暂停标志、'
        + 'push 水位、待 ACK 摘要、身份/阻断原因与配置来源；bind 把面板绑到指定会话（省略则当前会话）并'
        + '持久化；restore 恢复持久化绑定并 resume 会话；unbind 解绑；pause/resume 暂停或恢复注入；'
        + 'start/stop 开关 SSE；ack 重试待 ACK；discard（需 confirm=true）放弃待 ACK/注入意图；'
        + 'accept-identity（需 confirm=true）接受变化的数据库身份。不带参数等价 status。',
      parameters: INBOX_SCHEMA,
      output: OUTPUT,
      execute: async (args = {}, exec = {}) => {
        const action = String(args.action ?? 'status').trim() || 'status'
        const callingSession = exec?.agent?.id ? String(exec.agent.id) : null
        let value
        let message = null
        switch (action) {
          case 'status':
            value = client.status()
            break
          case 'bind': {
            const target = String(args.sessionId ?? callingSession ?? '').trim()
            if (!target) throw new Error('sessionId 不能为空：调用方未提供会话 id 时请显式传参')
            value = client.bind(target)
            break
          }
          case 'restore': {
            value = await client.restore(args.sessionId ?? client.status().targetSessionId)
            break
          }
          case 'unbind':
            value = client.unbind()
            break
          case 'pause':
            value = client.pause()
            break
          case 'resume':
            value = client.resume()
            break
          case 'start':
            client.start()
            value = client.status()
            if (!value.running) {
              message = `未启动：${value.blockedReason ?? value.lastError ?? 'state_write_failed'}`
            } else if (value.targetError || value.config?.targetError
              || value.persistence?.error || value.persistence?.enabled === false) {
              message = `未启动/不可安全运行：${value.targetError
                ?? value.config?.targetError
                ?? value.persistence?.error
                ?? 'persistence disabled'}`
            } else if (value.blockedReason) {
              message = `SSE 已启动，但仍有阻断：${value.blockedReason}`
            } else {
              message = 'SSE 已启动。'
            }
            break
          case 'stop':
            client.stop()
            value = client.status()
            break
          case 'ack': {
            const before = client.status()
            const beforeAttempts = before.pendingAck?.attempts ?? 0
            const hadAck = Boolean(before.pendingAck)
            const hadPush = Boolean(before.pendingPush)
            value = await client.ack()
            if (hadAck) {
              if (!value.pendingAck) message = 'ACK 已完成，待 ACK 记录已清除。'
              else if ((value.pendingAck.attempts ?? 0) <= beforeAttempts) {
                message = `未发出 ACK 请求：${value.pendingAck.lastError
                  ?? '当前条件不允许发送'}。不会自动重发。`
              } else message = '已请求重试原待 ACK 记录；结果见 status。'
            } else if (hadPush && value.pendingPush) {
              const recovery = value.lastAckResult
              if (!recovery || recovery.kind !== 'push_retry') {
                message = '未发起恢复校验；记录仍保留，详情见 status。'
              } else if (!recovery.ok) {
                message = pushRetryFailureText(recovery.reason)
              } else {
                message = PUSH_RETRY_REQUESTED_TEXT
              }
            } else if (hadPush) {
              message = '已验证待注入的 push 已恢复处理；结果见 status。'
            } else if (value.injectionIntent) {
              message = '存在未确认的注入意图，不会发送 ACK；请人工核对后 discard。'
            } else {
              message = '当前没有待 ACK 记录；不会用 revision-only 推进游标。'
            }
            break
          }
          case 'recover':
            value = client.recover(args.confirm === true)
            message = '已清除 stale pendingPush/deferredPush，并排队从当前面板重新校验；不会直接注入或 ACK。'
            break
          case 'discard':
            message = client.discard(args.confirm === true)
            value = client.status()
            break
          case 'accept-identity':
            message = client.acceptIdentity(args.confirm === true)
            value = client.status()
            break
          default:
            throw new Error(`未知 action: ${action}`)
        }
        return {
          text: JSON.stringify({
            action,
            callingSession,
            ...(message ? { message } : {}),
            thisSessionIsTarget:
              value.targetSessionId != null && value.targetSessionId === callingSession,
            ...value,
          }, null, 2),
        }
      },
    }
}

/**
 * Register the model-facing `bma3_inbox` tool in every scope we can reach. DSH may expose the
 * profile tools registry before an agent exists, and a preset/agent scope may not inherit a
 * profile registration. Registration failure is diagnostic, not fatal: inbox/SSE/ACK keep
 * running, and we retry the global registration while an agent-scoped registration is attempted
 * whenever wake() resolves an agent.
 */
function registerInboxTool(ctx, client) {
  let disposed = false
  let timer = null
  let profileDisposer = null
  const agentDisposers = new Map()
  const scopes = new Set()
  const diagnostic = { registered: false, attempts: 0, lastError: null, at: null, scopes: [] }
  const delayFor = (attempt) => Math.min(250 * (2 ** Math.max(0, attempt - 1)), 2000)
  const publish = (lastError = diagnostic.lastError) => {
    diagnostic.lastError = lastError
    diagnostic.scopes = [...scopes]
    if (typeof client?.setToolRegistration === 'function') {
      diagnostic.at = new Date().toISOString()
      client.setToolRegistration(diagnostic)
    }
  }
  const getTools = (holder) => {
    try {
      return holder?.tools ?? holder?.get?.('tools')
    } catch {
      return undefined
    }
  }
  const remember = (scope, dispose) => {
    scopes.add(scope)
    diagnostic.registered = true
    diagnostic.lastError = null
    publish(null)
    return dispose
  }
  const attemptProfile = () => {
    if (disposed || scopes.has('profile')) return true
    diagnostic.attempts += 1
    const tools = getTools(ctx)
    if (!tools?.register) {
      publish('tools_service_unavailable')
    } else {
      try {
        const dispose = tools.register(inboxToolDefinition(client))
        profileDisposer = typeof dispose === 'function' ? dispose : null
        remember('profile', profileDisposer)
        return true
      } catch (error) {
        publish('tool_register_failed')
      }
    }
    if (diagnostic.attempts < 12) {
      timer = setTimeout(() => {
        timer = null
        attemptProfile()
      }, delayFor(diagnostic.attempts))
      timer.unref?.()
    }
    return false
  }
  const registerAgent = (agent) => {
    if (disposed || !agent) return false
    const id = String(agent.id ?? '').trim()
    if (!id) return false
    const scope = 'agent:' + id
    if (scopes.has(scope)) return true
    const tools = getTools(agent.ctx)
    if (!tools?.register) return false
    try {
      const dispose = tools.register(inboxToolDefinition(client))
      if (typeof dispose === 'function') agentDisposers.set(id, dispose)
      remember(scope, dispose)
      return true
    } catch {
      publish('agent_tool_register_failed')
      return false
    }
  }
  const dispose = () => {
    disposed = true
    if (timer) clearTimeout(timer)
    timer = null
    for (const disposeAgent of agentDisposers.values()) {
      try { disposeAgent() } catch { /* already gone */ }
    }
    agentDisposers.clear()
    if (typeof profileDisposer === 'function') {
      try { profileDisposer() } catch { /* already gone */ }
    }
    profileDisposer = null
  }
  attemptProfile()
  publish()
  return { registerAgent, dispose, status: () => ({ ...diagnostic, scopes: [...scopes] }) }
}

export function apply(ctx, config = {}) {
  // In Web the Inbox can mount before the session reader. Give the reader its own
  // injection scope so the rest of the Inbox can start as soon as the host is ready.
  let sessionQuery
  try {
    ctx?.inject?.(['sessionQuery'], (scope) => {
      const reader = scope.sessionQuery
      scope.effect(() => {
        sessionQuery = reader
        return () => {
          if (sessionQuery === reader) sessionQuery = undefined
        }
      })
    })
  } catch {
    ctx?.logger?.('bma3-dsh')?.warn?.('context push: session reader injection failed')
  }
  const client = createInboxClient(ctx, config, () => sessionQuery ?? ctx?.get?.('sessionQuery'))
  const toolRegistration = registerInboxTool(ctx, client)

  if (typeof client.attachToolRegistrar === 'function') {
    client.attachToolRegistrar(toolRegistration.registerAgent)
  }

  // Slash-command surface: /inbox status|bind|unbind|detach|... (D-057).
  registerInboxCommand(ctx, client)

  // A persisted binding wins over autoStart: "bound once, bound forever" (D-045).
  const initial = client.status()
  if (initial.lockError || initial.stateError || initial.persistence?.enabled === false) {
    ctx?.logger?.('bma3-dsh')?.warn?.(`inbox disabled: ${initial.blockedReason}`)
  } else if (initial.targetSessionId && !initial.stopped) {
    void client.restore(initial.targetSessionId).catch((error) => {
      ctx?.logger?.('bma3-dsh')?.warn?.(`inbox restore failed: ${error?.message ?? error}`)
    })
  } else if (config.autoStart !== false && !initial.stopped) {
    try {
      client.start()
    } catch (error) {
      ctx?.logger?.('bma3-dsh')?.warn?.(`inbox start failed: ${error?.message ?? error}`)
    }
  }
  ctx?.effect?.(() => () => {
    toolRegistration.dispose()
    client.dispose()
  })
  return client
}
