"""Pure decisions for applying phone commands (no `sublime` import, so they
can be unit-tested with a normal Python)."""

import hashlib

DRAFT_MARK = "✎ "


def text_hash(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:32]


def draft_title(title, existing_titles):
    """'✎ title', or '✎ title (2)', '(3)'... if that name is already open."""
    base = title or "untitled"
    if base.startswith(DRAFT_MARK):
        base = base[len(DRAFT_MARK):]
    name = DRAFT_MARK + base
    taken = set(existing_titles)
    n = 2
    while name in taken:
        name = "%s%s (%d)" % (DRAFT_MARK, base, n)
        n += 1
    return name


def plan_draft(state, rev):
    """What to do with a draft command of revision `rev`.

    state is None when no tab shows this draft yet, else
    (applied_hash, applied_rev, current_hash) of that tab.
    """
    if state is None:
        return "create"
    applied_hash, applied_rev, current_hash = state
    if rev <= applied_rev:
        return "skip"
    if current_hash != applied_hash:
        return "conflict"  # edited on the Mac since the last sync: never overwrite
    return "update"


def close_decision(dirty, has_file, is_draft, current_hash, applied_hash, empty):
    """Whether a tab may be closed without losing anything; (ok, reason)."""
    if empty:
        return True, ""
    if is_draft:
        if current_hash == applied_hash:
            return True, ""
        return False, "changed on the Mac since the last sync"
    if has_file and not dirty:
        return True, ""
    return False, "has unsaved changes"
