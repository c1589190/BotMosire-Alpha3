import { execFile } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { access, mkdir, mkdtemp, readFile, rename, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

const run = promisify(execFile)
const payload = new URL('../runtime/', import.meta.url)
const versionPattern = /^[0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9.-]+)?$/
const digestPattern = /^[a-f0-9]{64}$/

export class RuntimeError extends Error {}

export async function readJson(path) {
  try {
    return JSON.parse(await readFile(path, 'utf8'))
  } catch (error) {
    if (error.code === 'ENOENT') return null
    throw error
  }
}

export async function writeJson(path, value) {
  const temporary = path + '.tmp-' + randomUUID()
  try {
    await writeFile(temporary, JSON.stringify(value) + '\n', { mode: 0o600, flag: 'wx' })
    await rename(temporary, path)
  } finally {
    await rm(temporary, { force: true })
  }
}

function validImage(image) {
  return image && typeof image.version === 'string' && versionPattern.test(image.version)
    && image.platform === process.platform && image.arch === process.arch
    && image.dataFormat === 1 && digestPattern.test(image.sha256)
}

function releasePath(root, image) {
  return join(root, 'releases', image.version + '-' + image.sha256)
}

export async function installedImage(root, image) {
  if (!validImage(image)) return null
  const directory = releasePath(root, image)
  const marker = await readJson(join(directory, 'image.json'))
  if (!validImage(marker) || marker.sha256 !== image.sha256 || marker.version !== image.version) return null
  const executable = join(directory, 'bin', 'botmosire-a3')
  await access(executable)
  return { executable, image }
}

export async function installImage(root) {
  const manifest = await readJson(new URL('manifest.json', payload))
  if (!validImage(manifest)) throw new RuntimeError('IMAGE_UNSUPPORTED')
  const existing = await installedImage(root, manifest)
  if (existing) return existing

  const archive = fileURLToPath(new URL('image.tar.gz', payload))
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(archive)) hash.update(chunk)
  if (hash.digest('hex') !== manifest.sha256) throw new RuntimeError('IMAGE_CHECKSUM_MISMATCH')

  const releases = join(root, 'releases')
  await mkdir(releases, { recursive: true, mode: 0o700 })
  const temporary = await mkdtemp(join(releases, '.install-'))
  try {
    // The bundled archive is immutable package content and matches the image manifest.
    await run('tar', ['-xzf', archive, '--no-same-owner', '-C', temporary], { timeout: 30000 })
    const extracted = join(temporary, 'botmosire-a3')
    await access(join(extracted, 'bin', 'botmosire-a3'))
    await writeJson(join(extracted, 'image.json'), manifest)
    try {
      await rename(extracted, releasePath(root, manifest))
    } catch (error) {
      if (!['EEXIST', 'ENOTEMPTY'].includes(error.code)) throw error
    }
    const installed = await installedImage(root, manifest)
    if (!installed) throw new RuntimeError('IMAGE_INVALID')
    return installed
  } finally {
    await rm(temporary, { recursive: true, force: true })
  }
}
