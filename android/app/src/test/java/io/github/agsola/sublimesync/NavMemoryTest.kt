package io.github.agsola.sublimesync

import io.github.agsola.sublimesync.model.Tab
import io.github.agsola.sublimesync.settings.NavState
import io.github.agsola.sublimesync.settings.Restored
import io.github.agsola.sublimesync.settings.resolveNav
import org.junit.Assert.assertEquals
import org.junit.Test

class NavMemoryTest {
    private val tabs = listOf(
        Tab(1, window = 10, windowName = "web", uid = "a"),
        Tab(2, window = 10, windowName = "web", uid = "b"),
        Tab(3, window = 20, windowName = "notas", uid = "c"),
    )

    @Test fun reopensTheSameTab() {
        val nav = NavState(windowId = 20, windowName = "notas", tabUid = "c", tabId = 3, open = true)
        assertEquals(Restored(20, 3, open = true), resolveNav(nav, tabs))
    }

    @Test fun findsTheTabAfterSublimeRestart() {
        // Sublime restarted: view and window ids changed, the uid did not.
        val restarted = tabs.map { it.copy(id = it.id + 100, window = it.window + 100) }
        val nav = NavState(windowId = 20, windowName = "notas", tabUid = "c", tabId = 3, open = true)
        assertEquals(Restored(120, 103, open = true), resolveNav(nav, restarted))
    }

    @Test fun staysInTheWindowWhenTheTabIsGone() {
        val nav = NavState(windowId = 10, windowName = "web", tabUid = "gone", tabId = 9, open = true)
        assertEquals(Restored(10, null, open = false), resolveNav(nav, tabs))
    }

    @Test fun findsTheWindowByNameWhenItsIdChanged() {
        val nav = NavState(windowId = 99, windowName = "notas", tabUid = "gone", tabId = 9, open = false)
        assertEquals(Restored(20, null, open = false), resolveNav(nav, tabs))
    }

    @Test fun goesToTheStartWhenTheWindowIsGone() {
        val nav = NavState(windowId = 99, windowName = "other", tabUid = "gone", tabId = 9, open = true)
        assertEquals(Restored(null, null, open = false), resolveNav(nav, tabs))
    }
}
