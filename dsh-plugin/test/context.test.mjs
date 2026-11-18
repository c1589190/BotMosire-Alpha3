import assert from 'node:assert/strict'
import { test } from 'node:test'

import {
  CONTEXT_DEFAULTS,
  CONTEXT_KIND_BY_TYPE,
  createContextPusher,
} from '../lib/context.js'

/** A session-query stand-in: fixed records plus one text per seq. */
function fakeQuery(records, texts = {}) {
  const reads = []
  return {
    reads,
    async listEvents(sessionId) {
      assert.equal(sessionId, 's-1')
      return records
    },
    async readEvent(request) {
      reads.push(request.seq)
      const record = records.find((row) => row.seq === request.seq)
      return { target: { seq: request.seq, type: record?.type, text: texts[request.seq] } }
    },
  }
}

/** A fetch stand-in that records every request and answers `{ok}`. */
function fakeFetch({ ok = true, maxEventSeq = 0 } = {}) {
  const calls = []
  const impl = async (url, init = {}) => {
    calls.push({ url, method: init.method ?? 'GET', body: init.body ? JSON.parse(init.body) : null })
    const body = init.method === 'POST' ? { stored: 1 } : { entries: [], count: 0, maxEventSeq }
    return { ok, status: ok ? 200 : 500, async json() { return body } }
  }
  return { impl, calls }
}

const base = {
  url: (suffix) => `http://jar/v1/inboxes/bma3${suffix}`,
  headers: () => ({ Authorization: 'Bearer t' }),
  sessionId: () => 's-1',
  fetchImpl: undefined,
}

const extractText = (event) => event?.text ?? ''

test('only whitelisted types are carried, and only while current', async () => {
  const records = [
    { seq: 1, type: 'user/message', time: 1000, surface: 'current' },
    { seq: 2, type: 'request/header', time: 1001, surface: 'current' },
    { seq: 3, type: 'assistant/message', time: 1002, surface: 'shadowed' },
    { seq: 4, type: 'tool/result', time: 1003, surface: 'current' },
  ]
  const query = fakeQuery(records, { 1: '晚上好', 4: 'EXIT 0' })
  const fetch = fakeFetch()
  const pusher = createContextPusher({
    ...base, sessionQuery: query, extractText, fetchImpl: fetch.impl,
  })

  await pusher.tick()

  const push = fetch.calls.find((call) => call.method === 'POST')
  assert.ok(push, 'a batch was pushed')
  assert.deepEqual(push.body.entries.map((entry) => entry.eventSeq), [1, 4])
  assert.deepEqual(push.body.entries.map((entry) => entry.kind), ['user', 'tool'])
  assert.equal(push.body.entries[0].text, '晚上好')
  assert.equal(push.body.capturedThroughSeq, 4)
  assert.equal(push.body.sessionId, 's-1')
  // The shadowed event is never read at all: the copy must not resurrect compacted context.
  assert.deepEqual(query.reads, [1, 4])
  assert.equal(pusher.snapshot().watermark, 4)
  assert.equal(pusher.snapshot().pushed, 2)
})

test('the watermark makes a replay a no-op', async () => {
  const records = [{ seq: 7, type: 'user/message', time: 1000, surface: 'current' }]
  const query = fakeQuery(records, { 7: 'a' })
  const fetch = fakeFetch()
  const pusher = createContextPusher({
    ...base, sessionQuery: query, extractText, fetchImpl: fetch.impl,
  })

  await pusher.tick()
  await pusher.tick()

  const posts = fetch.calls.filter((call) => call.method === 'POST')
  assert.equal(posts.length, 1)
  assert.equal(pusher.snapshot().batches, 1)
})

test('a failed push keeps the watermark so the range is retried', async () => {
  const records = [{ seq: 3, type: 'user/message', time: 1000, surface: 'current' }]
  const query = fakeQuery(records, { 3: 'a' })
  const failing = fakeFetch({ ok: false })
  const pusher = createContextPusher({
    ...base, sessionQuery: query, extractText, fetchImpl: failing.impl,
  })

  await pusher.tick()
  assert.equal(pusher.snapshot().error, 'push_failed')
  assert.equal(pusher.snapshot().watermark, 0)

  const working = fakeFetch()
  const retry = createContextPusher({
    ...base, sessionQuery: query, extractText, fetchImpl: working.impl,
  })
  // Same store, fresh pusher: the watermark comes back from the jar, not from history.
  await retry.tick()
  assert.equal(working.calls.filter((call) => call.method === 'GET').length, 1)
})

test('the jar watermark is restored instead of replaying the session', async () => {
  const records = [
    { seq: 1, type: 'user/message', time: 1000, surface: 'current' },
    { seq: 9, type: 'assistant/message', time: 1009, surface: 'current' },
  ]
  const query = fakeQuery(records, { 1: 'old', 9: 'new' })
  const fetch = fakeFetch({ maxEventSeq: 8 })
  const pusher = createContextPusher({
    ...base, sessionQuery: query, extractText, fetchImpl: fetch.impl,
  })

  await pusher.tick()

  assert.match(fetch.calls[0].url, /\/plugin\/context\?after=0&limit=1&sessionId=s-1$/)
  const push = fetch.calls.find((call) => call.method === 'POST')
  assert.deepEqual(push.body.entries.map((entry) => entry.eventSeq), [9])
  assert.deepEqual(query.reads, [9])
})

test('a batch is capped by maxBatch and the rest waits for the next tick', async () => {
  const records = Array.from({ length: 5 }, (_, index) => ({
    seq: index + 1, type: 'user/message', time: 1000 + index, surface: 'current',
  }))
  const texts = Object.fromEntries(records.map((record) => [record.seq, `t${record.seq}`]))
  const query = fakeQuery(records, texts)
  const fetch = fakeFetch()
  const pusher = createContextPusher({
    ...base, sessionQuery: query, extractText, fetchImpl: fetch.impl, limits: { maxBatch: 2 },
  })

  await pusher.tick()
  assert.deepEqual(pusher.snapshot().watermark, 2)
  await pusher.tick()
  await pusher.tick()

  const seqs = fetch.calls
    .filter((call) => call.method === 'POST')
    .flatMap((call) => call.body.entries.map((entry) => entry.eventSeq))
  assert.deepEqual(seqs, [1, 2, 3, 4, 5])
})

test('an entry is cut at maxEntryChars', async () => {
  const records = [{ seq: 1, type: 'user/message', time: 1000, surface: 'current' }]
  const query = fakeQuery(records, { 1: 'x'.repeat(50) })
  const fetch = fakeFetch()
  const pusher = createContextPusher({
    ...base, sessionQuery: query, extractText, fetchImpl: fetch.impl, limits: { maxEntryChars: 8 },
  })

  await pusher.tick()

  const push = fetch.calls.find((call) => call.method === 'POST')
  assert.equal(push.body.entries[0].text, 'x'.repeat(8))
})

test('an unreadable event still lands so the jar sees no permanent hole', async () => {
  const records = [{ seq: 2, type: 'tool/result', time: 1000, surface: 'current' }]
  const query = {
    async listEvents() { return records },
    async readEvent() { throw new Error('gone') },
  }
  const fetch = fakeFetch()
  const pusher = createContextPusher({
    ...base, sessionQuery: query, extractText, fetchImpl: fetch.impl,
  })

  await pusher.tick()

  const push = fetch.calls.find((call) => call.method === 'POST')
  assert.equal(push.body.entries[0].text, '')
  assert.equal(pusher.snapshot().watermark, 2)
})

test('an unresolved extractor refuses the batch instead of pushing empty bodies', async () => {
  const records = [{ seq: 1, type: 'user/message', time: 1000, surface: 'current' }]
  const query = fakeQuery(records, { 1: 'a' })
  const fetch = fakeFetch()
  const pusher = createContextPusher({
    ...base,
    sessionQuery: query,
    resolveExtractText: async () => undefined,
    fetchImpl: fetch.impl,
  })

  await pusher.tick()

  assert.equal(fetch.calls.filter((call) => call.method === 'POST').length, 0)
  assert.deepEqual(query.reads, [])
  assert.equal(pusher.snapshot().error, 'extraction_unavailable')
})

test('a lazily resolved extractor starts working without a reload', async () => {
  const records = [{ seq: 1, type: 'user/message', time: 1000, surface: 'current' }]
  const query = fakeQuery(records, { 1: 'later' })
  const fetch = fakeFetch()
  let resolutions = 0
  const pusher = createContextPusher({
    ...base,
    sessionQuery: query,
    resolveExtractText: async () => {
      resolutions += 1
      return resolutions === 1 ? undefined : extractText
    },
    fetchImpl: fetch.impl,
  })

  await pusher.tick()
  assert.equal(pusher.snapshot().error, 'extraction_unavailable')
  await pusher.tick()

  const push = fetch.calls.find((call) => call.method === 'POST')
  assert.equal(push.body.entries[0].text, 'later')
  assert.equal(pusher.snapshot().error, null)
})

test('rebinding to another session resets the watermark', async () => {
  const records = [{ seq: 4, type: 'user/message', time: 1000, surface: 'current' }]
  const query = {
    async listEvents() { return records },
    async readEvent(request) { return { target: { text: `t${request.seq}` } } },
  }
  const fetch = fakeFetch({ maxEventSeq: 0 })
  let bound = 's-1'
  const pusher = createContextPusher({
    ...base, sessionQuery: query, extractText, fetchImpl: fetch.impl, sessionId: () => bound,
  })

  await pusher.tick()
  bound = 's-2'
  await pusher.tick()

  const pushes = fetch.calls.filter((call) => call.method === 'POST')
  assert.equal(pushes.length, 2)
  assert.equal(pushes[1].body.sessionId, 's-2')
  assert.equal(pusher.snapshot().session, 's-2')
})

test('an unbound session pushes nothing and clears the watermark', async () => {
  const records = [{ seq: 1, type: 'user/message', time: 1000, surface: 'current' }]
  const query = fakeQuery(records, { 1: 'a' })
  const fetch = fakeFetch()
  let bound = 's-1'
  const pusher = createContextPusher({
    ...base, sessionQuery: query, extractText, fetchImpl: fetch.impl, sessionId: () => bound,
  })

  await pusher.tick()
  bound = ''
  await pusher.tick()

  assert.equal(pusher.snapshot().watermark, null)
  assert.equal(fetch.calls.filter((call) => call.method === 'POST').length, 1)
})

test('a host without sessionQuery is silent rather than broken', async () => {
  const fetch = fakeFetch()
  const pusher = createContextPusher({ ...base, extractText, fetchImpl: fetch.impl })

  await pusher.tick()

  assert.equal(fetch.calls.length, 0)
  assert.equal(pusher.snapshot().error, null)
})

test('the snapshot carries counters only', async () => {
  const records = [{ seq: 1, type: 'user/message', time: 1000, surface: 'current' }]
  const query = fakeQuery(records, { 1: 'secret body' })
  const fetch = fakeFetch()
  const pusher = createContextPusher({
    ...base, sessionQuery: query, extractText, fetchImpl: fetch.impl,
  })

  await pusher.tick()

  const snapshot = JSON.stringify(pusher.snapshot())
  assert.equal(snapshot.includes('secret body'), false)
  assert.deepEqual(Object.keys(pusher.snapshot()).sort(),
    ['at', 'batches', 'error', 'pushed', 'session', 'watermark'])
})

test('the documented whitelist and defaults are the ones the jar enforces', () => {
  assert.deepEqual(Object.keys(CONTEXT_KIND_BY_TYPE).sort(),
    ['assistant/message', 'tool/result', 'user/message'])
  assert.deepEqual(CONTEXT_DEFAULTS, { maxBatch: 50, maxEntries: 200, maxEntryChars: 10240 })
})
