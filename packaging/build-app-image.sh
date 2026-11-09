#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

./mvnw -q -pl bma3-app -am package -DskipTests

JAR="$(find bma3-app/target -maxdepth 1 -name 'bma3-app-*.jar' ! -name '*.original' | head -1)"
if [[ -z "${JAR}" ]]; then
  echo "app jar not found" >&2
  exit 1
fi

BUILD="$ROOT/build/app-image"
DIST="$ROOT/dist"
rm -rf "$BUILD" "$DIST/botmosire-a3" "$DIST/botmosire-a3-linux-x86_64.tar.gz" \
  "$DIST/botmosire-a3-linux-x86_64.tar.gz.sha256"
mkdir -p "$BUILD/input" "$DIST"
cp "$JAR" "$BUILD/input/app.jar"

jlink \
  --add-modules java.base,java.compiler,java.desktop,java.management,java.naming,java.net.http,java.rmi,java.scripting,java.sql,java.logging,java.xml,jdk.crypto.ec,jdk.httpserver,jdk.unsupported \
  --output "$BUILD/runtime" \
  --strip-debug --no-man-pages --no-header-files --compress=2

jpackage \
  --type app-image \
  --name botmosire-a3 \
  --input "$BUILD/input" \
  --main-jar app.jar \
  --main-class io.mosire.bma3.app.Main \
  --runtime-image "$BUILD/runtime" \
  --java-options "-Dfile.encoding=UTF-8" \
  --dest "$DIST"

tar -C "$DIST" -czf "$DIST/botmosire-a3-linux-x86_64.tar.gz" botmosire-a3
sha256sum "$DIST/botmosire-a3-linux-x86_64.tar.gz" \
  | tee "$DIST/botmosire-a3-linux-x86_64.tar.gz.sha256"
echo "app-image: $DIST/botmosire-a3"
