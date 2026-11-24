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

node packaging/stage-dsh-bundle.mjs "$STAGE" "$DIST/botmosire-a3/bin/botmosire-a3"
PACKED="$(npm pack --ignore-scripts --pack-destination "$STAGE" "$STAGE")"
BUNDLE="${PACKED%.tgz}-linux-x64-full.tgz"
mv "$STAGE/$PACKED" "$DIST/$BUNDLE"
(
  cd "$DIST"
  sha256sum "$BUNDLE" > "$BUNDLE.sha256"
)
printf '%s\n' "$DIST/$BUNDLE"
