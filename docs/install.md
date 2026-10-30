# Installing sublime-sync

**English** · [Español](install.es.md)

You need: a Mac with Sublime Text 4 and Go 1.23+, an Android 11+ phone, and
(recommended) a Linux server with systemd, nginx and HTTPS. Tailscale on the Mac and
the phone is optional but gives the fastest, most private path.

Replace `sync.example.com` with your own domain everywhere below.

## 1. Relay server (Linux)

1. Point a (sub)domain at the server and serve it over HTTPS with nginx, proxying to
   `http://127.0.0.1:8787` with WebSocket support.
   - **CloudPanel:** create a *Reverse Proxy* site with URL `http://127.0.0.1:8787` and
     issue a Let's Encrypt certificate. Its default vhost already forwards WebSockets.
   - **Plain nginx:** see [deploy/server/nginx-sublime-sync.conf](../deploy/server/nginx-sublime-sync.conf).
   - **Cloudflare proxy:** works. Use SSL mode *Full (strict)*; WebSockets are on by default.
2. Build the server for your server's CPU and copy it with the deploy files:
   ```sh
   GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -o sublime-sync-server ./cmd/sublime-sync-server   # or GOARCH=amd64
   scp sublime-sync-server deploy/server/install.sh deploy/server/sublime-sync.service you@server:
   ```
3. On the server:
   ```sh
   sudo ./install.sh ./sublime-sync-server
   ```
   It creates an unprivileged `sublime-sync` user and a hardened systemd service, and
   prints an **install token** once (also stored in `/etc/sublime-sync/env`). Re-running
   upgrades the binary and keeps the data and the token.
4. Check: `curl https://sync.example.com/health` → `ok`.

## 2. Mac agent and Sublime plugin

From the repository root:

```sh
deploy/mac/install.sh                      # builds the agent, installs the plugin
~/.local/bin/sublime-sync-agent init --server https://sync.example.com --install-token <token>
deploy/mac/install.sh                      # now also starts the agent (LaunchAgent)
~/.local/bin/sublime-sync-agent status
```

- `init` creates a random 256-bit key in the macOS Keychain and registers with the
  server. Tailscale must be running (or pass `--mac-url`).
- The plugin is copied to `~/Library/Application Support/Sublime Text/Packages/sublime-sync/`.
  It only reads tabs; it never changes them.
- Logs: `~/Library/Application Support/sublime-sync/agent.log`.

## 3. Android app

1. Build it (or use a release APK):
   ```sh
   cd android && ./gradlew :app:assembleRelease
   ```
   For a signed release build, create `android/keystore.local.properties` (git-ignored)
   with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.
2. Install the APK on the phone (allow installing from this source when asked).
3. Open it, unlock with your fingerprint or PIN, tap **Scan QR code**.
4. On the Mac run `~/.local/bin/sublime-sync-agent pair` and scan the QR. It contains the
   secret key: don't photograph or share it. Press Ctrl-C afterwards to clear the screen.

## What the app shows

| Indicator | Meaning |
|---|---|
| 🟢 Mac · Tailscale · live | Read directly from the Mac over Tailscale |
| 🔵 Server · live | Mac is online, read through the relay |
| 🟡 Server · copy from X | Mac is off or asleep; last copy on the relay |
| ⚪ Offline · cache from X | No network; last copy stored (encrypted) on the phone |

The clock icon opens the history. Tap a time to see the tabs as they were.

## Maintenance

- **Rotate the key:** `sublime-sync-agent rotate`, restart the agent
  (`deploy/mac/install.sh`), run `pair` and tap **Scan new QR** at the bottom of the
  app's tab list. Older history stays readable. (**Unpair** erases every key on the phone.)
- **Uninstall (Mac):** `launchctl bootout gui/$(id -u)/io.github.agsola.sublime-sync.agent`,
  delete `~/.local/bin/sublime-sync-agent`, the plugin folder, the data folder and the
  Keychain item `io.github.agsola.sublime-sync`.
- **Uninstall (server):** `sudo systemctl disable --now sublime-sync`, then remove
  `/opt/sublime-sync`, `/var/lib/sublime-sync` and `/etc/sublime-sync`.
