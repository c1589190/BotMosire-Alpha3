const CONTENT_RISK = /^Content Exists Risk(?:\s*\(request_id:\s*[^)\r\n]+\))?$/i
const FALLBACK_SUFFIX = '-content-risk-fallback'

export function inboxWakeId(revision, pushRevision) {
  return `bma3-inbox-${revision}-${pushRevision}`
}

function wakeInStep(session, turn) {
  if (!Number.isSafeInteger(session.seq) || typeof session.eventAt !== 'function'
    || typeof session.append !== 'function' || !Array.isArray(session.surface?.nodes)) return null

  let original
  let fallback
  for (let seq = session.seq - 1; seq >= 0; seq -= 1) {
    const event = session.eventAt(seq)
    if (!event) return null
    if (event.type === 'step/start') {
      if (event.data.turn !== turn || event.data.step !== 1 || !original) return null
      if (fallback && (fallback.surfaceOp.startSeq !== original.seq
        || fallback.data.id !== original.data.id + FALLBACK_SUFFIX)) return null
      if (!session.surface.nodes.includes((fallback ?? original).seq)) return null
      return { original, fallback }
    }
    if (event.type === 'turn/start' || event.type === 'step/end'
      || event.type === 'assistant/message' || event.type === 'tool/call') return null
    if (event.type !== 'user/message') continue

    const { id, source } = event.data
    if (source?.kind === 'runtime-context') continue
    if (source?.kind !== 'bma3-inbox' || !Number.isSafeInteger(source.revision) || source.revision < 0
      || !Number.isSafeInteger(source.pushRevision) || source.pushRevision <= 0) return null
    const wakeId = inboxWakeId(source.revision, source.pushRevision)
    if (source.contentRiskFallback === true) {
      const op = event.surfaceOp
      if (fallback || id !== wakeId + FALLBACK_SUFFIX || op?.op !== 'replace'
        || op.startSeq !== op.endSeq || event.sourceEventSeqs?.length !== 1
        || event.sourceEventSeqs[0] !== op.startSeq) return null
      fallback = event
    } else {
      if (original || id !== wakeId || event.surfaceOp !== 'append') return null
      original = event
    }
  }
  return null
}

export function registerInboxRiskRecovery(ctx, acceptsAgent, pause) {
  if (typeof ctx?.on !== 'function') return
  const logger = ctx?.logger?.('bma3-dsh')
  try {
    return ctx.on('agent/request-error', async ({ agent, turn, step, provider, failure, signal }, next) => {
      if (provider !== 'deepseek-official' || step !== 1 || signal?.aborted
        || failure?.status !== 400 || failure.code !== 'INVALID_REQUEST'
        || typeof failure.message !== 'string' || !CONTENT_RISK.test(failure.message)
        || !acceptsAgent(agent)) return next()

      const session = agent.session
      let wake
      try {
        wake = wakeInStep(session, turn)
      } catch {
        logger?.warn?.('inbox content_risk_history_unavailable')
        return next()
      }
      if (!wake) return next()

      const { revision, pushRevision } = wake.original.data.source
      const batch = `rev=${revision} push=${pushRevision}`
      if (wake.fallback) {
        try {
          pause()
          logger?.warn?.(`inbox content_risk_paused ${batch}`)
        } catch {
          logger?.warn?.(`inbox content_risk_pause_not_saved ${batch}`)
        }
        // Leave the original failure terminal, including with a provider-wide retry policy.
        return undefined
      }

      const seq = wake.original.seq
      try {
        const replacement = session.append('user/message', {
          id: wake.original.data.id + FALLBACK_SUFFIX,
          role: 'user',
          content: [{ type: 'text', text: `BMA3 收件箱有新消息（${batch}）。本次唤醒仅作通知，预览已省略。` }],
          source: { kind: 'bma3-inbox', revision, pushRevision, contentRiskFallback: true },
        }, {
          surfaceOp: { op: 'replace', startSeq: seq, endSeq: seq },
          sourceEventSeqs: [seq],
        })
        const nodes = session.surface.nodes
        if (nodes.includes(seq) || !nodes.includes(replacement.seq) || signal?.aborted) return next()
      } catch {
        logger?.warn?.(`inbox content_risk_replace_failed ${batch}`)
        return next()
      }
      return { kind: 'retry' }
    })
  } catch {
    logger?.warn?.('inbox content_risk_hook_unavailable')
  }
}
