#!/usr/bin/env bash
# Installs or upgrades the sublime-sync relay on a systemd Linux host.
# Usage: sudo ./install.sh /path/to/sublime-sync-server
# Idempotent: re-running upgrades the binary and keeps data and the install token.
set -euo pipefail

bin="${1:?usage: install.sh <sublime-sync-server binary>}"
here="$(cd "$(dirname "$0")" && pwd)"

if [[ $EUID -ne 0 ]]; then
  echo "run as root (sudo)" >&2
  exit 1
fi

if ! id -u sublime-sync >/dev/null 2>&1; then
  useradd --system --no-create-home --shell /usr/sbin/nologin sublime-sync
fi

install -d -m 0755 /opt/sublime-sync/bin
install -d -m 0700 -o sublime-sync -g sublime-sync /var/lib/sublime-sync
install -d -m 0750 /etc/sublime-sync
install -m 0755 "$bin" /opt/sublime-sync/bin/sublime-sync-server

if [[ ! -f /etc/sublime-sync/env ]]; then
  token="$(head -c 32 /dev/urandom | base64 | tr -d '/+=' | head -c 40)"
  umask 077
  cat >/etc/sublime-sync/env <<ENV
LISTEN=127.0.0.1:8787
INSTALL_TOKEN=$token
ENV
  echo "Install token (needed once by 'sublime-sync-agent init'):"
  echo "  $token"
  echo "It is stored in /etc/sublime-sync/env"
fi
chmod 0600 /etc/sublime-sync/env

install -m 0644 "$here/sublime-sync.service" /etc/systemd/system/sublime-sync.service
systemctl daemon-reload
systemctl enable sublime-sync.service >/dev/null
systemctl restart sublime-sync.service
sleep 1
if curl -fsS http://127.0.0.1:8787/health >/dev/null; then
  echo "sublime-sync is running on 127.0.0.1:8787"
else
  echo "sublime-sync did not start; see: journalctl -u sublime-sync" >&2
  exit 1
fi
