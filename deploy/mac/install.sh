#!/usr/bin/env bash
# Builds and installs the sublime-sync agent (LaunchAgent) and Sublime plugin.
# Usage: deploy/mac/install.sh            (from the repository root)
# Re-running upgrades both and restarts the agent.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
label="io.github.agsola.sublime-sync.agent"
bin="$HOME/.local/bin/sublime-sync-agent"
data="$HOME/Library/Application Support/sublime-sync"
plist="$HOME/Library/LaunchAgents/$label.plist"
pkg="$HOME/Library/Application Support/Sublime Text/Packages/sublime-sync"

mkdir -p "$(dirname "$bin")" "$HOME/Library/LaunchAgents"
mkdir -m 700 -p "$data"

echo "Building agent..."
(cd "$root" && go build -trimpath -o "$bin" ./cmd/sublime-sync-agent)

echo "Installing Sublime plugin into $pkg"
mkdir -p "$pkg"
cp "$root/plugin/sublime_sync.py" "$root/plugin/snapshot.py" "$root/plugin/commands.py" "$pkg/"

sed -e "s|__BIN__|$bin|g" -e "s|__LOG__|$data/agent.log|g" \
  "$root/deploy/mac/$label.plist" >"$plist"

if [[ ! -f "$data/config.json" ]]; then
  echo
  echo "Not initialised yet. Run:"
  echo "  $bin init --server https://sync.example.com --install-token <token>"
  echo "then re-run this script to start the agent."
  exit 0
fi

launchctl bootout "gui/$(id -u)/$label" 2>/dev/null || true
# bootout is asynchronous: wait until the old agent is gone.
for _ in $(seq 1 20); do
  launchctl print "gui/$(id -u)/$label" >/dev/null 2>&1 || break
  sleep 0.5
done
launchctl bootstrap "gui/$(id -u)" "$plist"
echo "Agent started. Log: $data/agent.log"
