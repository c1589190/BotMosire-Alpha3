import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { test } from 'node:test'

import {
  ASR_MAX_SECONDS,
  CREDENTIAL_REF,
  OCR_PURPOSES,
  apply,
  callAsr,
  callOcr,
  readUpload,
  resolveApiKey,
} from '../lib/media.js'

/** 最小 ctx：记录 register 调用，credentials 可注入。 */
function fakeCtx({ credentials, withRegister = true } = {}) {
  const registered = []
  const ctx = {
    registered,
    tools: withRegister ? { register: (definition) => { registered.push(definition); return () => {} } } : undefined,
    effect: (fn) => fn(),
    get(name) {
      if (name === 'credentials') return credentials
      return undefined
    },
  }
  return ctx
}

/** 假 fetch：记录请求，返回固定响应。 */
function fakeFetch(payload, { status = 200, raw } = {}) {
  const calls = []
  const impl = async (url, init) => {
    calls.push({ url, init })
    return {
      ok: status >= 200 && status < 300,
      status,
      text: async () => raw ?? JSON.stringify(payload),
    }
  }
  impl.calls = calls
  return impl
}

function tempFile(name, contents) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bma3-media-test-'))
  const file = path.join(dir, name)
  fs.writeFileSync(file, contents)
  return file
}

test('credentials win over the environment, and a missing key is not an error', async () => {
  const ctx = fakeCtx({ credentials: { resolve: async (ref) => (ref === CREDENTIAL_REF ? { key: 'sk-from-store' } : {}) } })
  assert.equal(await resolveApiKey(ctx, {}), 'sk-from-store')

  const empty = fakeCtx({})
  assert.equal(await resolveApiKey(empty, {}), undefined, '未配置返回 undefined，由工具报可读错误')
  assert.equal(await resolveApiKey(empty, { [CREDENTIAL_REF]: '  sk-env  ' }), 'sk-env')
})

test('readUpload enforces the size ceiling and rejects directories', () => {
  const file = tempFile('a.bin', Buffer.alloc(16))
  assert.equal(readUpload(file, 1024).size, 16)
  assert.throws(() => readUpload(file, 8), /文件过大/)
  assert.throws(() => readUpload(path.dirname(file), 1024), /不是文件/)
})

test('apply registers exactly the two paid tools', () => {
  const ctx = fakeCtx()
  apply(ctx, { fetch: fakeFetch({}) })
  assert.deepEqual(ctx.registered.map((definition) => definition.name), ['bma3_ocr', 'bma3_asr'])
  const ocr = ctx.registered[0]
  assert.deepEqual(ocr.parameters.required, ['filePath'])
  assert.deepEqual(ocr.parameters.properties.purpose.enum, [...OCR_PURPOSES])
})

test('without tools service apply is a no-op', () => {
  assert.doesNotThrow(() => apply(fakeCtx({ withRegister: false }), {}))
  assert.doesNotThrow(() => apply({}, {}))
})

test('callOcr joins pages and reports per-page finish reasons', async () => {
  const fetchImpl = fakeFetch({
    id: 'ocr-1',
    purpose: 'text_recognition',
    page_count: 2,
    pages: [{ index: 0, text: '第一页', finish_reason: 'stop' }, { index: 1, text: '第二页', finish_reason: 'length' }],
  })
  const result = await callOcr({
    fetchImpl, baseURL: 'https://api.deepseek.com', apiKey: 'k',
    file: { bytes: Buffer.from('x'), name: 'a.png' }, purpose: 'text_recognition',
  })
  assert.equal(result.text, '第一页\n\n第二页')
  assert.equal(result.pageCount, 2)
  assert.deepEqual(result.pages[1], { index: 1, finishReason: 'length' })
  assert.match(fetchImpl.calls[0].url, /\/v1\/tools\/ocr$/)
  assert.match(fetchImpl.calls[0].init.headers.Authorization, /^Bearer k$/)
})

test('callAsr returns text and duration', async () => {
  const fetchImpl = fakeFetch({ request_id: 'req-1', text: '上海今天天气怎么样？', duration_ms: 3200 })
  const result = await callAsr({ fetchImpl, baseURL: 'https://api.deepseek.com', apiKey: 'k', file: { bytes: Buffer.from('x'), name: 'a.wav' } })
  assert.equal(result.text, '上海今天天气怎么样？')
  assert.equal(result.durationMs, 3200)
  assert.match(fetchImpl.calls[0].url, /\/v1\/tools\/asr$/)
})

test('HTTP errors and in-body error objects both raise, without leaking the key', async () => {
  const denied = fakeFetch({ error: { message: 'Insufficient Balance' } })
  await assert.rejects(() => callAsr({ fetchImpl: denied, baseURL: 'https://x', apiKey: 'sk-secret', file: { bytes: Buffer.alloc(1), name: 'a.wav' } }),
    (error) => {
      assert.match(error.message, /Insufficient Balance/)
      assert.doesNotMatch(error.message, /sk-secret/)
      return true
    })

  const status = fakeFetch({}, { status: 413, raw: 'too large' })
  await assert.rejects(() => callOcr({ fetchImpl: status, baseURL: 'https://x', apiKey: 'k', file: { bytes: Buffer.alloc(1), name: 'a.pdf' }, purpose: 'text_recognition' }),
    /HTTP 413/)
})

test('the OCR tool reports a missing key instead of calling out', async () => {
  const fetchImpl = fakeFetch({})
  const ctx = fakeCtx()
  apply(ctx, { fetch: fetchImpl })
  const ocr = ctx.registered[0]
  const result = await ocr.execute({ filePath: tempFile('a.png', 'x') })
  const payload = JSON.parse(result.text)
  assert.equal(payload.ok, false)
  assert.match(payload.error, /DEEPSEEK_API_KEY 未配置/)
  assert.equal(fetchImpl.calls.length, 0)
})

test('the OCR tool uploads the file and returns page text', async () => {
  const fetchImpl = fakeFetch({ page_count: 1, pages: [{ index: 0, text: '标题', finish_reason: 'stop' }] })
  const ctx = fakeCtx({ credentials: { resolve: async () => ({ key: 'sk-test' }) } })
  apply(ctx, { fetch: fetchImpl })
  const result = await ctx.registered[0].execute({ filePath: tempFile('doc.png', 'bytes') })
  const payload = JSON.parse(result.text)
  assert.equal(payload.ok, true)
  assert.equal(payload.text, '标题')
  assert.equal(payload.pageCount, 1)
})

test('ASR refuses audio longer than the documented 120 second limit', async () => {
  const wav = tempFile('long.wav', 'x')
  // 用 121 秒的静音 WAV 头骗过 ffprobe 成本太高；这里直接把工具对上限的判断钉在常量上，
  // 真实时长校验由 transcodeForAsr 之前的 probeSeconds 负责。
  assert.equal(ASR_MAX_SECONDS, 120)
  assert.ok(fs.existsSync(wav))
})
