package io.github.agsola.sublimesync.settings

import android.content.Context
import io.github.agsola.sublimesync.model.Tab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SortOrder { SUBLIME, MODIFIED }

/** WINDOWS_LIST: windows as tabs, each with a list. WINDOWS_TABS: windows, then Sublime's tab bar. */
enum class Layout { WINDOWS_LIST, WINDOWS_TABS }

/** Where the phone reads from. */
enum class Sources { BOTH, MAC_ONLY, SERVER_ONLY }

data class Settings(
    val sort: SortOrder = SortOrder.SUBLIME,
    val mono: Boolean = true,
    val textSize: Int = 16,
    val layout: Layout = Layout.WINDOWS_LIST,
    val sources: Sources = Sources.BOTH,
    /** Block screenshots and the preview in recent apps. */
    val secureScreen: Boolean = false,
    /** Mark copied text as sensitive so Android hides it in the clipboard preview. */
    val sensitiveClipboard: Boolean = false,
    /** How often edits are sent to the Mac or the server. */
    val syncSeconds: Int = 10,
)

/** Display preferences. Not secret, so plain SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(change: (Settings) -> Settings) {
        val s = change(_settings.value)
        prefs.edit()
            .putString("sort", s.sort.name)
            .putBoolean("mono", s.mono)
            .putInt("textSize", s.textSize)
            .putString("layout", s.layout.name)
            .putString("sources", s.sources.name)
            .putBoolean("secureScreen", s.secureScreen)
            .putBoolean("sensitiveClipboard", s.sensitiveClipboard)
            .putInt("syncSeconds", s.syncSeconds)
            .apply()
        _settings.value = s
    }

    private fun load() = Settings(
        sort = enumOr(prefs.getString("sort", null), SortOrder.SUBLIME),
        mono = prefs.getBoolean("mono", true),
        textSize = prefs.getInt("textSize", 16).coerceIn(TEXT_SIZES),
        layout = enumOr(prefs.getString("layout", null), Layout.WINDOWS_LIST),
        sources = enumOr(prefs.getString("sources", null), Sources.BOTH),
        secureScreen = prefs.getBoolean("secureScreen", false),
        sensitiveClipboard = prefs.getBoolean("sensitiveClipboard", false),
        syncSeconds = prefs.getInt("syncSeconds", 10).coerceIn(SYNC_SECONDS),
    )

    private inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
        runCatching { enumValueOf<E>(name!!) }.getOrDefault(def)

    companion object {
        val TEXT_SIZES = 13..24
        val SYNC_SECONDS = 5..60
    }
}

/** Tabs in Sublime's order (window, then position) or most recently modified first. */
fun List<Tab>.sortedFor(order: SortOrder): List<Tab> = when (order) {
    SortOrder.SUBLIME -> this
    SortOrder.MODIFIED -> sortedWith(compareByDescending<Tab> { it.modified }.thenBy { it.window }.thenBy { it.index })
}

/** One Sublime window and its tabs. */
data class WindowGroup(val id: Long, val name: String, val tabs: List<Tab>)

/** Groups tabs by window, keeping Sublime's window order; tabs sorted within each window. */
fun List<Tab>.windows(order: SortOrder): List<WindowGroup> =
    groupBy { it.window }.map { (id, tabs) ->
        WindowGroup(id, tabs.first().windowName, tabs.sortedFor(order))
    }
