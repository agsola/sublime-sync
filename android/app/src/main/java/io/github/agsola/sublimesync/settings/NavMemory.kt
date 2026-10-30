package io.github.agsola.sublimesync.settings

import android.content.Context
import io.github.agsola.sublimesync.model.Tab

/** Where the user was: a Sublime window, a tab in it, and whether that tab was open. */
data class NavState(
    val windowId: Long?,
    val windowName: String,
    val tabUid: String,
    val tabId: Long?,
    val open: Boolean,
)

/** What to show now; null window means "the start". */
data class Restored(val windowId: Long?, val tabId: Long?, val open: Boolean)

/**
 * The tab is found by its stable uid (or view id from old agents); if it's gone
 * we stay in its window (by id, else by name, as ids change when Sublime
 * restarts); if that's gone too, back to the start.
 */
fun resolveNav(nav: NavState, tabs: List<Tab>): Restored {
    val tab = if (nav.tabUid.isNotBlank()) tabs.firstOrNull { it.uid == nav.tabUid }
    else tabs.firstOrNull { it.id == nav.tabId }
    if (tab != null) return Restored(tab.window, tab.id, nav.open)
    val window = tabs.firstOrNull { it.window == nav.windowId && (nav.windowName.isBlank() || it.windowName == nav.windowName) }?.window
        ?: tabs.firstOrNull { nav.windowName.isNotBlank() && it.windowName == nav.windowName }?.window
    return Restored(window, null, open = false)
}

/** Keeps the last position across app restarts (not secret: ids and a window name). */
class NavStore(context: Context) {
    private val prefs = context.getSharedPreferences("nav", Context.MODE_PRIVATE)

    fun save(n: NavState) {
        prefs.edit()
            .putLong("windowId", n.windowId ?: Long.MIN_VALUE)
            .putString("windowName", n.windowName)
            .putString("tabUid", n.tabUid)
            .putLong("tabId", n.tabId ?: Long.MIN_VALUE)
            .putBoolean("open", n.open)
            .apply()
    }

    fun load(): NavState? {
        if (!prefs.contains("windowId")) return null
        fun long(k: String) = prefs.getLong(k, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
        return NavState(long("windowId"), prefs.getString("windowName", "")!!, prefs.getString("tabUid", "")!!, long("tabId"), prefs.getBoolean("open", false))
    }

    fun clear() = prefs.edit().clear().apply()
}
