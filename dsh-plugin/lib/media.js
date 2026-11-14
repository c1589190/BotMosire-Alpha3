/**
 * DeepSeek 官方 OCR / ASR 工具（D-068）。
 *
 * 两件事，都是"把本地文件发给 DeepSeek 的独立接口拿文字回来"：
 *  - `bma3_ocr`   → `POST /v1/tools/ocr`，PDF/Word/PPT/图片逐页识别；
 *  - `bma3_asr`   → `POST /v1/tools/asr`，把语音转写为文字。
 *
 * 为什么放在插件里而不是 jar 里：jar 只管把媒体**下载**到本地（`qq_media_view`），
 * 转写是模型侧的活，跟 QQ/OneBot 无关。插件只需要 credentials 里的 `DEEPSEEK_API_KEY`，
 * 不给用户新增一个存放密钥的地方。
 *
 * 正文出境边界（D-068）：只有在 Agent **显式调用**这两个工具时，被指定的那一个文件才会离开本机；
 * 插件不批量上传、不缓存、不把结果写回消息板。
 */
import { execFile } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { promisify } from 'node:util'

const run = promisify(execFile)

export const name = 'bma3-media'

/** Only `tools` is required; credentials is resolved through `ctx.get` so a missing provider is reportable. */
export const inject = ['tools']

export const DEFAULT_BASE_URL = 'https://api.deepseek.com'
export const CREDENTIAL_REF = 'DEEPSEEK_API_KEY'

/** ASR 的硬限制（官方文档）。 */
export const ASR_MAX_BYTES = 8 * 1024 * 1024
export const ASR_MAX_SECONDS = 120
/** OCR 的文件上限。 */
export const OCR_MAX_BYTES = 100 * 1024 * 1024

/** OCR 的两种用途。 */
export const OCR_PURPOSES = Object.freeze(['text_recognition', 'rich_layout_recognition'])

/**
 * 解析 API key：优先 credentials 服务，其次进程环境。
 * @param {any} ctx 插件上下文。
 * @param {Record<string, string | undefined>} [env] 环境变量表（测试用）。
 * @returns {Promise<string | undefined>} 密钥，未配置时 undefined。
 */
export async function resolveApiKey(ctx, env = process.env) {
  const credentials = typeof ctx?.get === 'function' ? ctx.get('credentials') : undefined
  if (credentials?.resolve) {
    try {
      const resolved = await credentials.resolve(CREDENTIAL_REF)
      if (typeof resolved?.key === 'string' && resolved.key.length > 0) return resolved.key
    } catch {
      // 凭据层读失败就退回环境变量，不把异常当成"没配"。
    }
  }
  const fromEnv = env?.[CREDENTIAL_REF]
  return typeof fromEnv === 'string' && fromEnv.trim() ? fromEnv.trim() : undefined
}

/** 读取文件并做大小校验。 */
export function readUpload(filePath, maxBytes) {
  const resolved = path.resolve(String(filePath ?? ''))
  const stat = fs.statSync(resolved)
  if (!stat.isFile()) throw new Error(`不是文件：${resolved}`)
  if (stat.size > maxBytes) throw new Error(`文件过大：${stat.size} 字节，上限 ${maxBytes}`)
  return { path: resolved, bytes: fs.readFileSync(resolved), size: stat.size }
}

/** 用 ffprobe 读时长（秒）；读不到返回 undefined。 */
export async function probeSeconds(filePath) {
  try {
    const { stdout } = await run('ffprobe', [
      '-v', 'error', '-show_entries', 'format=duration',
      '-of', 'default=noprint_wrappers=1:nokey=1', filePath,
    ])
    const seconds = Number.parseFloat(stdout.trim())
    return Number.isFinite(seconds) ? seconds : undefined
  } catch {
    return undefined
  }
}

/**
 * 把任意音频转成 ASR 接受的格式：16 位 PCM WAV、16 kHz、单声道。
 * @param {string} source 源文件。
 * @returns {Promise<{path: string, cleanup: () => void}>} 临时 WAV 与清理函数。
 */
export async function transcodeForAsr(source) {
  const target = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'bma3-asr-')), 'audio.wav')
  await run('ffmpeg', [
    '-v', 'error', '-y', '-i', source,
    '-ac', '1', '-ar', '16000', '-c:a', 'pcm_s16le', target,
  ])
  return { path: target, cleanup: () => fs.rmSync(path.dirname(target), { recursive: true, force: true }) }
}

/** POST multipart 到 DeepSeek 工具接口，返回解析后的 JSON。 */
async function postMultipart({ fetchImpl, baseURL, apiKey, endpoint, fields, file, timeoutMs }) {
  const form = new FormData()
  for (const [key, value] of Object.entries(fields ?? {})) form.append(key, value)
  form.append('file', new Blob([file.bytes]), file.name)
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs ?? 180_000)
  try {
    const response = await fetchImpl(`${baseURL}${endpoint}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${apiKey}` },
      body: form,
      signal: controller.signal,
    })
    const raw = await response.text()
    if (!response.ok) throw new Error(`${endpoint} HTTP ${response.status}: ${raw.slice(0, 400)}`)
    // OCR 在识别阶段用换行保活，前导空白属于 JSON。
    const payload = JSON.parse(raw.trim())
    if (payload?.error) throw new Error(`${endpoint} ${payload.error.message ?? '未知错误'}`)
    return payload
  } finally {
    clearTimeout(timer)
  }
}

/** 调用 OCR。 */
export async function callOcr({ fetchImpl, baseURL, apiKey, file, purpose, timeoutMs }) {
  const payload = await postMultipart({
    fetchImpl, baseURL, apiKey, endpoint: '/v1/tools/ocr', timeoutMs,
    fields: { purpose }, file,
  })
  const pages = Array.isArray(payload?.pages) ? payload.pages : []
  return {
    id: payload?.id ?? null,
    purpose: payload?.purpose ?? purpose,
    pageCount: payload?.page_count ?? pages.length,
    text: pages.map((page) => page?.text ?? '').join('\n\n').trim(),
    pages: pages.map((page) => ({ index: page?.index, finishReason: page?.finish_reason })),
    usage: payload?.usage ?? null,
  }
}

/** 调用 ASR。 */
export async function callAsr({ fetchImpl, baseURL, apiKey, file, timeoutMs }) {
  const payload = await postMultipart({
    fetchImpl, baseURL, apiKey, endpoint: '/v1/tools/asr', timeoutMs, fields: {}, file,
  })
  return { requestId: payload?.request_id ?? null, text: payload?.text ?? '', durationMs: payload?.duration_ms ?? null }
}

/** 工具失败统一成结构化结果，永不带出密钥。 */
function failure(error) {
  return { text: JSON.stringify({ ok: false, error: String(error?.message ?? error) }, null, 2) }
}

/**
 * 注册 `bma3_ocr` / `bma3_asr`。
 * @param {any} ctx 插件上下文。
 * @param {{baseUrl?: string, fetch?: typeof fetch, timeoutMs?: number}} [config] 行配置。
 * @returns {void}
 */
export function apply(ctx, config = {}) {
  const tools = ctx?.tools
  if (!tools?.register) return undefined
  const baseURL = (config.baseUrl ?? DEFAULT_BASE_URL).replace(/\/+$/, '')
  const fetchImpl = config.fetch ?? globalThis.fetch
  const timeoutMs = config.timeoutMs

  const ocr = {
    name: 'bma3_ocr',
    description: '用 DeepSeek 官方 OCR 识别本地文件里的文字（PDF/DOC/DOCX/PPT/PPTX/PNG/JPEG/WebP/GIF/BMP/TIFF），按页返回。文件会发送到 api.deepseek.com。',
    parameters: {
      type: 'object',
      properties: {
        filePath: { type: 'string', description: '本地文件绝对路径（例如 qq_media_view 返回的 path）' },
        purpose: { type: 'string', enum: [...OCR_PURPOSES], description: '默认 text_recognition' },
      },
      required: ['filePath'],
      additionalProperties: false,
    },
    output: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] },
    execute: async (args = {}) => {
      try {
        const apiKey = await resolveApiKey(ctx)
        if (!apiKey) return failure(new Error(`${CREDENTIAL_REF} 未配置，无法调用 DeepSeek OCR`))
        const upload = readUpload(args.filePath, OCR_MAX_BYTES)
        const purpose = OCR_PURPOSES.includes(args.purpose) ? args.purpose : 'text_recognition'
        const result = await callOcr({
          fetchImpl, baseURL, apiKey, purpose, timeoutMs,
          file: { bytes: upload.bytes, name: path.basename(upload.path) },
        })
        return { text: JSON.stringify({ ok: true, file: upload.path, ...result }, null, 2) }
      } catch (error) {
        return failure(error)
      }
    },
  }

  const asr = {
    name: 'bma3_asr',
    description: '用 DeepSeek 官方 ASR 把本地音频转写为文字（mp3/amr/ogg/opus/wav 均可，插件用 ffmpeg 转成 16k 单声道 WAV）。最长 120 秒、8 MiB。音频会发送到 api.deepseek.com。',
    parameters: {
      type: 'object',
      properties: {
        filePath: { type: 'string', description: '本地音频文件绝对路径（例如 qq_media_view 下载的语音）' },
      },
      required: ['filePath'],
      additionalProperties: false,
    },
    output: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] },
    execute: async (args = {}) => {
      let cleanup
      try {
        const apiKey = await resolveApiKey(ctx)
        if (!apiKey) return failure(new Error(`${CREDENTIAL_REF} 未配置，无法调用 DeepSeek ASR`))
        const source = path.resolve(String(args.filePath ?? ''))
        if (!fs.existsSync(source)) throw new Error(`文件不存在：${source}`)
        const seconds = await probeSeconds(source)
        if (seconds !== undefined && seconds > ASR_MAX_SECONDS) {
          throw new Error(`音频 ${seconds.toFixed(1)} 秒，超过 ASR 上限 ${ASR_MAX_SECONDS} 秒`)
        }
        const transcoded = await transcodeForAsr(source)
        cleanup = transcoded.cleanup
        const upload = readUpload(transcoded.path, ASR_MAX_BYTES)
        const result = await callAsr({
          fetchImpl, baseURL, apiKey, timeoutMs,
          file: { bytes: upload.bytes, name: 'audio.wav' },
        })
        return { text: JSON.stringify({ ok: true, file: source, seconds: seconds ?? null, ...result }, null, 2) }
      } catch (error) {
        return failure(error)
      } finally {
        cleanup?.()
      }
    },
  }

  for (const definition of [ocr, asr]) {
    try {
      if (typeof ctx.effect === 'function') ctx.effect(() => tools.register(definition), `bma3-media.${definition.name}`)
      else tools.register(definition)
    } catch {
      // 重复注册说明另一个 loader 行已经挂过这两个工具。
    }
  }
  return undefined
}
