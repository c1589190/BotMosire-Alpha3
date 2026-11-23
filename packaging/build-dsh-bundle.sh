#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ "$(uname -s)-$(uname -m)" != Linux-x86_64 ]]; then
  echo 'The managed app-image build requires Linux x86_64.' >&2
  exit 1
fi
bash packaging/build-app-image.sh

DIST="$ROOT/dist"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp -a dsh-plugin/{index.js,lib,examples,cordis.patch.yml,README.md,package.json} "$STAGE/"
mkdir "$STAGE/runtime"
cp "$DIST/botmosire-a3-linux-x86_64.tar.gz" "$STAGE/runtime/image.tar.gz"

node --input-type=module - "$STAGE" "$DIST" <<'BMA3_MANIFEST'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFileSync, writeFileSync, createReadStream } from 'node:fs'
import { createHash } from 'node:crypto'
import { join } from 'node:path'

const [stage, dist] = process.argv.slice(2)
const packagePath = join(stage, 'package.json')
const bundle = JSON.parse(readFileSync(packagePath, 'utf8'))
const version = bundle.version
const launcher = join(dist, 'botmosire-a3/bin/botmosire-a3')
assert.equal(execFileSync(launcher, ['--version'], { encoding: 'utf8' }).trim(), 'botmosire-a3 ' + version)
const hash = createHash('sha256')
for await (const chunk of createReadStream(join(stage, 'runtime/image.tar.gz'))) hash.update(chunk)
writeFileSync(join(stage, 'runtime/manifest.json'), JSON.stringify({
  version, platform: 'linux', arch: 'x64', dataFormat: 1, sha256: hash.digest('hex'),
}) + '\n')

const thin = bundle.name
bundle.name = '@mosire/bma3-dsh-managed'
bundle.description = 'BMA3 DSH bundle with a managed Linux x86_64 Java app-image.'
bundle.files.push('runtime')
bundle.dsh.engines.dsh = '>=0.2.2-rc.3'
writeFileSync(packagePath, JSON.stringify(bundle, null, 2) + '\n')
const patchPath = join(stage, 'cordis.patch.yml')
const patch = readFileSync(patchPath, 'utf8').replaceAll(thin, bundle.name)
writeFileSync(patchPath, patch.replace('module: inbox\n', 'module: inbox\n        source: bundled\n'))
BMA3_MANIFEST
BUNDLE="$(npm pack --ignore-scripts --pack-destination "$DIST" "$STAGE")"
(
  cd "$DIST"
  sha256sum "$BUNDLE" > "$BUNDLE.sha256"
)
printf '%s\n' "$DIST/$BUNDLE"
