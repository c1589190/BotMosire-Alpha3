#!/usr/bin/env node
/**
 * X1：bma3 jar 的 `tools/list` 合规检查。
 *
 * 检查三件事（对应 spec M5）：
 *  1. 公开名合法：`mcp__<server>__<raw>` ≤64 字符且只含 `[A-Za-z0-9_-]`（DSH/DeepSeek 函数名契约）；
 *  2. 重名：raw 名或公开名撞车 → `dsh-mcp-client` 的整代注册会回滚，必须提前发现；
 *  3. 描述/入参 schema 体检：缺 description、用了 DSH 子集之外的 JSON Schema 关键字
 *     （`minimum`/`maximum`/`anyOf`/`$ref`/`type: [...]` 等）。这些目前**不会**被 DSH 拒绝
 *     （只校验 output schema），但记下来避免将来踩雷。
 *
 * 用法：
 *   curl … tools/list > tools.json
 *   node tools/e2e-local/dsh-tools-list-check.mjs tools.json [--server bma3] [--strict]
 *
 * `--strict` 让 schema 体检也参与退出码（默认只报名字/重名）。
 */
import { readFileSync } from 'node:fs'

const NAME_MAX = 64
const INVALID = /[^A-Za-z0-9_-]/
const SUBSET = new Set([
  'type', 'oneOf', 'properties', 'required', 'additionalProperties', 'items',
  'enum', 'const', 'description', 'title', 'default',
])

function parseArgs(argv) {
  const args = { file: undefined, server: 'bma3', strict: false }
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index]
    if (arg === '--server' && argv[index + 1]) args.server = argv[++index]
    else if (arg === '--strict') args.strict = true
    else if (!arg.startsWith('--')) args.file = arg
  }
  return args
}

/** Accept either a bare `{result:{tools:[…]}}` envelope, an SSE-ish body, or `{tools:[…]}`. */
function extractTools(text) {
  const start = text.indexOf('{')
  if (start < 0) throw new Error('输入里找不到 JSON')
  const payload = JSON.parse(text.slice(start))
  const tools = payload?.result?.tools ?? payload?.tools
  if (!Array.isArray(tools)) throw new Error('找不到 result.tools / tools 数组')
  return tools
}

function walkSchema(node, path, violations, propertyMap = false) {
  if (node === null || typeof node !== 'object') return
  if (Array.isArray(node)) {
    node.forEach((item, index) => walkSchema(item, `${path}[${index}]`, violations))
    return
  }
  for (const [key, value] of Object.entries(node)) {
    if (propertyMap) {
      // Keys inside `properties` are user-chosen field names, not schema keywords.
      walkSchema(value, `${path}.${key}`, violations)
      continue
    }
    if (key === 'properties') {
      walkSchema(value, `${path}.properties`, violations, true)
      continue
    }
    if (!SUBSET.has(key)) violations.push(`${path}.${key}`)
    if (key === 'type' && Array.isArray(value)) violations.push(`${path}.type[]（不支持类型数组）`)
    if (key === 'items' || key === 'oneOf') {
      walkSchema(value, `${path}.${key}`, violations)
      continue
    }
    if (key === 'additionalProperties' && value && typeof value === 'object') {
      walkSchema(value, `${path}.additionalProperties`, violations)
    }
  }
}

function main() {
  const args = parseArgs(process.argv.slice(2))
  if (!args.file) {
    console.error('用法: node tools/e2e-local/dsh-tools-list-check.mjs <tools.json> [--server bma3] [--strict]')
    process.exit(2)
  }
  const tools = extractTools(readFileSync(args.file, 'utf8'))
  const prefix = `mcp__${args.server}__`
  const seenRaw = new Map()
  const seenPublic = new Map()
  const nameFailures = []
  const schemaNotes = []

  for (const tool of tools) {
    const raw = String(tool?.name ?? '')
    const pub = `${prefix}${raw}`
    if (raw === '') nameFailures.push('(空名字)')
    if (INVALID.test(pub)) nameFailures.push(`${pub}: 含非法字符`)
    if (pub.length > NAME_MAX) nameFailures.push(`${pub}: ${pub.length} > ${NAME_MAX}`)
    if (seenRaw.has(raw)) nameFailures.push(`${pub}: 与 ${seenRaw.get(raw)} 重名`)
    seenRaw.set(raw, pub)
    if (seenPublic.has(pub)) nameFailures.push(`${pub}: 公开名撞车`)
    seenPublic.set(pub, raw)

    if (!tool?.description || String(tool.description).trim() === '') {
      schemaNotes.push(`${raw}: 缺 description`)
    }
    const violations = []
    walkSchema(tool?.inputSchema ?? {}, 'inputSchema', violations)
    for (const violation of violations) schemaNotes.push(`${raw}: ${violation}`)
  }

  const longest = tools.reduce((best, tool) => {
    const length = `${prefix}${tool?.name ?? ''}`.length
    return length > best.length ? { name: tool?.name, length } : best
  }, { name: '-', length: 0 })

  console.log(`工具数 ${tools.length} · 名字问题 ${nameFailures.length} · schema 体检提示 ${schemaNotes.length}`)
  console.log(`最长公开名 ${longest.length}/${NAME_MAX}（${longest.name}）`)
  if (nameFailures.length > 0) {
    console.error('\n名字问题：')
    for (const failure of nameFailures) console.error(`- ${failure}`)
  }
  if (schemaNotes.length > 0) {
    console.log('\nschema 体检提示（DSH 目前不拦 input schema，仅供规范收敛）：')
    for (const note of schemaNotes.slice(0, 40)) console.log(`- ${note}`)
    if (schemaNotes.length > 40) console.log(`- …另有 ${schemaNotes.length - 40} 条`)
  }
  const failed = nameFailures.length > 0 || (args.strict && schemaNotes.length > 0)
  process.exit(failed ? 1 : 0)
}

main()
