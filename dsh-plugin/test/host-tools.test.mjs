import assert from 'node:assert/strict'
import { test } from 'node:test'

import { HOST_TOOL_PACKAGES, describeHostTools, hostToolEntry, hostToolRowName } from '../lib/host-tools.js'

test('host tool lookup degrades to the bare specifier instead of throwing', () => {
  const bogus = '/nonexistent/host/bin.js'
  assert.equal(hostToolEntry('@deepseek-ai/dsh-tool-bash', bogus), undefined)
  assert.equal(hostToolRowName('@deepseek-ai/dsh-tool-bash', bogus), '@deepseek-ai/dsh-tool-bash')
  assert.deepEqual(describeHostTools(bogus), Object.fromEntries(HOST_TOOL_PACKAGES.map((name) => [name, null])))
})

test('the tool package list stays in preset order and covers bash/fs/subagent', () => {
  assert.deepEqual([...HOST_TOOL_PACKAGES], [
    '@deepseek-ai/dsh-tool-bash',
    '@deepseek-ai/dsh-tool-pwsh',
    '@deepseek-ai/dsh-tool-fs',
    '@deepseek-ai/dsh-tool-subagent',
    '@deepseek-ai/dsh-tool-subagent-control',
  ])
})
