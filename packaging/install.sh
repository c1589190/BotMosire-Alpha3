#!/usr/bin/env bash
set -euo pipefail

VERSION=""
ARCHIVE=""
PREFIX="${BMA3_HOME:-$HOME/.local/share/botmosire-a3}"
DRY_RUN=0

usage() {
  cat <<'EOF'
Usage: install.sh --version <v> [--archive <tar.gz>] [--prefix <dir>] [--dry-run]

Installs the BMA3 app-image into a user-owned directory, points `current` at it,
and writes a systemd user unit. It never uses root and never edits the caller's
config or token files.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) VERSION="${2:?}"; shift 2 ;;
    --archive) ARCHIVE="${2:?}"; shift 2 ;;
    --prefix) PREFIX="${2:?}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ -z "$VERSION" ]]; then
  echo "--version is required" >&2
  usage >&2
  exit 2
fi
ARCHIVE="${ARCHIVE:-dist/botmosire-a3-linux-x86_64.tar.gz}"
UNIT_DIR="$HOME/.config/systemd/user"
APP_HOME="$PREFIX/$VERSION/botmosire-a3"

if [[ "$DRY_RUN" == "1" ]]; then
  echo "install $ARCHIVE -> $PREFIX/$VERSION"
  echo "ln -sfn $APP_HOME $PREFIX/current"
  echo "write $UNIT_DIR/botmosire-a3.service"
  echo "systemctl --user daemon-reload && systemctl --user enable --now botmosire-a3"
  exit 0
fi

mkdir -p "$PREFIX/$VERSION" "$UNIT_DIR"
tar -xzf "$ARCHIVE" -C "$PREFIX/$VERSION"
ln -sfn "$APP_HOME" "$PREFIX/current"

cat > "$UNIT_DIR/botmosire-a3.service" <<EOF
[Unit]
Description=BotMosire-Alpha3
After=network.target

[Service]
Type=simple
ExecStart=$APP_HOME/bin/botmosire-a3 --config $PREFIX
EnvironmentFile=-$PREFIX/bma3.env
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
EOF

if command -v systemctl >/dev/null 2>&1; then
  systemctl --user daemon-reload || true
  systemctl --user enable --now botmosire-a3 || true
fi

echo "installed $VERSION at $APP_HOME"
echo "create $PREFIX/bma3.env with BMA3_MCP_TOKEN before starting"
