#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

bash packaging/build-app-image.sh

BIN="$ROOT/dist/botmosire-a3/bin/botmosire-a3"
[[ -x "$BIN" ]] || { echo "launcher missing: $BIN" >&2; exit 1; }
"$BIN" --version | grep -q 'botmosire-a3'

bash packaging/install.sh --version 0.0.5-hotfix2 \
  --archive "$ROOT/dist/botmosire-a3-linux-x86_64.tar.gz" \
  --prefix "$ROOT/build/install-smoke" --dry-run | grep -q 'systemctl --user'

TMP="$(mktemp -d)"
PID=""
cleanup() {
  if [[ -n "$PID" ]]; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

mkdir -p "$TMP/config"
cat > "$TMP/config/app.yaml" <<EOF
dataDir: $TMP/data
server:
  host: 127.0.0.1
  port: 18865
  mcpPath: /mcp
  tokenEnv: BMA3_SMOKE_TOKEN
qqconnect:
  accounts: []
inbox:
  inboxId: bma3
  httpPort: 18866
logging:
  level: INFO
  dir: $TMP/logs
EOF

BMA3_SMOKE_TOKEN=smoke-token "$BIN" --config "$TMP" >"$TMP/app.log" 2>&1 &
PID=$!

for _ in $(seq 1 80); do
  if curl -fsS "http://127.0.0.1:18866/healthz" >/dev/null 2>&1; then
    echo "smoke PASS: app-image starts and /healthz is 200"
    exit 0
  fi
  if ! kill -0 "$PID" 2>/dev/null; then
    echo "app exited early" >&2
    cat "$TMP/app.log" >&2
    exit 1
  fi
  sleep 0.25
done

echo "health check timed out" >&2
cat "$TMP/app.log" >&2
exit 1
