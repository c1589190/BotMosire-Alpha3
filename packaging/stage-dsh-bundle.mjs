import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { createReadStream, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const [stage, launcher] = process.argv.slice(2)
const { platform, arch } = process
assert.ok(['linux', 'win32'].includes(platform) && arch === 'x64', 'Unsupported bundle platform')

const packagePath = join(stage, 'package.json')
const bundle = JSON.parse(readFileSync(packagePath, 'utf8'))
const version = bundle.version
const actualVersion = execFileSync(launcher, ['--version'], { encoding: 'utf8', windowsHide: true }).trim()
assert.equal(actualVersion, 'botmosire-a3 ' + version)

const hash = createHash('sha256')
for await (const chunk of createReadStream(join(stage, 'runtime', 'image.tar.gz'))) hash.update(chunk)
writeFileSync(join(stage, 'runtime', 'manifest.json'), JSON.stringify({
  version, platform, arch, dataFormat: 1, sha256: hash.digest('hex'),
}) + '\n')

const pluginName = bundle.name
if (platform === 'win32') bundle.name = '@mosire/bma3-dsh-managed'
bundle.description = 'BMA3 DSH bundle with a managed ' + (platform === 'win32' ? 'Windows x64' : 'Linux x86_64') + ' Java app-image.'
bundle.files.push('runtime')
bundle.dsh.engines.dsh = platform === 'linux' ? '>=0.2.2-rc.5' : '>=0.2.2-rc.3'
writeFileSync(packagePath, JSON.stringify(bundle, null, 2) + '\n')

if (platform === 'win32') {
  const patch = readFileSync(new URL('./cordis-windows.patch.yml', import.meta.url), 'utf8')
    .replaceAll(pluginName, bundle.name)
  const inboxRow = /^([ \t]*)module: inbox(\r?\n)/m
  assert.match(patch, inboxRow)
  writeFileSync(join(stage, 'cordis.patch.yml'), patch.replace(inboxRow, '$1module: inbox$2$1source: bundled$2'))
}
