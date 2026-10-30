"""sublime-sync: mirrors every open tab to the local sublime-sync agent.

Read-only: it never modifies, saves or closes views. If the agent is not
running, snapshots are silently dropped (the agent also reads Sublime's
session file as a fallback).
"""

import socket

import sublime
import sublime_plugin

from .snapshot import (MAX_TAB_CHARS, Debouncer, TextCache, encode_bye,
                       encode_message, file_mtime_ms, socket_path,
                       tab_from_view_data, window_name)

DEBOUNCE_SECONDS = 2.0
SWEEP_MS = 60 * 1000

_loaded = False
_texts = TextCache()


def _read(view):
    size = view.size()
    return (view.substr(sublime.Region(0, min(size, MAX_TAB_CHARS))),
            size > MAX_TAB_CHARS)


def _collect():
    tabs = []
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
            tabs.append(tab_from_view_data(
                view.id(), window.id(), index, view.name(), path,
                dirty, syntax.name if syntax else "", text,
                truncated, 0 if dirty else file_mtime_ms(path), wname))
    _texts.retain(seen)
    return tabs


def _send(payload):
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.settimeout(1.0)
    try:
        sock.connect(socket_path())
        sock.sendall(payload)
    except OSError:
        pass  # agent not running; nothing to do
    finally:
        sock.close()


def _snapshot_now():
    try:
        _send(encode_message(_collect()))
    except Exception as e:  # never break the editor
        print("sublime-sync:", e)


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


def plugin_unloaded():
    global _loaded
    _loaded = False
    _debouncer.cancel()
    _send(encode_bye())


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
