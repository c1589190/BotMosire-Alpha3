export const DEFAULT_PROMPT = '你是 BMA3 的角色。'

export function promptFor(config = {}) {
  return typeof config.prompt === 'string' && config.prompt.trim() !== ''
    ? config.prompt
    : DEFAULT_PROMPT
}

/** Register the role prompt as the session's complete system prompt section. */
export function apply(ctx, config = {}) {
  const systemPrompt = ctx?.systemPrompt ?? ctx?.get?.('systemPrompt')
  if (!systemPrompt?.section) {
    return
  }
  systemPrompt.section({
    name: 'bma3-persona',
    order: 0,
    text: promptFor(config),
    complete: true,
    interpolate: false,
  })
  ctx?.effect?.(() => () => {})
}
