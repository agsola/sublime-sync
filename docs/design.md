# sublime-sync — Design

**English** · [Español](design.es.md)

Date: 2026-10-30

## 1. Intent

The user treats Sublime Text 4 on macOS as a scratchpad: many open tabs that are never saved.
They want to **read those tabs from an Android phone** at any time and from anywhere,
**securely**, and to keep a **Time Machine–style history** as a backup.

The main fear: **closing the laptop and losing text that hadn't been synced yet**.

## 2. Goals

- Read on the phone every tab open in Sublime (saved and unsaved), across all windows.
- Acceptable latency: **≤ 10 min** (real target: seconds).
- Works when the phone is away from home and the Mac is behind NAT.
- Redundancy: if the Mac is off or asleep, read the server's latest copy.
- History you can browse back in time (Time Machine).
- Easily copy the full text of a tab.
- Security: end-to-end encryption; the server never sees plaintext.
- App protected by fingerprint; key stored in the OS secure store.
- Simple, intuitive UI: the tab list must be easy to understand, see, read and copy.

## 3. Non-goals (v1)

- Editing from the phone. **But** the design must leave the way open (see §10).
- iOS, web, multiple users, multiple Macs.
- Publishing on Google Play (installed via APK).

## 4. Anti-goals (a failure even if it "works")

- The server (or whoever compromises it, or an intermediate proxy/CDN) can read the content.
- Content is lost because the lid was closed / the Mac shut down before syncing.
- The plugin modifies, saves or closes Sublime tabs, or slows down typing.
- The key is stored in plaintext on the phone or on the Mac.
- A confusing UI where you can't tell where the data came from or how old it is.

## 5. Architecture

```
Sublime Text ──(Python plugin, official API)──► macOS agent (Go, launchd)
                                                  │  compress (Brotli) + encrypt (E2E)
                        Tailscale (direct) ◄──────┤────► Linux server (Go, systemd)
                                │                 │        behind nginx + HTTPS
                                ▼                 │        (optionally Cloudflare proxy)
                        Android app (Kotlin) ◄────┘        stores only encrypted blobs + history
```

### 5.1 Reading on the Mac — Sublime plugin

- Python file in `~/Library/Application Support/Sublime Text/Packages/User/` (or its own package).
- Uses the API: iterate `sublime.windows()` → `window.views()`; per view: id, name/title,
  file path if any, syntax, dirty state (`is_dirty`), full content.
- Events: `on_modified_async` (debounced ~2 s), `on_new_async`, `on_close`,
  `on_load_async`, `on_post_save_async`, window activation/deactivation. Plus a periodic
  backup sweep (e.g. every 60 s) in case an event is missed.
- The plugin **only reads**; it sends the snapshot to the local agent over a Unix socket. It
  contains no cryptography (Sublime's Python ships no modern crypto libraries).
- A plugin was chosen over only reading `Auto Save Session.sublime_session` because (a) future
  editing needs a plugin anyway and (b) it is immediate. Measured: Sublime rewrites the session
  file seconds (≤ ~1 min) after you stop typing, so it works as a **fallback source**.

### 5.2 macOS agent (Go, LaunchAgent)

- Receives snapshots from the plugin; computes per-tab diffs (content hash).
- Compresses, encrypts and **uploads to the server as soon as something changes** (after the
  debounce), not in batches. The "I closed the lid and it didn't upload" window shrinks to the
  ~2 s debounce.
- On system sleep / Sublime quitting, forces an immediate send (best effort).
- Without network, queues to disk (encrypted) and retries when connectivity returns.
- Serves the read API to the phone **only on the Tailscale interface** (never on LAN/0.0.0.0).
- Shows the pairing QR code.

### 5.3 Server (Go, single binary, systemd)

- Configurable location (e.g. `sync.example.com`), behind nginx as reverse proxy to
  `http://127.0.0.1:8787`, with HTTPS. Works behind Cloudflare in proxy mode.
- Stores **only encrypted data** in SQLite (+ blobs). Cannot decrypt.
- Functions: receive uploads from the Mac; serve the latest version and the history to the
  phone; live relay (WebSocket) while the Mac is connected; Time Machine retention.
- WebSocket pings < 100 s (Cloudflare idle timeout); nginx vhost with `Upgrade` headers.

### 5.4 Android app (Kotlin + Jetpack Compose, APK)

- On open: biometric unlock (BiometricPrompt, falling back to device PIN/pattern).
- Fires requests **in parallel** to the Mac (Tailscale) and the server. Uses the freshest
  available answer, **preferring the Mac** if it responds within a short window (e.g. 1.5 s).
- Local cache of the last known version (encrypted) so it opens instantly and works offline.
- Source indicator always visible:
  - **"Mac · Tailscale · live"**
  - **"Server · live"** (Mac connected to the server but not reachable over Tailscale)
  - **"Server · copy from X ago"** (Mac disconnected)
  - **"Offline · cache from X ago"**
- Date/time of the displayed data always visible.

## 6. Security

- **Pairing**: the Mac agent generates a random 256-bit master key and shows it as a **QR**
  (along with the server URL, the Mac's Tailscale address and a device id). The phone scans it
  once. (Generating it on the phone was rejected: it would have to be carried to the Mac by
  hand; same security level.)
- Separate keys are derived from the master key (HKDF):
  - content encryption key (AEAD: XChaCha20-Poly1305 or AES-256-GCM),
  - server authentication token (the server only stores its hash),
  - credential for the Mac agent's API over Tailscale.
- All content travels and is stored encrypted; minimal plaintext metadata (opaque ids, size,
  timestamp). **Tab titles are encrypted too.**
- Anti-rollback: each snapshot carries a monotonic counter inside the ciphertext.
- Android: master key in the **Android Keystore** (wrapped by a Keystore key that requires
  biometric authentication). Local cache encrypted with that key.
- Mac: key in the **macOS Keychain**.
- Transport: HTTPS on top of E2E encryption; Tailscale already encrypts.
- Revocation: regenerate the key (re-pair) and invalidate the old one.
- Known limitation: the agent stores the key through macOS's `security` tool, so any process
  running as the same macOS user can read it without a prompt. Protecting it from your own
  user account would need a signed native agent; out of scope for v1.

## 7. History (Time Machine)

- Full snapshot (all tabs) at most every 5 min, only when something changed.
  Per-tab deduplication (an unchanged tab reuses its encrypted blob).
- Retention: everything for the last 24 h; one per hour for 30 days; one per day after that
  (indefinitely unless a storage limit is configured).
- Pruning is done by the server from metadata (no decryption needed).
- In the app: a timeline/slider to pick a moment and see the tabs as they were; copying works
  there too.

## 8. Android UI

Priority: **read and copy without thinking**.

- **Main screen (list)**: top bar with source indicator + "updated X ago".
  Each tab is a card with:
  - a clear title: file name if any; otherwise the first non-empty line;
  - an unsaved marker and which window it belongs to (grouped by window);
  - 3–4 preview lines in readable type;
  - a **"Copy"** button right on the card (copies the full text).
- **Detail**: full text, large type, optional monospace, selectable; pinned "Copy all"
  button; find in text.
- Global search by content in the list.
- **History**: clock button → timeline; picking a moment puts the list in a clearly marked
  "viewing copy from dd/mm hh:mm" mode, with a button to go back to "now".
- Follows the system light/dark theme.

## 9. Constraints and environment

- Mac: macOS, Sublime Text 4, Sublime's plugin-host Python (3.8+).
- Server: Linux with nginx (e.g. CloudPanel) as reverse proxy to `127.0.0.1:8787` with HTTPS.
  Installed by a sudo user: systemd service running as its own unprivileged user. No Docker.
- Behind Cloudflare proxy: request size limits and idle WebSocket timeout.
- One user, one Mac, one phone (the design must not prevent adding a second phone).

## 10. Preparing for future editing

- Edits from the phone would be sent as a **new tab** in Sublime, never touching the original
  (e.g. titled "Edited on phone — <original title>").
- The protocol must have a phone → Mac channel (encrypted the same way) even though v1 doesn't
  use it; the plugin opens the new tab (`window.new_file()` + insert text).

## 11. Implementation decisions

- Languages: Go for agent and server (shared protocol/crypto code); Kotlin + Compose for Android.
- Exact crypto library, message format, SQLite schema, exact debounce, Mac preference window and
  sweep interval: decided in the implementation plan.
- Repo layout: monorepo with `plugin/`, `agent/`, `server/`, `android/`.

### 11.1 Specifics

- **Tailscale on the phone**: the Android device runs the Tailscale app on the same tailnet as
  the Mac. The agent serves plain HTTP only on its Tailscale IP/MagicDNS name (WireGuard already
  encrypts and content is E2E-encrypted on top); the app allows cleartext only for that host. If
  Tailscale is off, the request fails fast and the server wins.
- **Transport**: the agent keeps a WebSocket to the server (uploads + presence heartbeat every
  ~30 s). "Server · live" = a Mac heartbeat in the last ~90 s. The phone subscribes over
  WebSocket to the server (and to the agent over Tailscale) for live changes; otherwise it
  fetches on open and on pull-to-refresh. The same channel will carry phone → Mac edits later.
- **Loss on lid close**: content is never lost (it stays in Sublime and its session file); only
  the last ~2 s may be pending, uploaded on wake. The agent keeps an encrypted local queue of
  anything not yet uploaded. **The fallback is implemented**: read
  `Auto Save Session.sublime_session` (every ~60 s) if the plugin sends nothing.
- **Keys**: a single shared master key (Mac + phones). The first registration on the server is
  authorised with a one-time install token set when installing the server; the agent uses it to
  register the hash of its auth token. Re-pairing generates a new key; app and agent keep old
  keys (keyring with key ids) so older history stays readable. A second phone scans the same QR.
- **QR**: shown on demand (`syncsublime pair`); single-use not required. Key stored in the
  Keychain as the agent's own item.
- **Sizes**: tabs up to 5 MB; larger ones are sent truncated with a visible warning in the app;
  binary buffers are skipped.
- **Server**: idempotent install script + systemd unit + nginx snippet for WebSocket.
  `/health` endpoint; the app shows "server not responding" when it fails.
- **Android**: minSdk 30 (Android 11), latest target. Biometrics falling back to device PIN/pattern.
  Tested on emulator and on a real device.
- **Closed tabs**: disappear from "now" but stay in the history.
- **Plugin**: no external dependencies, compatible with Sublime's Python host (3.8+).

## 12. Compression

- Content is compressed with **Brotli before encryption** (ciphertext can't be compressed).
  Applies to uploads, history, relay and cache. Length-leak risk (CRIME-style) ruled out: no
  outsider can inject text into the tabs. No HTTP `Content-Encoding` on encrypted blobs
  (nothing to gain).

## 13. Public repository

- The repo is public and reusable by others: **nothing private** in it (domains, IPs, users,
  keys, personal paths). Anything deployment-specific goes into git-ignored local config files
  (`local/`, `*.local.*`, `.env`).
