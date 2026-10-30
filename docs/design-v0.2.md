# sublime-sync 0.2 — Editing from the phone (design)

**English** · [Español](design-v0.2.es.md) · Base: [design.md](design.md) (v0.1)

Date: 2026-10-30

## 1. Intent

In 0.1 the phone only reads. In 0.2 the user wants to:

- **Edit** any tab on the phone, with a basic but very capable editor and Sublime's font.
  Edits **must never be lost**, even if the app is closed: they are saved on the phone right
  away and synced every X seconds.
- **Create** new tabs from the phone.
- **Hide** tabs (and unhide them), with an undo notice for a few seconds.
- **Really close** a tab in Sublime, without losing anything.
- Mark tabs as **private**: shown as dots in lists; normal when opened and when copied.

## 2. The user's decisions

- When an edit syncs, Sublime opens **a new "✎ title" tab** next to the original, and later
  syncs **update that same tab**. The original is never touched.
- If that "✎" tab was changed on the Mac since the last sync, it is **not overwritten**:
  another "✎ title (2)" opens with the phone's text.
- Hidden and private flags are **synced** (encrypted on the server), so they survive a
  reinstall and apply on another phone.
- Really closing: only when the tab is saved on disk or is an already-synced "✎" tab.
  Otherwise it is refused and the app says why.

## 3. Anti-goals

- Losing edited text: by closing the app, losing the network, a conflict or closing a tab.
- Touching the original Sublime tab when editing.
- The server seeing text, titles, or which tabs are hidden or private.
- A slow editor with long texts.

## 4. Stable tab identity

Sublime's view ids change when it restarts; hidden, private and drafts need a stable identity.

- The plugin stores a `sublime_sync_uid` (UUID) in each view's settings. View settings are kept
  in Sublime's session, so the uid survives restarts. **It doesn't modify the text or mark the
  tab as changed.**
- `Tab` gains two fields: `uid` and `draft` (the phone draft id when it is a "✎" tab).

## 5. Phone → Mac commands

A new channel, encrypted like everything else (AAD `command:<id>`):

| Type | Content | Effect in Sublime |
|---|---|---|
| `draft` | `draftId`, `sourceUid?`, `title`, `text`, `rev` | Creates or updates that draft's "✎" tab |
| `close` | `uid` | Closes the tab if safe; otherwise refuses |

- The phone sends commands to the **Mac over Tailscale** when reachable, otherwise to the
  **server** (per the "Read from" setting). The Mac picks them up from the server at once via
  WebSocket, or by polling every 30 s.
- Each command has a **unique id**: applying it twice does nothing.
- The Mac returns an **encrypted result** per command (`applied` / `conflict` / `refused`,
  with a reason). The app shows it.
- The plugin collects commands from the agent over the same connection it uses to send
  snapshots, plus a light poll every 3 s.
- Conflicts: the plugin stores the hash of the last applied text in the view
  (`sublime_sync_applied`). If the current text differs, the tab changed on the Mac: it is
  unlinked from the draft and "✎ title (2)" opens with the phone's text.

## 6. Synced phone state

An encrypted `phone-state` document on the server (and a local copy) with per-tab flags.

- Versioned writes (compare-and-swap). On a clash, both states are merged and the write retried.
- If the server isn't available (Mac only, or offline), it is kept locally and uploaded later.

## 7. Editor (Android)

- **Edit** button in the text view and **New tab** button on the main screen.
- JetBrains Mono at the size from settings, with line numbers.
- Undo/redo (own history), find and replace, auto-indent on Enter, soft wrap, line and
  character count, "Copy all". No autocorrect.
- **Local autosave:** every change is saved encrypted on the phone (within 0.5 s and when
  leaving the app), with the content key. Reopening shows the draft as it was.
- **Sync:** every X seconds (setting, 10 s by default) when something changed; also when
  leaving the editor. A draft opened but never edited is neither sent nor kept.
- Status shown in the editor: "Saved on this phone", "Sent 5 s ago", "In Sublime",
  "Changed on the Mac: opened as a new copy", "Waiting for Sublime to open", "No connection".
- A list of drafts to resume them.
- Editing a tab that already is a phone "✎" tab continues the same draft.
- Tabs over 5 million characters, or cut to fit the relay, can be read and copied but not
  edited on the phone (the app explains why).

## 8. Hide, private and close (Android)

- **Hide:** per-tab action. The tab disappears from lists and bars; a notice offers "Undo" for
  a few seconds. Settings → "Hidden tabs (N)" to show them again.
- **Private:** per-tab toggle. In lists, bars and search results, title and preview show as
  `••••••`. Opening it shows the text; "Copy" copies the real text.
- **Close in Sublime:** separate action, with confirmation; shows the result.

## 9. Server

New tables, all with encrypted content:

- `commands(id, created, env, result, result_at)`: `POST /v1/commands`,
  `GET /v1/commands?pending=1` (Mac), `PUT /v1/commands/{id}/result` (Mac) and
  `GET /v1/commands/{id}` (phone). Deleted 7 days after being answered.
- `state(name, version, env)`: `GET`/`PUT /v1/state/{name}` with `If-Match: <version>`.
- WebSocket: `{"type":"command"}` notifies the Mac and `{"type":"result","id"}` the phone.
- The agent exposes the same routes over Tailscale.

## 10. Specifics after review

- **After a conflict the draft follows the "(2)" tab;** the original "✎" stays as a normal Mac
  tab. Another conflict opens "(3)", and so on: nothing is ever overwritten or lost.
- **New tab from the phone:** titled "✎ " plus its first line in Sublime, opened in the active
  window; edits of an existing tab open in that tab's window.
- **Sublime closed or plugin stopped:** commands wait on the server and the agent until the
  plugin collects them; the app shows "Waiting for Sublime" after 30 s.
- **Close rule:** only if (a) the tab has no unsaved changes and has a file, or (b) it is a "✎"
  tab whose text hasn't changed since the last sync, or (c) it is empty. Otherwise refused.
- **The plugin is no longer read-only, but only through commands:** it creates, updates and
  closes tabs only when applying a command, and stores the uid in each view's settings. The
  agent decrypts and validates commands; the plugin never sees the key.
- **Private tabs in search:** search also matches their text, but results show dots.
- **Phone-state merge:** each entry carries its timestamp (`{uid: {hidden, private, at}}`);
  the newest wins, so unhiding on one phone holds on another.
- **Replay protection:** the agent remembers applied command ids and rejects commands older
  than 7 days, so the server can't resend an old one.
- **Autosave and locking:** keys stay in memory while the app is unlocked; going to the
  background saves and sends at once, before the 5-minute re-lock.
- **Platforms:** as in 0.1 (Sublime Text 4, macOS, Android 11+).

## 11. Implementation decisions

- Format: what the phone encrypts uses envelope version 2 (no Brotli, as Android only ships the
  decoder); the Mac and the server read both versions.
- Exact message schema, polling and intervals; editor undo implementation; visual design within
  the existing system (Mariana, JetBrains Mono, Atkinson Hyperlegible).
- Version: app 0.2.0; protocol backwards compatible with 0.1 (new fields are optional).
