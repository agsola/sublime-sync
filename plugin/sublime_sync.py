"""sublime-sync: mirrors every open tab to the local sublime-sync agent and
applies the edits sent from the phone.

It only changes Sublime when applying a phone command (opening or updating a
"✎" tab, or closing a tab when that loses nothing), plus a stable id stored
in each view's settings. If the agent is not running, nothing happens.
"""

import json
import socket
import threading
import uuid

import sublime
import sublime_plugin

from .commands import close_decision, draft_title, plan_draft, text_hash
from .snapshot import (MAX_TAB_CHARS, Debouncer, TextCache, encode_bye,
                       file_mtime_ms, mark_unchanged, send_timeout,
                       socket_path, tab_from_view_data, window_name)

DEBOUNCE_SECONDS = 2.0
SWEEP_MS = 60 * 1000
POLL_MS = 3 * 1000

UID = "sublime_sync_uid"
DRAFT = "sublime_sync_draft"
APPLIED = "sublime_sync_applied"
REV = "sublime_sync_rev"

_loaded = False
_texts = TextCache()
_results = []  # results to report in the next exchange
_results_lock = threading.Lock()  # appended on the main thread, sent from the async one
# Change count of each view as last received by the agent; unchanged views are
# sent without their text (the agent keeps it).
_sent_counts = {}


def _uid(view):
    s = view.settings()
    uid = s.get(UID)
    if not uid:
        uid = uuid.uuid4().hex
        s.set(UID, uid)
    return uid


def _read(view):
    size = view.size()
    return (view.substr(sublime.Region(0, min(size, MAX_TAB_CHARS))),
            size > MAX_TAB_CHARS)


def _collect():
    """Returns (tabs, change counts by view id)."""
    tabs = []
    counts = {}
    seen = set()
    for window in sublime.windows():
        wname = window_name(window.project_file_name(), window.folders())
        for index, view in enumerate(window.views()):
            seen.add(view.id())
            text, truncated = _texts.get(
                view.id(), view.change_count(), lambda v=view: _read(v))
            syntax = view.syntax()
            dirty = view.is_dirty()
            path = view.file_name()
            tab = tab_from_view_data(
                view.id(), window.id(), index, view.name(), path,
                dirty, syntax.name if syntax else "", text,
                truncated, 0 if dirty else file_mtime_ms(path), wname)
            tab["uid"] = _uid(view)
            draft = view.settings().get(DRAFT)
            if draft:
                tab["draft"] = draft
            counts[view.id()] = view.change_count()
            mark_unchanged(tab, counts[view.id()], _sent_counts)
            tabs.append(tab)
    _texts.retain(seen)
    return tabs, counts


def _exchange(messages):
    """Sends our lines, then reads the agent's reply.

    Returns the reply dict ({"commands": [...], "needFull": bool}), or None
    if the agent couldn't be reached.
    """
    payload = b"".join(messages)
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.settimeout(send_timeout(len(payload)))
    try:
        sock.connect(socket_path())
        sock.sendall(payload)
        sock.shutdown(socket.SHUT_WR)
        data = b""
        while not data.endswith(b"\n"):
            chunk = sock.recv(65536)
            if not chunk:
                break
            data += chunk
    except OSError:
        return None  # agent not running; keep results for later
    finally:
        sock.close()
    try:
        return json.loads(data.decode("utf-8"))
    except ValueError:
        return {}


def _send(first_message):
    """Sends a message plus pending results; returns the reply or None."""
    global _results
    with _results_lock:
        sent, _results = _results, []
    messages = [first_message]
    if sent:
        messages.append((json.dumps({"type": "results", "results": sent},
                                    ensure_ascii=False) + "\n").encode("utf-8"))
    reply = _exchange(messages)
    if reply is None:
        with _results_lock:
            _results = sent + _results
        return None
    commands = reply.get("commands") or []
    if commands:
        sublime.set_timeout(lambda: _apply_all(commands), 0)
    return reply



def _snapshot_now():
    global _sent_counts
    try:
        tabs, counts = _collect()
        reply = _send((json.dumps({"type": "snapshot", "tabs": tabs},
                                  ensure_ascii=False) + "\n").encode("utf-8"))
        if reply is None:
            _sent_counts = {}  # agent down: send everything next time
        elif reply.get("needFull"):
            _sent_counts = {}
            _debouncer.trigger()
        else:
            _sent_counts = counts
    except Exception as e:  # never break the editor
        print("sublime-sync:", e)


def _poll():
    if not _loaded:
        return
    try:
        _send(b'{"type":"poll"}\n')
    except Exception as e:
        print("sublime-sync:", e)
    sublime.set_timeout_async(_poll, POLL_MS)


# --- applying phone commands (main thread) ---------------------------------

def _all_views():
    for window in sublime.windows():
        for view in window.views():
            yield window, view


def _find(key, value):
    for window, view in _all_views():
        if view.settings().get(key) == value:
            return window, view
    return None, None


def _text(view):
    return view.substr(sublime.Region(0, view.size()))


def _result(cmd, status, reason="", uid=""):
    with _results_lock:
        _results.append({"id": cmd["id"], "status": status, "reason": reason,
                         "uid": uid})


def _open_draft(window, cmd, title_hint):
    names = [v.name() for _, v in _all_views()]
    view = window.new_file()
    view.set_name(draft_title(title_hint, names))
    _write(view, cmd)
    return view


def _write(view, cmd):
    text = cmd.get("text", "")
    view.run_command("sublime_sync_replace", {"text": text})
    s = view.settings()
    s.set(DRAFT, cmd["draftId"])
    s.set(APPLIED, text_hash(text))
    s.set(REV, cmd.get("rev", 0))


def _apply_draft(cmd):
    window, view = _find(DRAFT, cmd.get("draftId"))
    state = None
    if view is not None:
        s = view.settings()
        state = (s.get(APPLIED), s.get(REV, 0), text_hash(_text(view)))
    plan = plan_draft(state, cmd.get("rev", 0))
    title = cmd.get("title", "")
    if plan == "skip":
        _result(cmd, "applied", "already applied", _uid(view))
    elif plan == "update":
        _write(view, cmd)
        _result(cmd, "applied", "", _uid(view))
    elif plan == "conflict":
        # The "✎" tab changed on the Mac: it stays as an ordinary tab and the
        # phone's text goes to a new numbered copy, which the draft now follows.
        for key in (DRAFT, APPLIED, REV):
            view.settings().erase(key)
        new = _open_draft(window, cmd, title)
        _result(cmd, "conflict", "changed on the Mac", _uid(new))
    else:
        target = sublime.active_window()
        if cmd.get("sourceUid"):
            w, _ = _find(UID, cmd["sourceUid"])
            target = w or target
        if target is None:  # Sublime running with no windows open
            sublime.run_command("new_window")
            target = sublime.active_window()
        new = _open_draft(target, cmd, title)
        _result(cmd, "applied", "", _uid(new))


def _apply_close(cmd):
    _, view = _find(UID, cmd.get("uid"))
    if view is None:
        _result(cmd, "applied", "already closed")
        return
    s = view.settings()
    text = _text(view)
    ok, reason = close_decision(
        view.is_dirty(), bool(view.file_name()), bool(s.get(DRAFT)),
        text_hash(text), s.get(APPLIED), text == "")
    if not ok:
        _result(cmd, "refused", reason, s.get(UID, ""))
        return
    if view.is_dirty():
        view.set_scratch(True)  # safe per close_decision: never show a save prompt
    view.close()
    _result(cmd, "applied")


def _apply_all(commands):
    for cmd in commands:
        try:
            if cmd.get("type") == "draft":
                _apply_draft(cmd)
            elif cmd.get("type") == "close":
                _apply_close(cmd)
        except Exception as e:
            print("sublime-sync: command failed:", e)
            _result(cmd, "refused", "error in Sublime: %s" % e)
    _debouncer.trigger()  # report the new state soon


class SublimeSyncReplaceCommand(sublime_plugin.TextCommand):
    """Replaces the whole buffer; used only for "✎" tabs from the phone."""

    def run(self, edit, text=""):
        self.view.replace(edit, sublime.Region(0, self.view.size()), text)


# --- lifecycle --------------------------------------------------------------

_debouncer = Debouncer(DEBOUNCE_SECONDS, _snapshot_now,
                       schedule=sublime.set_timeout_async)


def _sweep():
    if not _loaded:
        return
    _snapshot_now()
    sublime.set_timeout_async(_sweep, SWEEP_MS)


def plugin_loaded():
    global _loaded
    _loaded = True
    sublime.set_timeout_async(_sweep, 1000)
    sublime.set_timeout_async(_poll, POLL_MS)


def plugin_unloaded():
    global _loaded
    _loaded = False
    _debouncer.cancel()
    with _results_lock:
        sent = list(_results)
    messages = [encode_bye()]
    if sent:  # don't lose answers to commands applied just before quitting
        messages.append((json.dumps({"type": "results", "results": sent},
                                    ensure_ascii=False) + "\n").encode("utf-8"))
    _exchange(messages)


class SublimeSyncListener(sublime_plugin.EventListener):
    def on_modified_async(self, view):
        _debouncer.trigger()

    def on_new_async(self, view):
        _debouncer.trigger()

    def on_load_async(self, view):
        _debouncer.trigger()

    def on_post_save_async(self, view):
        _debouncer.trigger()

    def on_close(self, view):
        _debouncer.trigger()

    def on_activated_async(self, view):
        _debouncer.trigger()
