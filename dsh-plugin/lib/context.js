/**
 * Push the session context DSH already holds into the jar's rolling copy.
 *
 * This never calls a model: `ctx.sessionQuery` reads the append-only session log the host already
 * wrote, `extractSessionEventText` turns one event into its searchable text, and the batch goes to
 * `POST /v1/inboxes/{id}/plugin/context`. Cost is one HTTP request per tick plus one read per new
 * event, and the plugin keeps no state on disk — after a restart it asks the jar for its watermark
 * (`GET .../plugin/context`) and resumes from there.
 *
 * Whitelist, not filter: only `user/message`, `assistant/message` and `tool/result` are carried,
 * and only while the host still calls them `current`. Everything else (structural events, request
 * envelopes, stream fragments, and anything already shadowed by compaction) stays in DSH, where the
 * Agent can still read it through the native session tools.
 */
import { pathToFileURL } from 'node:url'
import { hostToolEntry } from './host-tools.js'

/** DSH event type → the copy's kind. Anything not listed here is not carried. */
export const CONTEXT_KIND_BY_TYPE = Object.freeze({
  'user/message': 'user',
  'assistant/message': 'assistant',
  'tool/result': 'tool',
})

/** Client-side limits; the jar enforces its own copy of the same numbers. */
export const CONTEXT_DEFAULTS = Object.freeze({
  maxBatch: 50,
  maxEntries: 200,
  maxEntryChars: 10240,
})

let extraction = null

/**
 * `extractSessionEventText` lives in the host tree (profiles do not resolve `@deepseek-ai/*`).
 * Resolved lazily and once per process; a miss simply disables pushing instead of throwing.
 * @param {string} entryPoint running host entry (`process.argv[1]`).
 */
export function loadEventTextExtractor(entryPoint = process.argv[1]) {
  if (extraction === null) {
    extraction = (async () => {
      const entry = hostToolEntry('@deepseek-ai/dsh-session-query', entryPoint)
      if (entry === undefined) return undefined
      const module = await import(pathToFileURL(entry).href)
      return typeof module?.extractSessionEventText === 'function'
        ? module.extractSessionEventText
        : undefined
    })().catch(() => undefined)
  }
  return extraction
}

/**
 * One pusher per inbox client.
 *
 * @param {object} options
 * @param {{listEvents?: Function, readEvent?: Function}} options.sessionQuery host query service
 * @param {Function} [options.extractText] `(event) => string`, when already resolved
 * @param {Function} [options.resolveExtractText] `() => Promise<Function|undefined>`, resolved on
 *   the first tick — the host module is only importable from the host tree, and a profile reload
 *   must not turn a transient miss into a permanently disabled pusher
 * @param {Function} options.fetchImpl fetch implementation
 * @param {Function} options.url `(suffix) => string|null` panel URL for this inbox
 * @param {Function} options.headers `() => object` auth headers
 * @param {Function} options.sessionId `() => string` currently bound DSH session
 * @param {Function} [options.now] clock, injectable for tests
 * @param {object} [options.limits] overrides for {@link CONTEXT_DEFAULTS}
 */
export function createContextPusher(options = {}) {
  const {
    sessionQuery,
    extractText,
    resolveExtractText,
    fetchImpl,
    url,
    headers,
    sessionId,
    now = () => Date.now(),
    limits = {},
  } = options
  const config = { ...CONTEXT_DEFAULTS, ...limits }

  let session = ''
  let watermark = null
  let inFlight = false
  let state = {
    session: '',
    watermark: null,
    batches: 0,
    pushed: 0,
    error: null,
    at: 0,
  }

  const isoTime = (value) => {
    const number = Number(value)
    if (!Number.isFinite(number) || number <= 0) return null
    try {
      return new Date(number).toISOString()
    } catch {
      return null
    }
  }

  const cut = (text) => {
    const limit = Math.max(1, Number(config.maxEntryChars))
    if (text.length <= limit) return text
    return text.slice(0, limit)
  }

  /** Ask the jar where it is, so a restarted plugin does not replay the whole session. */
  const restoreWatermark = async (current) => {
    const target = url(`/plugin/context?after=0&limit=1&sessionId=${encodeURIComponent(current)}`)
    if (!target) return 0
    try {
      const response = await fetchImpl(target, { method: 'GET', headers: { ...headers() } })
      if (!response?.ok) return 0
      const body = await response.json()
      const seq = Number(body?.maxEventSeq ?? 0)
      return Number.isFinite(seq) && seq > 0 ? seq : 0
    } catch {
      return 0
    }
  }

  const post = async (current, capturedThroughSeq, entries) => {
    const target = url('/plugin/context')
    if (!target) return false
    try {
      const response = await fetchImpl(target, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...headers() },
        body: JSON.stringify({
          sessionId: current,
          capturedThroughSeq,
          entries,
          sentAt: new Date(now()).toISOString(),
        }),
      })
      return response?.ok === true
    } catch {
      return false
    }
  }

  return {
    /**
     * One push attempt. Safe to call often: it returns immediately while a previous tick still
     * runs, and a failed tick leaves the watermark untouched so the next one retries the range.
     */
    async tick() {
      if (inFlight) return { ...state }
      const current = String(sessionId?.() ?? '').trim()
      if (!current) {
        session = ''
        watermark = null
        state = { ...state, session: '', watermark: null }
        return { ...state }
      }
      if (current !== session) {
        session = current
        watermark = null
      }
      if (!sessionQuery || typeof sessionQuery.listEvents !== 'function') return { ...state }
      inFlight = true
      try {
        let extract = typeof extractText === 'function' ? extractText : undefined
        if (extract === undefined && typeof resolveExtractText === 'function') {
          extract = await resolveExtractText().catch(() => undefined)
        }
        // Pushing without the extractor would land correct row counts with empty bodies: a copy
        // that looks complete and says nothing. Refuse the batch instead and retry next tick.
        if (typeof extract !== 'function') {
          state = { ...state, session: current, error: 'extraction_unavailable' }
          return { ...state }
        }
        if (watermark === null) watermark = await restoreWatermark(current)
        const records = await sessionQuery.listEvents(current)
        const fresh = (Array.isArray(records) ? records : [])
          .filter((record) => Number(record?.seq) > watermark
            && record?.surface === 'current'
            && CONTEXT_KIND_BY_TYPE[record?.type] !== undefined)
          .sort((left, right) => Number(left.seq) - Number(right.seq))
        if (fresh.length === 0) {
          state = { ...state, session: current, watermark, error: null }
          return { ...state }
        }
        const batch = fresh.slice(0, Math.max(1, Number(config.maxBatch)))
        const entries = []
        for (const record of batch) {
          let text = ''
          try {
            const window = await sessionQuery.readEvent({ sessionId: current, seq: record.seq })
            text = cut(String(extract(window?.target) ?? '').trim())
          } catch {
            // An event we cannot read still counts as processed; the jar would otherwise see a
            // hole that never closes. It lands with an empty body and chars=0.
            text = ''
          }
          entries.push({
            eventSeq: Number(record.seq),
            kind: CONTEXT_KIND_BY_TYPE[record.type],
            speaker: '',
            at: isoTime(record.time),
            text,
          })
        }
        const captured = Math.max(...batch.map((record) => Number(record.seq)))
        if (!(await post(current, captured, entries))) {
          state = { ...state, session: current, watermark, error: 'push_failed', at: now() }
          return { ...state }
        }
        watermark = captured
        state = {
          session: current,
          watermark,
          batches: state.batches + 1,
          pushed: state.pushed + entries.length,
          error: null,
          at: now(),
        }
        return { ...state }
      } catch (error) {
        state = { ...state, session: current, error: 'tick_failed', at: now() }
        return { ...state }
      } finally {
        inFlight = false
      }
    },

    /** Counters for the heartbeat diagnostics: never a body, never a token. */
    snapshot() {
      return { ...state }
    },
  }
}
