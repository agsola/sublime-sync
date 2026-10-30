"""Pure helpers for the sublime-sync plugin (no `sublime` import, so they can
be unit-tested with a normal Python)."""

import json
import os
import threading

# Safety cap on what is read from Sublime; the agent cuts further so each tab
# fits the relay once compressed.
MAX_TAB_CHARS = 30 * 1000 * 1000
TITLE_MAX = 80


def socket_path():
    return os.path.expanduser(
        "~/Library/Application Support/sublime-sync/agent.sock")


def tab_title(name, path, text):
    if name:
        return name
    if path:
        return os.path.basename(path)
    for line in text.split("\n"):
        line = line.strip()
        if line:
            return line[:TITLE_MAX]
    return "untitled"


def tab_from_view_data(view_id, window_id, index, name, path, dirty, syntax,
                       text, truncated=False, modified=0, window_name=""):
    return {
        "id": view_id,
        "window": window_id,
        "windowName": window_name,
        "index": index,
        "title": tab_title(name, path, text),
        "path": path or None,
        "dirty": bool(dirty),
        "syntax": syntax or "",
        "text": text,
        "truncated": bool(truncated),
        "modified": int(modified),
    }


def window_name(project_file, folders):
    """Project name, else the first open folder's name, else empty."""
    if project_file:
        base = os.path.basename(project_file)
        return base[:-len(".sublime-project")] if base.endswith(".sublime-project") else base
    if folders:
        return os.path.basename(folders[0].rstrip("/")) or folders[0]
    return ""


def file_mtime_ms(path):
    """mtime of a saved file in unix ms, or 0 if unknown."""
    if not path:
        return 0
    try:
        return int(os.path.getmtime(path) * 1000)
    except OSError:
        return 0


def encode_message(tabs):
    msg = {"type": "snapshot", "tabs": tabs}
    return (json.dumps(msg, ensure_ascii=False) + "\n").encode("utf-8")


def encode_bye():
    return b'{"type":"bye"}\n'


class Debouncer:
    """Calls fn once, `delay` seconds after the last trigger().

    `schedule(delay_ms, callback)` runs callback later; in Sublime it is
    sublime.set_timeout_async, so no thread is created per keystroke.
    """

    def __init__(self, delay, fn, schedule=None):
        self._delay_ms = int(delay * 1000)
        self._fn = fn
        self._schedule = schedule or _thread_schedule
        self._lock = threading.Lock()
        self._generation = 0

    def trigger(self):
        with self._lock:
            self._generation += 1
            generation = self._generation
        self._schedule(self._delay_ms, lambda: self._fire(generation))

    def cancel(self):
        with self._lock:
            self._generation += 1

    def _fire(self, generation):
        with self._lock:
            if generation != self._generation:
                return
        self._fn()


def _thread_schedule(delay_ms, callback):
    t = threading.Timer(delay_ms / 1000.0, callback)
    t.daemon = True
    t.start()


class TextCache:
    """Remembers each view's text by change count, so unchanged views are not
    re-read from the editor on every snapshot."""

    def __init__(self):
        self._entries = {}

    def get(self, view_id, change_count, read):
        entry = self._entries.get(view_id)
        if entry is not None and entry[0] == change_count:
            return entry[1]
        value = read()
        self._entries[view_id] = (change_count, value)
        return value

    def retain(self, view_ids):
        for vid in list(self._entries):
            if vid not in view_ids:
                del self._entries[vid]


def mark_unchanged(tab, change_count, sent_counts):
    """Drops the text of a tab already sent with this change count."""
    if sent_counts.get(tab["id"]) == change_count:
        tab.pop("text", None)
        tab["unchanged"] = True


def send_timeout(nbytes):
    """Seconds to allow for sending nbytes to the agent (at least 5)."""
    return max(5.0, nbytes / (4.0 * 1024 * 1024))
