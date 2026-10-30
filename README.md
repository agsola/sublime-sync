# sublime-sync

**English** · [Español](README.es.md)

Read every tab you have open in **Sublime Text** — including unsaved ones — from your
**Android phone**, securely, from anywhere. With a Time Machine–style history as a backup.

> **Status:** v0.2 — reading and editing. Install: [docs/install.md](docs/install.md).

## The problem

Many people use Sublime Text as a scratchpad: dozens of untitled tabs that are never saved.
That text lives only on one computer. When you are away from it, you can't read it, and if the
laptop dies you may lose it.

## What it does

- Mirrors all open Sublime tabs (saved and unsaved, all windows) to your phone in seconds.
- Works behind NAT: connects directly to your Mac over **Tailscale** when possible, otherwise
  through a small relay **server** you host.
- Asks both at once and prefers the Mac; the app always shows where the data came from and how
  old it is (`Mac · Tailscale · live`, `Server · live`, `Server · copy from 2 h ago`, `Offline · cache`).
- Keeps an encrypted **history** on the server (every snapshot for 24 h, hourly for 30 days,
  daily after that) and lets you browse back in time.
- Reads like Sublime: a **tab bar** on top and the full text below; swipe between tabs. Each
  tab shows when it was **last modified**; sort tabs as in Sublime or most recent first.
- One-tap **copy the full text** of any tab.
- **Edit from the phone** with a plain-text editor (Sublime's monospaced font, undo/redo,
  find and replace, auto-indent). Everything you type is saved on the phone at once and sent
  every few seconds; in Sublime it appears as a new "✎ title" tab, never touching the original.
  If that tab was changed on the Mac meanwhile, a numbered copy opens instead.
- Create new tabs, hide tabs (with undo), mark tabs as private (shown as dots in lists) and
  close tabs in Sublime when nothing would be lost.

## Security model

- **End-to-end encryption.** Content is compressed (Brotli) and encrypted on the Mac. The
  server and any proxy/CDN in front of it only see opaque ciphertext — not even tab titles.
- **Pairing by QR.** The Mac generates a random 256-bit key and shows it as a QR code; the
  phone scans it once. Separate keys for encryption and server authentication are derived from it.
- **Keys in the OS vault.** macOS Keychain on the Mac; Android Keystore on the phone, unlocked
  with your fingerprint (or device PIN). The local cache on the phone is encrypted too.
- **Anti-rollback.** Each snapshot carries a monotonic counter inside the ciphertext.

## Components

```
Sublime Text ──(plugin)──► macOS agent ──(Tailscale)──► Android app
                                │                           ▲
                                └──► relay server ──────────┘
                                     (stores ciphertext + history)
```

| Folder     | What                                    | Tech                      |
|------------|-----------------------------------------|---------------------------|
| `plugin/`  | Sublime Text plugin, read-only          | Python (no dependencies)  |
| `agent/`   | macOS background agent (LaunchAgent)    | Go                        |
| `server/`  | Relay + encrypted history (systemd)     | Go + SQLite               |
| `android/` | Phone app                               | Kotlin + Jetpack Compose  |

## Requirements

- macOS with Sublime Text 4
- Android 11+
- Tailscale on both Mac and phone (optional, for the direct path)
- A Linux server with nginx and HTTPS for the relay (optional but recommended; works behind Cloudflare)

## Documentation

- Design: [docs/design.md](docs/design.md) · [docs/design.es.md](docs/design.es.md); editing (v0.2): [docs/design-v0.2.md](docs/design-v0.2.md) · [docs/design-v0.2.es.md](docs/design-v0.2.es.md)
- Installation: [docs/install.md](docs/install.md) · [docs/install.es.md](docs/install.es.md)
- Cleaning up after a build: [docs/cleanup.md](docs/cleanup.md) · [docs/cleanup.es.md](docs/cleanup.es.md)

## Keeping your deployment private

This repository contains no deployment data. Put your own domain, hosts and credentials in
git-ignored files (`local/`, `*.local.*`, `.env`) — see [.gitignore](.gitignore).

Before publishing, run `scripts/check-leaks.sh`. It looks for secrets, private files,
e-mail addresses, IPs, your home path and your own private strings, listed one regex per
line in the git-ignored `local/private-patterns.txt`. `scripts/check-leaks.sh --install-hook`
runs it before every commit, and `--history` also scans past commits.

## License

To be decided.
