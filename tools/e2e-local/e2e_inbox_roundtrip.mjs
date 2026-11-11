#!/usr/bin/env node
/**
 * 统一真环境回归：真 jar（真 HTTP/SSE/ack）+ 真插件客户端（dsh-plugin/lib/inbox.js）。
 *
 * 断言的是这次修的两个 bug：
 *  1. 面板窗口 top-M 之外的条目不能被"标记已推送"或"ack"吞掉；
 *  2. 插件 ack 必须按 itemIds 精确确认，而不是只发 revision。
 *
 * 用法：node e2e_inbox_roundtrip.mjs <inboxHttpPort> <injectPort>
 */
import { createInboxClient } from '../../dsh-plugin/lib/inbox.js'

const inboxPort = Number(process.argv[2])
const injectPort = Number(process.argv[3])
if (!inboxPort || !injectPort) {
  console.error('usage: node e2e_inbox_roundtrip.mjs <inboxHttpPort> <injectPort>')
  process.exit(2)
}

const injections = []
const ackBodies = []
const agent = {
  id: 'session-e2e',
  status: 'idle',
  followup(message) { injections.push(message) },
}
const ctx = { agents: { get: (id) => (id === agent.id ? agent : undefined), list: () => [agent] } }

const realFetch = globalThis.fetch
const fetchImpl = async (url, init) => {
  if (url.endsWith('/ack') && init?.body) {
    const body = JSON.parse(init.body)
    ackBodies.push(body)
  }
  return realFetch(url, init)
}

const client = createInboxClient(ctx, {
  baseUrl: `http://127.0.0.1:${inboxPort}`,
  inboxId: 'bma3',
  token: 'e2e-mcp-token',
  fetchImpl,
  pumpMs: 50,
  reconnectMinMs: 200,
})

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const report = []
function check(name, ok, detail = '') {
  report.push(ok)
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (detail ? `  | ${detail}` : ''))
}

async function inject(payload) {
  const response = await realFetch(`http://127.0.0.1:${injectPort}/inject`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (!response.ok) throw new Error(`inject failed: ${response.status}`)
}

async function waitFor(predicate, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (predicate()) return true
    await sleep(50)
  }
  return false
}

let exitCode = 1
try {
  client.start()
  await sleep(500)
  await client.ack()          // 清场：确认快照里可能残留的条目
  ackBodies.length = 0

  const run = Date.now()
  for (let index = 0; index < 8; index++) {
    await inject({
      post_type: 'message',
      message_type: 'group',
      self_id: 10001,
      group_id: 910000 + index,
      user_id: 20002,
      message_id: run + index,
      time: Math.floor(run / 1000),
      message: [{ type: 'text', data: { text: `低优先 ${index}` } }],
      sender: { nickname: 'LvtN' },
    })
  }

  const first = await waitFor(() => injections.length >= 1, 45_000)
  injections.forEach((message, index) => {
    const lines = String(message?.content?.[0]?.text ?? '').split('\n')
    console.log(`  [injection ${index}] ${lines.length} 行 · ${lines.slice(0, 2).join(' ⏎ ')}`)
  })
  check('第一批低优先消息按批次延迟送达', first, `${injections.length} 次注入`)
  check('面板窗口只装 6 条', /items=6\/8/.test(injections[0]?.content?.[0]?.text ?? ''),
    (injections[0]?.content?.[0]?.text ?? '').split('\n')[0])
  check('注入文本提示还有 2 个来源未显示',
    /另有 2 个来源未显示/.test(injections[0]?.content?.[0]?.text ?? ''))

  const second = await waitFor(() => injections.length >= 2, 20_000)
  check('被截断的 2 条在下一轮补推（不是消失）', second)
  check('第二轮面板正好 2 条', /items=2\/2/.test(injections[1]?.content?.[0]?.text ?? ''),
    (injections[1]?.content?.[0]?.text ?? '').split('\n')[0])

  const withIds = ackBodies.filter((body) => Array.isArray(body.itemIds) && body.itemIds.length > 0)
  check('插件 ack 按 itemIds 精确确认', withIds.length >= 2,
    ackBodies.map((body) => body.itemIds?.length ?? `rev:${body.revision}`).join(','))
  check('两次 ack 合计 8 条', withIds.reduce((sum, body) => sum + body.itemIds.length, 0) === 8,
    withIds.map((body) => body.itemIds.length).join('+'))

  await sleep(1500)
  check('没有重复推送刷屏', injections.length <= 3, `${injections.length} 次注入`)

  const status = client.status()
  check('客户端状态可读（供 bma3_inbox status 用）',
    typeof status.lastRevision === 'number' && status.config.inboxId === 'bma3',
    JSON.stringify({ lastRevision: status.lastRevision, acked: status.ackedRevision, paused: status.paused }))

  exitCode = report.every(Boolean) ? 0 : 1
  console.log(`\nSUMMARY ${report.filter(Boolean).length}/${report.length} passed`)
} catch (error) {
  console.error('FAIL  ' + (error?.stack ?? error))
} finally {
  client.stop()
  process.exit(exitCode)
}
