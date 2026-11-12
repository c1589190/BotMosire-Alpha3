/**
 * Locate DSH's own tool plugins in the **host** install tree.
 *
 * A preset's child rows resolve from *where the preset is declared* — the DSH profile — and a
 * profile only has the plugins the user installed (`@mosire/bma3-dsh`, connector, …). It does not
 * have `@deepseek-ai/*`, and those tool packages cannot simply be installed into a profile either:
 * they declare the whole host runtime (`dsh-tools`, `dsh-llm`, `cordis`, …) as **peer
 * dependencies**, so importing them from the profile fails with
 * `Cannot find package '@deepseek-ai/dsh-tools'`.
 *
 * The only tree where they resolve together with their peers is the host installation. This module
 * resolves them from there and hands back an absolute path, which the Cordis loader accepts the
 * same way it accepts a bare specifier.
 */
import { createRequire } from 'node:module'
import { existsSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

/** Packages a BMA3 session needs, in preset order. */
export const HOST_TOOL_PACKAGES = Object.freeze([
  '@deepseek-ai/dsh-tool-bash',
  '@deepseek-ai/dsh-tool-pwsh',
  '@deepseek-ai/dsh-tool-fs',
  '@deepseek-ai/dsh-tool-subagent',
  '@deepseek-ai/dsh-tool-subagent-control',
])

/** Package scopes that depend on the tool packages (any of them anchors the lookup). */
const ANCHORS = Object.freeze(['@deepseek-ai/dsh-web-app', '@deepseek-ai/dsh-base', '@deepseek-ai/dsh'])

/** Walk up from a resolved file until the owning package.json. */
function packageDirOf(file) {
  let dir = path.dirname(file)
  for (let hop = 0; hop < 12; hop += 1) {
    if (existsSync(path.join(dir, 'package.json'))) return dir
    const up = path.dirname(dir)
    if (up === dir) break
    dir = up
  }
  return undefined
}

/** Resolve a package entry inside one package's dependency scope. */
function resolveFrom(packageDir, specifier) {
  try {
    return createRequire(path.join(packageDir, 'package.json')).resolve(specifier)
  } catch {
    return undefined
  }
}

/**
 * pnpm-layout fallback: `<host>/node_modules/.pnpm/@deepseek-ai+dsh-tool-bash@<ver>/node_modules/…`.
 * Used when the dependency-scope lookups above miss (hoisted/other linkers).
 */
function scanStore(entryPoint, specifier) {
  const marker = specifier.slice(specifier.indexOf('/') + 1) // dsh-tool-bash
  const flattened = marker.replace('/', '+')
  let dir = path.dirname(entryPoint)
  for (let hop = 0; hop < 8; hop += 1) {
    const store = path.join(dir, 'node_modules', '.pnpm')
    if (existsSync(store)) {
      try {
        const hit = readdirSync(store)
          .filter((name) => name.startsWith(`@deepseek-ai+${flattened}@`))
          .sort()
          .at(-1)
        if (hit !== undefined) {
          const candidate = path.join(store, hit, 'node_modules', specifier)
          if (existsSync(path.join(candidate, 'package.json'))) {
            try {
              return createRequire(path.join(candidate, 'package.json')).resolve(specifier)
            } catch {
              // fall through to the next strategy
            }
          }
        }
      } catch {
        // unreadable store: keep walking
      }
    }
    const up = path.dirname(dir)
    if (up === dir) break
    dir = up
  }
  return undefined
}

/**
 * Absolute entry file of one host tool package, or undefined when the host cannot provide it.
 * @param specifier - host tool package name.
 * @param entryPoint - the running host entry (`process.argv[1]`).
 */
const cache = new Map()

export function hostToolEntry(specifier, entryPoint = process.argv[1]) {
  const cacheKey = `${entryPoint}\u0000${specifier}`
  if (cache.has(cacheKey)) return cache.get(cacheKey)
  const resolved = lookupHostTool(specifier, entryPoint)
  // Never cache a miss: a `dsh plugin` reinstall can temporarily make the host tree
  // unresolvable, and a cached `undefined` would keep bash/read_image unmounted for the
  // rest of the process (the "reinstall makes bash disappear" regression).
  if (resolved !== undefined) cache.set(cacheKey, resolved)
  return resolved
}

/** Number of positive cached entries for one host entry; diagnostics/tests only. */
export function hostToolCacheSize(entryPoint = process.argv[1]) {
  const prefix = `${entryPoint}\u0000`
  return [...cache.keys()].filter((key) => key.startsWith(prefix)).length
}

/** Drop cached lookups for one host entry (default: this process). */
export function invalidateHostToolCache(entryPoint = process.argv[1]) {
  const prefix = `${entryPoint}\u0000`
  for (const key of [...cache.keys()]) {
    if (key.startsWith(prefix)) cache.delete(key)
  }
}

function lookupHostTool(specifier, entryPoint) {
  if (typeof entryPoint === 'string' && entryPoint !== '') {
    for (const anchor of ANCHORS) {
      let anchorDir
      try {
        anchorDir = packageDirOf(createRequire(entryPoint).resolve(anchor))
      } catch {
        anchorDir = undefined
      }
      const found = anchorDir === undefined ? undefined : resolveFrom(anchorDir, specifier)
      if (found !== undefined) return found
    }
    const scanned = scanStore(entryPoint, specifier)
    if (scanned !== undefined) return scanned
  }
  return undefined
}

/**
 * Preset row name for a host tool: a `file:` URL of the package entry when the host provides it,
 * otherwise the bare specifier (what earlier versions shipped — the row then fails to mount, but
 * only that row, and `bma3_self_status.presetTools` reports it).
 *
 * A `file:` URL is used instead of a raw path so Windows drive letters survive `import()`.
 * @param specifier - host tool package name.
 * @param entryPoint - the running host entry.
 */
export function hostToolRowName(specifier, entryPoint = process.argv[1]) {
  const entry = hostToolEntry(specifier, entryPoint)
  return entry === undefined ? specifier : pathToFileURL(entry).href
}

/** Resolution report for diagnostics; never throws. */
export function describeHostTools(entryPoint = process.argv[1]) {
  const resolved = {}
  for (const specifier of HOST_TOOL_PACKAGES) {
    resolved[specifier] = hostToolEntry(specifier, entryPoint) ?? null
  }
  return resolved
}
